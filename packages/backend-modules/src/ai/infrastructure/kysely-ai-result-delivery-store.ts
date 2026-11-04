import {
  createCipheriv,
  createDecipheriv,
  createHash,
  hkdfSync,
  randomBytes,
} from "node:crypto";
import { sql, type Kysely } from "kysely";
import type {
  AiDeliveryIdentity,
  AiResultDeliveryStore,
} from "../application/ai-result-delivery-store.js";
import type {
  AiResultDeliveryReader,
  ExistingAiDelivery,
  ExistingAiDeliveryQuery,
} from "../application/read-ai-result-delivery.js";

const algorithm = "AES-256-GCM/HKDF-SHA256/v1";
const purpose = "reallanguage:ai:result-delivery:v1";
const ttlMilliseconds = 24 * 60 * 60 * 1000;
type Row = {
  request_digest: string;
  provider_attempt_id: string;
  status: "PROCESSING" | "COMPLETED" | "FAILED" | "EXPIRED";
  result_ciphertext: Uint8Array | null;
  algorithm: string | null;
  key_version: number | null;
};

/** Clears bodies but retains command tombstones: expiry must not permit a paid replay. */
export const purgeExpiredAiResults = async (
  database: Kysely<unknown>,
  now = new Date(),
) => {
  const result = await sql`update ai.result_deliveries
    set result_ciphertext=null, algorithm=null, key_version=null, status='EXPIRED'
    where expires_at <= ${now} and status in ('PROCESSING','COMPLETED')`.execute(
    database,
  );
  return Number(result.numAffectedRows ?? 0);
};

export class KyselyAiResultDeliveryStore
  implements AiResultDeliveryStore, AiResultDeliveryReader
{
  private readonly key: Buffer;
  public constructor(
    private readonly database: Kysely<unknown>,
    private readonly source: { key: Uint8Array; version: number },
    private readonly clock = { now: () => new Date() },
  ) {
    if (
      source.key.length !== 32 ||
      !Number.isSafeInteger(source.version) ||
      source.version < 1
    )
      throw new Error("Invalid AI delivery encryption configuration");
    // Purpose-separated subkey; never reuse the source key directly for this payload type.
    this.key = Buffer.from(
      hkdfSync(
        "sha256",
        source.key,
        Buffer.from(purpose),
        Buffer.from("encryption"),
        32,
      ),
    );
  }

  public async claim(identity: AiDeliveryIdentity) {
    const now = this.clock.now();
    await purgeExpiredAiResults(this.database, now);
    const digest = requestDigest(identity);
    const inserted = await sql`insert into ai.result_deliveries
      (user_id,task,command_id,provider_attempt_id,request_digest,status,created_at,expires_at)
      values (${identity.accountId},${identity.task},${identity.commandId},${identity.providerAttemptId},${digest},'PROCESSING',${now},${new Date(now.getTime() + ttlMilliseconds)})
      on conflict do nothing returning command_id`.execute(this.database);
    if (inserted.rows.length) return { status: "CLAIMED" as const };
    const existing = await sql<Row>`select * from ai.result_deliveries
      where user_id=${identity.accountId} and task=${identity.task} and command_id=${identity.commandId}`.execute(
      this.database,
    );
    const row = existing.rows[0];
    if (
      !row ||
      row.request_digest !== digest ||
      row.provider_attempt_id !== identity.providerAttemptId
    )
      return { status: "CONFLICT" as const };
    if (row.status !== "COMPLETED") return { status: row.status };
    if (
      !row.result_ciphertext ||
      row.algorithm !== algorithm ||
      row.key_version !== this.source.version
    )
      throw new Error(
        "AI result cannot be recovered with the configured key version",
      );
    const bytes = Buffer.from(row.result_ciphertext);
    const cipher = createDecipheriv(
      "aes-256-gcm",
      this.key,
      bytes.subarray(0, 12),
    );
    cipher.setAAD(associatedData(identity));
    cipher.setAuthTag(bytes.subarray(12, 28));
    const plaintext = Buffer.concat([
      cipher.update(bytes.subarray(28)),
      cipher.final(),
    ]);
    return {
      status: "COMPLETED" as const,
      result: JSON.parse(plaintext.toString("utf8")) as unknown,
    };
  }

  public async readExisting(
    query: ExistingAiDeliveryQuery,
  ): Promise<ExistingAiDelivery> {
    const rows = await sql<
      Row & { expires_at: Date }
    >`select request_digest,provider_attempt_id,status,
      result_ciphertext,algorithm,key_version,expires_at from ai.result_deliveries
      where user_id=${query.accountId} and task=${query.task} and command_id=${query.commandId}`.execute(
      this.database,
    );
    const row = rows.rows[0];
    if (!row) {
      if (query.historicalAttemptIds.length) {
        const historical = await sql`select 1 from ai.result_deliveries
          where user_id=${query.accountId} and task='LEGACY_UNAVAILABLE'
          and provider_attempt_id in (${sql.join(query.historicalAttemptIds)}) limit 1`.execute(
          this.database,
        );
        if (historical.rows.length) return { status: "LEGACY_UNAVAILABLE" };
      }
      return { status: "NO_DELIVERY" };
    }
    // This reader does not run the purge: expired data is denied before decrypting,
    // even during the interval before the independent cleanup runs.
    if (
      row.status === "EXPIRED" ||
      row.expires_at.getTime() <= this.clock.now().getTime()
    )
      return { status: "EXPIRED" };
    if (row.status !== "COMPLETED") return { status: row.status };
    try {
      if (
        !row.result_ciphertext ||
        row.result_ciphertext.length < 28 ||
        row.algorithm !== algorithm ||
        row.key_version !== this.source.version
      )
        return { status: "INVALID_DELIVERY" };
      const bytes = Buffer.from(row.result_ciphertext);
      const cipher = createDecipheriv(
        "aes-256-gcm",
        this.key,
        bytes.subarray(0, 12),
      );
      // Only the authenticated row supplies AAD metadata. No new input is guessed.
      cipher.setAAD(
        Buffer.from(
          JSON.stringify([
            purpose,
            query.accountId,
            query.task,
            query.commandId,
            row.provider_attempt_id,
            row.request_digest,
          ]),
        ),
      );
      cipher.setAuthTag(bytes.subarray(12, 28));
      const plaintext = Buffer.concat([
        cipher.update(bytes.subarray(28)),
        cipher.final(),
      ]);
      const result: unknown = JSON.parse(plaintext.toString("utf8"));
      if (row.expires_at.getTime() <= this.clock.now().getTime())
        return { status: "EXPIRED" };
      return {
        status: "COMPLETED",
        providerAttemptId: row.provider_attempt_id,
        result,
      };
    } catch {
      return { status: "INVALID_DELIVERY" };
    }
  }

  public async complete(identity: AiDeliveryIdentity, result: unknown) {
    const nonce = randomBytes(12);
    const cipher = createCipheriv("aes-256-gcm", this.key, nonce);
    cipher.setAAD(associatedData(identity));
    const ciphertext = Buffer.concat([
      cipher.update(JSON.stringify(result), "utf8"),
      cipher.final(),
    ]);
    const payload = Buffer.concat([nonce, cipher.getAuthTag(), ciphertext]);
    const saved = await sql`update ai.result_deliveries set status='COMPLETED',
      result_ciphertext=${payload},algorithm=${algorithm},key_version=${this.source.version}
      where user_id=${identity.accountId} and task=${identity.task} and command_id=${identity.commandId}
      and provider_attempt_id=${identity.providerAttemptId} and request_digest=${requestDigest(identity)}
      and status='PROCESSING' and expires_at > ${this.clock.now()} returning command_id`.execute(
      this.database,
    );
    return saved.rows.length === 1;
  }

  public async fail(identity: AiDeliveryIdentity) {
    await sql`update ai.result_deliveries set status='FAILED'
      where user_id=${identity.accountId} and task=${identity.task} and command_id=${identity.commandId}
      and provider_attempt_id=${identity.providerAttemptId} and request_digest=${requestDigest(identity)}
      and status='PROCESSING'`.execute(this.database);
  }
}

const associatedData = (identity: AiDeliveryIdentity) =>
  Buffer.from(
    JSON.stringify([
      purpose,
      identity.accountId,
      identity.task,
      identity.commandId,
      identity.providerAttemptId,
      requestDigest(identity),
    ]),
  );
const requestDigest = (identity: AiDeliveryIdentity) =>
  createHash("sha256").update(canonical(identity.request)).digest("hex");
const canonical = (value: unknown): string => {
  if (value === null || typeof value !== "object") {
    const json = JSON.stringify(value);
    if (json === undefined) throw new Error("AI request must be JSON");
    return json;
  }
  if (Array.isArray(value)) return `[${value.map(canonical).join(",")}]`;
  return `{${Object.keys(value)
    .sort()
    .map(
      (key) =>
        `${JSON.stringify(key)}:${canonical((value as Record<string, unknown>)[key])}`,
    )
    .join(",")}}`;
};
