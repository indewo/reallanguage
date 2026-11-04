import type { ColumnType, Generated, Kysely } from "kysely";

import type {
  AdminIdentity,
  AdminIdentityStore,
} from "../application/create-admin-session.js";
import type {
  InitialAdminConfigurationStatus,
  InitialAdminProvisioningInput,
  InitialAdminStore,
} from "../application/setup-initial-admin.js";
import type {
  AdminCryptography,
  ProtectedAdminValue,
} from "./admin-cryptography.js";

type Timestamp = ColumnType<Date, Date | undefined, Date>;

interface AdminAccountsTable {
  admin_id: string;
  email_ciphertext: Uint8Array;
  email_algorithm: string;
  email_key_version: number;
  email_index: Uint8Array;
  email_index_key_version: number;
  password_hash: string;
  totp_secret_ciphertext: Uint8Array;
  totp_algorithm: string;
  totp_key_version: number;
  last_accepted_totp_counter: ColumnType<
    number | null,
    number | null,
    number | null
  >;
  mfa_enrollment_confirmed_at: ColumnType<
    Date | null,
    Date | null | undefined,
    Date | null
  >;
  enabled: Generated<boolean>;
  row_version: Generated<number>;
  created_at: Timestamp;
  updated_at: Timestamp;
}

interface AdminSessionsTable {
  session_id: string;
  admin_id: string;
  bearer_digest: Uint8Array;
  bearer_digest_key_version: number;
  expires_at: ColumnType<Date, Date, Date>;
  revoked_at: ColumnType<Date | null, Date | null | undefined, Date | null>;
  created_at: Timestamp;
}

interface AuditEventsTable {
  audit_event_id: string;
  user_id: string | null;
  actor_type: "USER" | "ADMIN" | "SYSTEM";
  actor_id: string;
  action_type: string;
  resource_type: string;
  resource_id: string;
  result: string;
  reason_code: string | null;
  request_id: string | null;
  created_at: Timestamp;
}

export interface AdminIdentityDatabase {
  "admin.accounts": AdminAccountsTable;
  "admin.sessions": AdminSessionsTable;
  "platform.audit_events": AuditEventsTable;
}

type SessionInput = Parameters<AdminIdentityStore["issueSession"]>[0];

export class KyselyAdminIdentityStore
  implements AdminIdentityStore, InitialAdminStore
{
  public constructor(
    private readonly database: Kysely<AdminIdentityDatabase>,
    private readonly dependencies: Readonly<{
      cryptography: AdminCryptography;
      identifiers: { next(): string };
    }>,
  ) {}

  public async configurationStatus(): Promise<InitialAdminConfigurationStatus> {
    const rows = await this.database
      .selectFrom("admin.accounts")
      .select(["admin_id", "mfa_enrollment_confirmed_at"])
      .limit(2)
      .execute();
    if (rows.length === 0) return "UNCONFIGURED";
    if (rows.length > 1) throw new Error("Multiple administrators configured");
    return rows[0]?.mfa_enrollment_confirmed_at === null
      ? "ENROLLMENT_PENDING"
      : "CONFIGURED";
  }

  public async loadPendingTotpSecret(
    canonicalEmail: string,
  ): Promise<string | null> {
    const normalizedEmail = canonicalEmail.trim().toLowerCase();
    if (!normalizedEmail) return null;
    const emailIndex =
      this.dependencies.cryptography.indexEmail(normalizedEmail);
    const row = await this.findByEmailIndex(
      emailIndex.digest,
      emailIndex.keyVersion,
    );
    if (!row || row.mfa_enrollment_confirmed_at !== null) return null;
    const decryptedEmail = await this.dependencies.cryptography.unprotectEmail(
      protectedValue(
        row.email_ciphertext,
        row.email_algorithm,
        row.email_key_version,
      ),
    );
    if (decryptedEmail !== normalizedEmail) return null;
    return this.dependencies.cryptography.unprotectTotpSecret(
      protectedValue(
        row.totp_secret_ciphertext,
        row.totp_algorithm,
        row.totp_key_version,
      ),
    );
  }

  public async provision(
    input: InitialAdminProvisioningInput,
  ): Promise<"CREATED" | "ALREADY_CONFIGURED"> {
    const canonicalEmail = input.canonicalEmail.trim().toLowerCase();
    const email = this.dependencies.cryptography.protectEmail(canonicalEmail);
    const emailIndex =
      this.dependencies.cryptography.indexEmail(canonicalEmail);
    const totp = this.dependencies.cryptography.protectTotpSecret(
      input.totpSecret,
    );
    const adminId = this.dependencies.identifiers.next();
    const inserted = await this.database
      .insertInto("admin.accounts")
      .values({
        admin_id: adminId,
        email_ciphertext: email.ciphertext,
        email_algorithm: email.algorithm,
        email_key_version: email.keyVersion,
        email_index: emailIndex.digest,
        email_index_key_version: emailIndex.keyVersion,
        password_hash: input.passwordHash,
        totp_secret_ciphertext: totp.ciphertext,
        totp_algorithm: totp.algorithm,
        totp_key_version: totp.keyVersion,
        last_accepted_totp_counter: null,
        mfa_enrollment_confirmed_at: null,
        created_at: input.now,
        updated_at: input.now,
      })
      .onConflict((conflict) => conflict.column("email_index").doNothing())
      .returning("admin_id")
      .executeTakeFirst();
    if (inserted) return "CREATED";

    const existing = await this.findByEmailIndex(
      emailIndex.digest,
      emailIndex.keyVersion,
    );
    if (!existing) throw new Error("Administrator provisioning conflict");
    const existingEmail = await this.dependencies.cryptography.unprotectEmail(
      protectedValue(
        existing.email_ciphertext,
        existing.email_algorithm,
        existing.email_key_version,
      ),
    );
    if (existingEmail !== canonicalEmail) {
      throw new Error("Administrator email index collision");
    }
    return "ALREADY_CONFIGURED";
  }

  public async loadByCanonicalEmail(
    email: string,
  ): Promise<AdminIdentity | null> {
    const canonicalEmail = email.trim().toLowerCase();
    if (!canonicalEmail) return null;
    const emailIndex =
      this.dependencies.cryptography.indexEmail(canonicalEmail);
    const row = await this.findByEmailIndex(
      emailIndex.digest,
      emailIndex.keyVersion,
    );
    if (!row) return null;
    const decryptedEmail = await this.dependencies.cryptography.unprotectEmail(
      protectedValue(
        row.email_ciphertext,
        row.email_algorithm,
        row.email_key_version,
      ),
    );
    if (decryptedEmail !== canonicalEmail) return null;
    return {
      adminId: row.admin_id,
      canonicalEmail: decryptedEmail,
      passwordHash: row.password_hash,
      totpSecret: await this.dependencies.cryptography.unprotectTotpSecret(
        protectedValue(
          row.totp_secret_ciphertext,
          row.totp_algorithm,
          row.totp_key_version,
        ),
      ),
      lastAcceptedTotpCounter:
        row.last_accepted_totp_counter === null
          ? null
          : Number(row.last_accepted_totp_counter),
      enabled: row.enabled,
    };
  }

  public async issueSession(input: SessionInput): Promise<boolean> {
    return this.database.transaction().execute(async (transaction) => {
      let update = transaction
        .updateTable("admin.accounts")
        .set((expression) => ({
          last_accepted_totp_counter: input.acceptedTotpCounter,
          mfa_enrollment_confirmed_at: input.now,
          row_version: expression("row_version", "+", 1),
          updated_at: input.now,
        }))
        .where("admin_id", "=", input.adminId)
        .where("enabled", "=", true);
      update =
        input.previousTotpCounter === null
          ? update.where("last_accepted_totp_counter", "is", null)
          : update
              .where(
                "last_accepted_totp_counter",
                "=",
                input.previousTotpCounter,
              )
              .where(
                "last_accepted_totp_counter",
                "<",
                input.acceptedTotpCounter,
              );
      const consumed = await update.executeTakeFirst();
      if (Number(consumed.numUpdatedRows) !== 1) return false;

      const sessionId = this.dependencies.identifiers.next();
      await transaction
        .insertInto("admin.sessions")
        .values({
          session_id: sessionId,
          admin_id: input.adminId,
          bearer_digest: input.sessionDigest,
          bearer_digest_key_version: input.sessionDigestKeyVersion,
          expires_at: input.expiresAt,
          revoked_at: null,
          created_at: input.now,
        })
        .execute();
      await transaction
        .insertInto("platform.audit_events")
        .values({
          audit_event_id: this.dependencies.identifiers.next(),
          user_id: null,
          actor_type: "ADMIN",
          actor_id: input.adminId,
          action_type: "ADMIN_SESSION_CREATED",
          resource_type: "ADMIN_SESSION",
          resource_id: sessionId,
          result: "SUCCESS",
          reason_code: null,
          request_id: null,
          created_at: input.now,
        })
        .execute();
      return true;
    });
  }

  public async authenticateDigest(
    digest: Uint8Array,
    keyVersion: number,
    now: Date,
  ): Promise<string | null> {
    const session = await this.database
      .selectFrom("admin.sessions as sessions")
      .innerJoin(
        "admin.accounts as accounts",
        "accounts.admin_id",
        "sessions.admin_id",
      )
      .select("sessions.admin_id")
      .where("sessions.bearer_digest", "=", digest)
      .where("sessions.bearer_digest_key_version", "=", keyVersion)
      .where("sessions.revoked_at", "is", null)
      .where("sessions.expires_at", ">", now)
      .where("accounts.enabled", "=", true)
      .executeTakeFirst();
    return session?.admin_id ?? null;
  }

  public async revokeDigest(
    adminId: string,
    digest: Uint8Array,
    keyVersion: number,
    now: Date,
  ): Promise<boolean> {
    const revoked = await this.database
      .updateTable("admin.sessions")
      .set({ revoked_at: now })
      .where("admin_id", "=", adminId)
      .where("bearer_digest", "=", digest)
      .where("bearer_digest_key_version", "=", keyVersion)
      .where("revoked_at", "is", null)
      .executeTakeFirst();
    return Number(revoked.numUpdatedRows) === 1;
  }

  private findByEmailIndex(digest: Uint8Array, keyVersion: number) {
    return this.database
      .selectFrom("admin.accounts")
      .selectAll()
      .where("email_index", "=", digest)
      .where("email_index_key_version", "=", keyVersion)
      .executeTakeFirst();
  }
}

const protectedValue = (
  ciphertext: Uint8Array,
  algorithm: string,
  keyVersion: number,
): ProtectedAdminValue => {
  if (algorithm !== "AES-256-GCM/v1") {
    throw new Error("Unsupported admin encryption algorithm");
  }
  return { ciphertext, algorithm, keyVersion };
};
