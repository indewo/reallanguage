import { PGlite } from "@electric-sql/pglite";
import { migrateToLatest } from "@reallanguage/database";
import { Kysely, PGliteDialect, sql } from "kysely";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { purgeExpiredSafetyPayloads } from "./kysely-safety-payload-retention.js";
import {
  KyselySafetyHandlingStore,
  type SafetyHandlingDatabase,
} from "./kysely-safety-handling-store.js";
import { AesGcmContentSafetyPayloadProtector } from "./aes-gcm-content-safety-payload-protector.js";

let database: Kysely<unknown>;
const now = new Date("2026-09-08T00:00:00Z");
const past = new Date(now.getTime() - 1);
const future = new Date(now.getTime() + 60_000);
const account = "30000000-0000-4000-8000-000000000001";
const otherAccount = "30000000-0000-4000-8000-000000000002";
const cipher = new AesGcmContentSafetyPayloadProtector({
  key: new Uint8Array(32).fill(7),
  version: 1,
});
let sequence: number;
const id = () =>
  `10000000-0000-4000-8000-${String(++sequence).padStart(12, "0")}`;

beforeEach(async () => {
  sequence = 0;
  database = new Kysely({
    dialect: new PGliteDialect({ pglite: new PGlite() }),
  });
  await migrateToLatest(database);
  for (const owner of [account, otherAccount]) {
    await sql`insert into identity.accounts (account_id,birth_year_month_ciphertext,birth_year_month_algorithm,birth_year_month_key_version,age_eligible,age_eligibility_confirmed_at,email_verified_at)
      values (${owner},decode('01','hex'),'AES-256-GCM/v1',1,true,${now},${now})`.execute(
      database,
    );
  }
});
afterEach(async () => database.destroy());

describe("safety temporary payload retention", () => {
  it("clears only expired terminal ciphertext and risk text while keeping replay and audit metadata", async () => {
    const blocked = await seed({ expiry: now });
    const failed = await seed({
      expiry: past,
      status: "FAILED",
      withCase: false,
      owner: otherAccount,
    });
    const before = await operation(blocked.operationId);
    expect(await purgeExpiredSafetyPayloads(database, now)).toEqual({
      operations: 2,
      candidates: 0,
      cases: 1,
    });
    const after = await operation(blocked.operationId);
    expect(after).toEqual({
      ...before,
      payload_ciphertext: null,
      payload_algorithm: null,
      payload_key_version: null,
    });
    expect((await operation(failed.operationId)).payload_ciphertext).toBeNull();
    const cases = await sql<{
      reason_code: string;
      user_reason_zh: string;
      risk_spans: unknown;
    }>`select reason_code,user_reason_zh,risk_spans from safety.safety_cases where safety_case_id=${blocked.caseId}`.execute(
      database,
    );
    expect(cases.rows[0]).toEqual({
      reason_code: "RISK",
      user_reason_zh: "具体原因",
      risk_spans: [],
    });
    expect(await purgeExpiredSafetyPayloads(database, now)).toEqual({
      operations: 0,
      candidates: 0,
      cases: 0,
    });
  });

  it("removes the entire expired candidate including edits and summary, but not an unexpired candidate", async () => {
    const expired = await seed({ expiry: future, candidateExpiry: now });
    const live = await seed({ expiry: future, candidateExpiry: future });
    expect(await purgeExpiredSafetyPayloads(database, now)).toEqual({
      operations: 0,
      candidates: 1,
      cases: 0,
    });
    const remaining = await sql<{
      safety_case_id: string;
    }>`select safety_case_id from safety.deidentified_versions`.execute(
      database,
    );
    expect(remaining.rows).toEqual([{ safety_case_id: live.caseId }]);
    expect(
      (await operation(expired.operationId)).payload_ciphertext,
    ).not.toBeNull();
  });

  it("retains all manually submitted payloads and candidates regardless of decision until the appeal policy is confirmed", async () => {
    for (const reviewStatus of [
      "PENDING",
      "APPROVED",
      "NOT_APPROVED",
    ] as const) {
      await seed({ expiry: past, candidateExpiry: past, reviewStatus });
    }
    await seed({
      expiry: past,
      candidateExpiry: past,
      resolution: "REVIEW_PENDING",
    });
    expect(await purgeExpiredSafetyPayloads(database, now)).toEqual({
      operations: 0,
      candidates: 0,
      cases: 0,
    });
    expect(
      (
        await sql`select 1 from safety.check_operations where payload_ciphertext is not null`.execute(
          database,
        )
      ).rows,
    ).toHaveLength(4);
    expect(
      (await sql`select 1 from safety.deidentified_versions`.execute(database))
        .rows,
    ).toHaveLength(4);
    expect(
      (
        await sql`select 1 from safety.safety_cases where risk_spans <> '[]'::jsonb`.execute(
          database,
        )
      ).rows,
    ).toHaveLength(4);
  });

  it("does not clear live or in-flight operations", async () => {
    await seed({ expiry: future });
    await seed({ expiry: past, status: "PROCESSING", withCase: false });
    expect(await purgeExpiredSafetyPayloads(database, now)).toEqual({
      operations: 0,
      candidates: 0,
      cases: 0,
    });
    expect(
      (
        await sql`select 1 from safety.check_operations where payload_ciphertext is not null`.execute(
          database,
        )
      ).rows,
    ).toHaveLength(2);
  });

  it("refuses a manual review with already expired or cleared content instead of creating an unreviewable request", async () => {
    const expired = await seed({ expiry: past });
    const cleared = await seed({ expiry: future });
    await sql`update safety.check_operations set payload_ciphertext=null,payload_algorithm=null,payload_key_version=null where operation_id=${cleared.operationId}`.execute(
      database,
    );
    const store = createStore();
    expect(
      await store.submitManualReview(
        account,
        expired.caseId!,
        "expired-review",
      ),
    ).toBeNull();
    expect(
      await store.submitManualReview(
        account,
        cleared.caseId!,
        "cleared-review",
      ),
    ).toBeNull();
    expect(
      (await sql`select 1 from safety.manual_review_requests`.execute(database))
        .rows,
    ).toHaveLength(0);
    expect(
      (
        await sql`select 1 from safety.safety_cases where resolution_status='BLOCKED'`.execute(
          database,
        )
      ).rows,
    ).toHaveLength(2);
  });

  it("protects a submitted case at expiry and preserves its stable replay", async () => {
    const held = await seed({ expiry: future, candidateExpiry: future });
    const store = createStore();
    const review = await store.submitManualReview(
      account,
      held.caseId!,
      "user-review",
    );
    expect(review).toBeTruthy();
    expect(await purgeExpiredSafetyPayloads(database, future)).toEqual({
      operations: 0,
      candidates: 0,
      cases: 0,
    });
    expect(
      await store.submitManualReview(account, held.caseId!, "user-review"),
    ).toBe(review);
    expect(
      (await operation(held.operationId)).payload_ciphertext,
    ).not.toBeNull();
  });

  it("does not accept an expired candidate or change a case already held for manual review", async () => {
    const expired = await seed({ expiry: future, candidateExpiry: past });
    const held = await seed({
      expiry: future,
      candidateExpiry: future,
      resolution: "REVIEW_PENDING",
      reviewStatus: "PENDING",
    });
    const store = createStore();
    expect(
      await store.acceptCandidate(account, expired.caseId!, "expired-accept"),
    ).toBe(false);
    expect(
      await store.acceptCandidate(account, held.caseId!, "manual-accept"),
    ).toBe(false);
    expect(await store.returnToEdit(account, held.caseId!, "manual-edit")).toBe(
      false,
    );
    expect(
      (await operation(expired.operationId)).payload_ciphertext,
    ).not.toBeNull();
    expect(
      (await operation(held.operationId)).payload_ciphertext,
    ).not.toBeNull();
    expect(
      (await sql`select 1 from safety.deidentified_versions`.execute(database))
        .rows,
    ).toHaveLength(2);
  });

  it("rolls back the manual hold if creating its review request fails", async () => {
    const source = await seed({ expiry: future });
    await sql`create function safety.reject_test_review() returns trigger language plpgsql as $$ begin raise exception 'isolated insertion failure'; end $$`.execute(
      database,
    );
    await sql`create trigger reject_test_review before insert on safety.manual_review_requests for each row execute function safety.reject_test_review()`.execute(
      database,
    );
    await expect(
      createStore().submitManualReview(
        account,
        source.caseId!,
        "failed-review",
      ),
    ).rejects.toThrow();
    const cases = await sql<{
      resolution_status: string;
    }>`select resolution_status from safety.safety_cases where safety_case_id=${source.caseId}`.execute(
      database,
    );
    expect(cases.rows[0]?.resolution_status).toBe("BLOCKED");
    expect(
      (await operation(source.operationId)).payload_ciphertext,
    ).not.toBeNull();
  });

  it.each([false, true])(
    "never leaves a submitted review without its body when cleanup starts first=%s",
    async (cleanupFirst) => {
      const source = await seed({ expiry: future, candidateExpiry: future });
      const submit = () =>
        createStore().submitManualReview(
          account,
          source.caseId!,
          "concurrent-review",
        );
      const purge = () => purgeExpiredSafetyPayloads(database, future);
      await Promise.all(
        cleanupFirst ? [purge(), submit()] : [submit(), purge()],
      );
      const reviews =
        await sql`select 1 from safety.manual_review_requests where safety_case_id=${source.caseId}`.execute(
          database,
        );
      const saved = await operation(source.operationId);
      if (reviews.rows.length) expect(saved.payload_ciphertext).not.toBeNull();
      else expect(saved.payload_ciphertext).toBeNull();
    },
  );

  it("does not resurrect a candidate from a provider response arriving after source expiry or acceptance", async () => {
    const expired = await seed({ expiry: past });
    const accepted = await seed({ expiry: future });
    await sql`update safety.safety_cases set resolution_status='DEIDENTIFIED_ACCEPTED' where safety_case_id=${accepted.caseId}`.execute(
      database,
    );
    await purgeExpiredSafetyPayloads(database, now);
    const candidate = {
      sanitizedText: "隔离候选",
      edits: [],
      preservedIntentSummaryZh: "隔离意图",
      internalSafetyValid: true as const,
      providerOperationId: "isolated-provider",
    };
    const store = createStore();
    await expect(
      store.saveCandidate(account, expired.caseId!, candidate),
    ).rejects.toThrow();
    await expect(
      store.saveCandidate(account, accepted.caseId!, candidate),
    ).rejects.toThrow();
    expect(
      (await sql`select 1 from safety.deidentified_versions`.execute(database))
        .rows,
    ).toHaveLength(0);
  });
});

async function seed(options: {
  expiry: Date;
  status?: "BLOCKED" | "FAILED" | "PROCESSING";
  withCase?: boolean;
  candidateExpiry?: Date;
  owner?: string;
  reviewStatus?: "PENDING" | "APPROVED" | "NOT_APPROVED";
  resolution?: "BLOCKED" | "REVIEW_PENDING";
}) {
  const operationId = id();
  const caseId = options.withCase === false ? null : id();
  const owner = options.owner ?? account;
  const payload = await cipher.protect("隔离测试正文");
  await sql`insert into safety.check_operations (operation_id,user_id,command_id,request_digest,source_type,content_shape,payload_ciphertext,payload_algorithm,payload_key_version,status,policy_version,provider_operation_id,safety_case_id,expires_at,created_at,updated_at)
    values (${operationId},${owner},${operationId},decode('01','hex'),'DIARY','ARTICLE',${payload.ciphertext},${payload.algorithm},${payload.keyVersion},${options.status ?? "BLOCKED"},'policy-v1','provider-reference',${caseId},${options.expiry},${new Date(now.getTime() - 120_000)},${now})`.execute(
    database,
  );
  if (caseId) {
    await sql`insert into safety.safety_cases (safety_case_id,operation_id,user_id,policy_version,reason_code,user_reason_zh,risk_spans,confidence,context_considered,resolution_status)
      values (${caseId},${operationId},${owner},'policy-v1','RISK','具体原因','[{"startCodePoint":0,"endCodePoint":2,"text":"隔离","reasonCode":"RISK"}]'::jsonb,0.9,true,${options.resolution ?? "BLOCKED"})`.execute(
      database,
    );
    if (options.candidateExpiry) {
      await sql`insert into safety.deidentified_versions (deidentified_version_id,safety_case_id,user_id,payload_ciphertext,payload_algorithm,payload_key_version,edits,preserved_intent_summary_zh,provider_operation_id,internal_safety_valid,expires_at)
        values (${id()},${caseId},${owner},${payload.ciphertext},${payload.algorithm},${payload.keyVersion},'[{"originalText":"隔离测试正文"}]'::jsonb,'含隔离正文的意图','provider-reference',true,${options.candidateExpiry})`.execute(
        database,
      );
    }
    if (options.reviewStatus) {
      await sql`insert into safety.manual_review_requests (review_request_id,safety_case_id,user_id,command_id,status)
        values (${id()},${caseId},${owner},${caseId},${options.reviewStatus})`.execute(
        database,
      );
    }
  }
  return { operationId, caseId };
}

async function operation(operationId: string) {
  return (
    await sql<
      Record<string, unknown>
    >`select * from safety.check_operations where operation_id=${operationId}`.execute(
      database,
    )
  ).rows[0]!;
}
function createStore() {
  return new KyselySafetyHandlingStore(
    database as Kysely<SafetyHandlingDatabase>,
    {
      cipher,
      identifiers: { next: id },
      clock: { now: () => now },
      candidateTtlMilliseconds: 60_000,
    },
  );
}
