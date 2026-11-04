import { PGlite } from "@electric-sql/pglite";
import { migrateToLatest } from "@reallanguage/database";
import { Kysely, PGliteDialect, sql } from "kysely";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { HandleBlockedSafety } from "../application/handle-blocked-safety.js";
import {
  KyselyAdminReviewStore,
  type AdminReviewDatabase,
} from "../../admin/infrastructure/kysely-admin-review-store.js";
import { AesGcmContentSafetyPayloadProtector } from "./aes-gcm-content-safety-payload-protector.js";
import {
  KyselySafetyHandlingStore,
  type SafetyHandlingDatabase,
} from "./kysely-safety-handling-store.js";

const accountId = "30000000-0000-4000-8000-000000000001";
const caseId = "20000000-0000-4000-8000-000000000001";
const operationId = "10000000-0000-4000-8000-000000000001";
const now = new Date("2026-09-02T00:00:00Z");
const cipher = new AesGcmContentSafetyPayloadProtector({
  key: Uint8Array.from({ length: 32 }, () => 9),
  version: 1,
});
let database: Kysely<SafetyHandlingDatabase & AdminReviewDatabase>;

beforeEach(async () => {
  database = new Kysely({
    dialect: new PGliteDialect({ pglite: new PGlite() }),
  });
  await migrateToLatest(database);
  const payload = await cipher.protect("联系张三");
  await sql`insert into identity.accounts (account_id, birth_year_month_ciphertext, birth_year_month_algorithm, birth_year_month_key_version, age_eligible, age_eligibility_confirmed_at, email_verified_at, created_at, updated_at) values (${accountId}, decode('01','hex'), 'AES-256-GCM/v1', 1, true, ${now}, ${now}, ${now}, ${now})`.execute(
    database,
  );
  await sql`insert into safety.check_operations (operation_id, user_id, command_id, request_digest, source_type, content_shape, payload_ciphertext, payload_algorithm, payload_key_version, status, safety_case_id, expires_at, created_at, updated_at) values (${operationId}, ${accountId}, 'check', decode('01','hex'), 'DIARY', 'ARTICLE', ${payload.ciphertext}, ${payload.algorithm}, ${payload.keyVersion}, 'BLOCKED', ${caseId}, ${new Date(now.getTime() + 60_000)}, ${now}, ${now})`.execute(
    database,
  );
  await sql`insert into safety.safety_cases (safety_case_id, operation_id, user_id, policy_version, reason_code, user_reason_zh, risk_spans, confidence, context_considered, created_at, updated_at) values (${caseId}, ${operationId}, ${accountId}, 'p1', 'RISK', '具体原因', ${JSON.stringify([{ startCodePoint: 2, endCodePoint: 4, text: "张三", reasonCode: "RISK" }])}::jsonb, 0.9, true, ${now}, ${now})`.execute(
    database,
  );
});
afterEach(async () => {
  vi.restoreAllMocks();
  await database.destroy();
});

describe("KyselySafetyHandlingStore", () => {
  it.each(["APPROVED", "NOT_APPROVED"] as const)(
    "reads the owned %s status after lost-response replay without touching original content or creating another request",
    async (status) => {
      const store = createStore();
      const reviewId = (await store.submitManualReview(
        accountId,
        caseId,
        "lost-response",
      ))!;
      expect(
        await store.loadManualReviewStatus(accountId, caseId, reviewId),
      ).toBe("PENDING");
      await sql`update safety.manual_review_requests set status=${status}, row_version=row_version+1 where review_request_id=${reviewId}`.execute(
        database,
      );
      await sql`update safety.check_operations set payload_ciphertext=null,payload_algorithm=null,payload_key_version=null where operation_id=${operationId}`.execute(
        database,
      );
      const before = await reviewHistory();
      const decrypt = vi.spyOn(cipher, "unprotect");
      const repeated = await store.submitManualReview(
        accountId,
        caseId,
        "lost-response",
      );
      expect(repeated).toBe(reviewId);
      expect(
        await createStore().loadManualReviewStatus(
          accountId,
          caseId,
          repeated!,
        ),
      ).toBe(status);
      expect(
        await store.loadManualReviewStatus(
          "30000000-0000-4000-8000-000000000099",
          caseId,
          reviewId,
        ),
      ).toBeNull();
      expect(
        await store.loadManualReviewStatus(
          accountId,
          "20000000-0000-4000-8000-000000000099",
          reviewId,
        ),
      ).toBeNull();
      expect(
        await store.loadManualReviewStatus(
          accountId,
          caseId,
          "50000000-0000-4000-8000-000000000099",
        ),
      ).toBeNull();
      expect(await reviewHistory()).toEqual(before);
      expect(decrypt).not.toHaveBeenCalled();
    },
  );
  it.each([0, -1])(
    "does not decrypt or send an ordinary expired source to AI before cleanup (offset %s)",
    async (offset) => {
      await sql`update safety.check_operations set expires_at=${new Date(now.getTime() + offset)},created_at=${new Date(now.getTime() - 60_000)} where operation_id=${operationId}`.execute(
        database,
      );
      const decrypt = vi.spyOn(cipher, "unprotect");
      const provider = {
        deidentify: vi
          .fn()
          .mockRejectedValue(new Error("Provider must not run")),
      };
      const store = createStore();
      await expect(store.loadBlocked(accountId, caseId)).resolves.toBeNull();
      await expect(
        new HandleBlockedSafety(store, provider).prepare(accountId, caseId),
      ).resolves.toBeNull();
      expect(decrypt).not.toHaveBeenCalled();
      expect(provider.deidentify).not.toHaveBeenCalled();
      const retained = await sql<{
        present: boolean;
      }>`select payload_ciphertext is not null as present from safety.check_operations where operation_id=${operationId}`.execute(
        database,
      );
      expect(retained.rows[0]?.present).toBe(true);
    },
  );

  it("does not decrypt an expired candidate even while a manually held source remains available", async () => {
    const store = createStore();
    await store.saveCandidate(accountId, caseId, candidate);
    const reviewId = await store.submitManualReview(
      accountId,
      caseId,
      "read-expired-held",
    );
    await sql`update safety.manual_review_requests set status='NOT_APPROVED' where review_request_id=${reviewId}`.execute(
      database,
    );
    await sql`update safety.deidentified_versions set expires_at=${now} where safety_case_id=${caseId}`.execute(
      database,
    );
    const decrypt = vi.spyOn(cipher, "unprotect");
    await expect(store.loadCandidate(accountId, caseId)).resolves.toBeNull();
    expect(decrypt).not.toHaveBeenCalled();
    expect(
      await store.hasExpiredCandidate(
        "40000000-0000-4000-8000-000000000001",
        caseId,
      ),
    ).toBe(false);
    const provider = {
      deidentify: vi
        .fn()
        .mockRejectedValue(
          new Error("Expired held candidate must not cause another AI call"),
        ),
    };
    await expect(
      new HandleBlockedSafety(store, provider).prepare(accountId, caseId),
    ).resolves.toBeNull();
    expect(provider.deidentify).not.toHaveBeenCalled();
    expect(
      (
        await sql`select 1 from safety.deidentified_versions where safety_case_id=${caseId}`.execute(
          database,
        )
      ).rows,
    ).toHaveLength(1);
  });

  it("allows only the owned NOT_APPROVED review source after its ordinary expiry", async () => {
    const store = createStore();
    const reviewId = await store.submitManualReview(
      accountId,
      caseId,
      "review-owned-expiry",
    );
    await sql`update safety.manual_review_requests set status='NOT_APPROVED' where review_request_id=${reviewId}`.execute(
      database,
    );
    await sql`update safety.check_operations set expires_at=${now},created_at=${new Date(now.getTime() - 60_000)} where operation_id=${operationId}`.execute(
      database,
    );
    const decrypt = vi.spyOn(cipher, "unprotect");
    await expect(store.loadBlocked(accountId, caseId)).resolves.toMatchObject({
      text: "联系张三",
    });
    await expect(
      store.loadBlocked("40000000-0000-4000-8000-000000000001", caseId),
    ).resolves.toBeNull();
    expect(decrypt).toHaveBeenCalledTimes(1);
  });

  it("reuses an unexpired NOT_APPROVED candidate without AI even when its held source passed ordinary expiry", async () => {
    const store = createStore();
    await store.saveCandidate(accountId, caseId, candidate);
    const reviewId = await store.submitManualReview(
      accountId,
      caseId,
      "held-live-candidate",
    );
    await sql`update safety.manual_review_requests set status='NOT_APPROVED' where review_request_id=${reviewId}`.execute(
      database,
    );
    await sql`update safety.check_operations set expires_at=${now},created_at=${new Date(now.getTime() - 60_000)} where operation_id=${operationId}`.execute(
      database,
    );
    const provider = { deidentify: vi.fn() };
    await expect(
      new HandleBlockedSafety(store, provider).prepare(accountId, caseId),
    ).resolves.toMatchObject({
      source: { text: "联系张三" },
      candidate: { sanitizedText: "联系某人" },
    });
    expect(provider.deidentify).not.toHaveBeenCalled();
  });

  it("distinguishes never-generated from expired for an owned NOT_APPROVED source", async () => {
    const store = createStore();
    const reviewId = await store.submitManualReview(
      accountId,
      caseId,
      "held-new-candidate",
    );
    await sql`update safety.manual_review_requests set status='NOT_APPROVED' where review_request_id=${reviewId}`.execute(
      database,
    );
    await sql`update safety.check_operations set expires_at=${now},created_at=${new Date(now.getTime() - 60_000)} where operation_id=${operationId}`.execute(
      database,
    );
    const provider = { deidentify: vi.fn().mockResolvedValue(candidate) };
    await expect(
      new HandleBlockedSafety(store, provider).prepare(accountId, caseId),
    ).resolves.toMatchObject({ candidate });
    await expect(store.loadCandidate(accountId, caseId)).resolves.toEqual(
      candidate,
    );
    expect(provider.deidentify).toHaveBeenCalledTimes(1);
  });

  it("keeps a successful acceptance command replayable after payload deletion and expiry", async () => {
    let currentTime = now;
    const store = new KyselySafetyHandlingStore(database, {
      cipher,
      identifiers: { next: () => crypto.randomUUID() },
      clock: { now: () => currentTime },
      candidateTtlMilliseconds: 60_000,
    });
    await store.saveCandidate(accountId, caseId, candidate);
    expect(
      await store.acceptCandidate(accountId, caseId, "stable-accept"),
    ).toBe(true);
    currentTime = new Date(now.getTime() + 120_000);
    const decrypt = vi.spyOn(cipher, "unprotect");
    expect(
      await store.acceptCandidate(accountId, caseId, "stable-accept"),
    ).toBe(true);
    expect(
      await store.acceptCandidate(accountId, caseId, "different-accept"),
    ).toBe(false);
    expect(decrypt).not.toHaveBeenCalled();
  });

  it("decrypts only the owned blocked source and clears payload after acceptance", async () => {
    const store = createStore();
    await expect(store.loadBlocked(accountId, caseId)).resolves.toMatchObject({
      text: "联系张三",
      safetyCaseId: caseId,
    });
    await expect(
      store.loadBlocked("40000000-0000-4000-8000-000000000001", caseId),
    ).resolves.toBeNull();
    await store.saveCandidate(accountId, caseId, candidate);
    await expect(store.loadCandidate(accountId, caseId)).resolves.toMatchObject(
      { sanitizedText: "联系某人" },
    );
    await expect(
      store.acceptCandidate(accountId, caseId, "accept-1"),
    ).resolves.toBe(true);
    await expect(
      store.acceptCandidate(accountId, caseId, "accept-1"),
    ).resolves.toBe(true);
    expect(
      (
        await sql<{
          payload_ciphertext: Uint8Array | null;
        }>`select payload_ciphertext from safety.check_operations where operation_id=${operationId}`.execute(
          database,
        )
      ).rows[0]?.payload_ciphertext,
    ).toBeNull();
  });

  it("creates manual review only from an explicit stable command", async () => {
    const store = createStore();
    const first = await store.submitManualReview(accountId, caseId, "review-1");
    expect(first).toBeTruthy();
    await expect(
      store.submitManualReview(accountId, caseId, "review-1"),
    ).resolves.toBe(first);
    expect(
      (await sql`select * from safety.manual_review_requests`.execute(database))
        .rows,
    ).toHaveLength(1);
  });

  it("reads an adjudicated owned review result with its temporary source", async () => {
    const store = createStore();
    const reviewId = await store.submitManualReview(
      accountId,
      caseId,
      "review-result",
    );
    await sql`update safety.manual_review_requests set status='APPROVED' where review_request_id=${reviewId}`.execute(
      database,
    );
    await expect(
      store.loadManualReviewResult(accountId, reviewId!),
    ).resolves.toMatchObject({
      outcome: "APPROVED",
      source: { safetyCaseId: caseId, text: "联系张三" },
    });
    await expect(
      store.loadManualReviewResult(
        "40000000-0000-4000-8000-000000000001",
        reviewId!,
      ),
    ).resolves.toBeNull();
  });

  it("allows a not-approved result to return to safety handling", async () => {
    const store = createStore();
    const reviewId = await store.submitManualReview(
      accountId,
      caseId,
      "review-return",
    );
    await sql`update safety.manual_review_requests set status='NOT_APPROVED' where review_request_id=${reviewId}`.execute(
      database,
    );
    await expect(store.loadBlocked(accountId, caseId)).resolves.toMatchObject({
      safetyCaseId: caseId,
      text: "联系张三",
    });
  });

  it.each(["KEEP_BLOCKED", "REQUIRES_DEIDENTIFICATION"] as const)(
    "accepts an unexpired candidate after a real %s decision without altering the review history",
    async (decision) => {
      const store = createStore();
      await store.saveCandidate(accountId, caseId, candidate);
      const reviewId = await rejectReview(store, decision);
      // A manual hold legitimately outlives the ordinary source TTL. Only the
      // candidate's own unexpired, internally checked version can be accepted.
      await expireSource();
      const before = await reviewHistory();
      expect(
        await store.acceptCandidate(accountId, caseId, "accepted-after-review"),
      ).toBe(true);
      const reloaded = createStore();
      expect(
        await reloaded.acceptCandidate(
          accountId,
          caseId,
          "accepted-after-review",
        ),
      ).toBe(true);
      expect(
        await reloaded.acceptCandidate(accountId, caseId, "another-command"),
      ).toBe(false);
      expect(
        await reloaded.returnToEdit(accountId, caseId, "different-action"),
      ).toBe(false);
      expect(await reviewHistory()).toEqual(before);
      expect(await reloaded.loadBlocked(accountId, caseId)).toBeNull();
      expect(
        await reloaded.loadManualReviewResult(accountId, reviewId),
      ).toBeNull();
      expect(await resolution()).toEqual({
        status: "DEIDENTIFIED_ACCEPTED",
        hasPayload: false,
        candidates: 0,
      });
    },
  );

  it("returns the rejected source to editing once without approving or erasing its decision", async () => {
    const store = createStore();
    await store.saveCandidate(accountId, caseId, candidate);
    await rejectReview(store, "KEEP_BLOCKED");
    await expireSource();
    const before = await reviewHistory();
    expect(
      await store.returnToEdit(accountId, caseId, "edit-after-review"),
    ).toBe(true);
    expect(
      await createStore().returnToEdit(accountId, caseId, "edit-after-review"),
    ).toBe(true);
    expect(
      await createStore().returnToEdit(accountId, caseId, "another-edit"),
    ).toBe(false);
    expect(await reviewHistory()).toEqual(before);
    expect(await resolution()).toEqual({
      status: "RETURNED_TO_EDIT",
      hasPayload: false,
      candidates: 0,
    });
  });

  it("does not let a pending or approved review use either blocked-source resolution even with a legacy BLOCKED case", async () => {
    const store = createStore();
    await store.saveCandidate(accountId, caseId, candidate);
    const reviewId = await store.submitManualReview(
      accountId,
      caseId,
      "pending-review",
    );
    for (const status of ["PENDING", "APPROVED"] as const) {
      await sql`update safety.manual_review_requests set status=${status} where review_request_id=${reviewId}`.execute(
        database,
      );
      for (const caseStatus of ["REVIEW_PENDING", "BLOCKED"] as const) {
        await sql`update safety.safety_cases set resolution_status=${caseStatus} where safety_case_id=${caseId}`.execute(
          database,
        );
        expect(
          await store.acceptCandidate(accountId, caseId, "cannot-bypass"),
        ).toBe(false);
        expect(
          await store.returnToEdit(accountId, caseId, "cannot-bypass-edit"),
        ).toBe(false);
      }
    }
    expect((await resolution()).hasPayload).toBe(true);
  });

  it("rejects another owner, an expired candidate and a missing held payload without releasing the rejected case", async () => {
    const store = createStore();
    await store.saveCandidate(accountId, caseId, candidate);
    await rejectReview(store, "KEEP_BLOCKED");
    const other = "40000000-0000-4000-8000-000000000001";
    expect(await store.acceptCandidate(other, caseId, "cross-account")).toBe(
      false,
    );
    expect(await store.returnToEdit(other, caseId, "cross-account-edit")).toBe(
      false,
    );
    await sql`update safety.deidentified_versions set expires_at=${now} where safety_case_id=${caseId}`.execute(
      database,
    );
    expect(
      await store.acceptCandidate(accountId, caseId, "expired-candidate"),
    ).toBe(false);
    await sql`update safety.check_operations set payload_ciphertext=null,payload_algorithm=null,payload_key_version=null where operation_id=${operationId}`.execute(
      database,
    );
    expect(
      await store.acceptCandidate(accountId, caseId, "missing-source"),
    ).toBe(false);
    expect(
      await store.returnToEdit(accountId, caseId, "missing-source-edit"),
    ).toBe(false);
    expect((await resolution()).status).toBe("REVIEW_PENDING");
  });

  it("settles only one of concurrent accept and edit commands after rejection", async () => {
    const store = createStore();
    await store.saveCandidate(accountId, caseId, candidate);
    await rejectReview(store, "KEEP_BLOCKED");
    const results = await Promise.all([
      store.acceptCandidate(accountId, caseId, "race-accept"),
      createStore().returnToEdit(accountId, caseId, "race-edit"),
    ]);
    expect(results.filter(Boolean)).toHaveLength(1);
    expect((await resolution()).hasPayload).toBe(false);
  });

  it("reads the immutable reviewer reason for exactly the owned request, not the automatic case reason or another review", async () => {
    const store = createStore();
    const firstReview = await rejectReview(store, "KEEP_BLOCKED");
    const secondCase = "20000000-0000-4000-8000-000000000002";
    const secondOperation = "10000000-0000-4000-8000-000000000002";
    await sql`insert into safety.check_operations (operation_id,user_id,command_id,request_digest,source_type,content_shape,payload_ciphertext,payload_algorithm,payload_key_version,status,safety_case_id,expires_at,created_at,updated_at) select ${secondOperation},user_id,'check-2',request_digest,'AI_SCENE','DIALOGUE',payload_ciphertext,payload_algorithm,payload_key_version,status,${secondCase},expires_at,created_at,updated_at from safety.check_operations where operation_id=${operationId}`.execute(
      database,
    );
    await sql`insert into safety.safety_cases (safety_case_id,operation_id,user_id,policy_version,reason_code,user_reason_zh,risk_spans,confidence,context_considered,created_at,updated_at) select ${secondCase},${secondOperation},user_id,policy_version,'AUTO_OTHER','自动判定另一理由',risk_spans,confidence,context_considered,created_at,updated_at from safety.safety_cases where safety_case_id=${caseId}`.execute(
      database,
    );
    const secondReview = (await store.submitManualReview(
      accountId,
      secondCase,
      "explicit-other-review",
    ))!;
    const admin = new KyselyAdminReviewStore(database, {
      cipher,
      identifiers: { next: () => crypto.randomUUID() },
      clock: { now: () => now },
    });
    expect(
      await admin.decide({
        adminId: "40000000-0000-4000-8000-000000000002",
        reviewRequestId: secondReview,
        decision: "APPROVE",
        reasonCode: "CONTEXT_OK",
        reasonZh: "结合第二份来源上下文，允许继续。",
        requestId: "isolated-second-decision",
      }),
    ).toBe(true);
    expect(
      await createStore().loadManualReviewResult(accountId, firstReview),
    ).toMatchObject({
      outcome: "NOT_APPROVED",
      source: {
        safetyCaseId: caseId,
        reasonCode: "PROTECT_IDENTITY",
        userReasonZh: "请移除可以识别当事人的信息。",
      },
    });
    expect(
      await createStore().loadManualReviewResult(accountId, secondReview),
    ).toMatchObject({
      outcome: "APPROVED",
      source: {
        safetyCaseId: secondCase,
        sourceType: "AI_SCENE",
        reasonCode: "CONTEXT_OK",
        userReasonZh: "结合第二份来源上下文，允许继续。",
      },
    });
    expect(
      await store.loadManualReviewResult(
        "30000000-0000-4000-8000-000000000099",
        firstReview,
      ),
    ).toBeNull();
    expect(
      await store.loadManualReviewResult(
        accountId,
        "50000000-0000-4000-8000-000000000099",
      ),
    ).toBeNull();
  });

  it.each(["accept", "edit"] as const)(
    "removes original risk-span text together with a rejected source on %s",
    async (action) => {
      const store = createStore();
      await store.saveCandidate(accountId, caseId, candidate);
      await rejectReview(store, "KEEP_BLOCKED");
      const complete =
        action === "accept"
          ? await store.acceptCandidate(accountId, caseId, "privacy-resolve")
          : await store.returnToEdit(accountId, caseId, "privacy-resolve");
      expect(complete).toBe(true);
      const saved = await sql<{
        risk_spans: unknown;
      }>`select risk_spans from safety.safety_cases where safety_case_id=${caseId}`.execute(
        database,
      );
      expect(saved.rows[0]?.risk_spans).toEqual([]);
      expect((await reviewHistory()).decisions).toHaveLength(1);
    },
  );

  it("supports an older BLOCKED case with an immutable rejection but never recreates a resolved candidate", async () => {
    const store = createStore();
    await store.saveCandidate(accountId, caseId, candidate);
    await rejectReview(store, "REQUIRES_DEIDENTIFICATION");
    await sql`update safety.safety_cases set resolution_status='BLOCKED' where safety_case_id=${caseId}`.execute(
      database,
    );
    await expireSource();
    expect(
      await store.acceptCandidate(accountId, caseId, "legacy-rejected-accept"),
    ).toBe(true);
    await expect(
      createStore().saveCandidate(accountId, caseId, candidate),
    ).rejects.toThrow("SAFETY_SOURCE_UNAVAILABLE");
    expect((await resolution()).candidates).toBe(0);
  });
});

const expireSource = () =>
  sql`update safety.check_operations set expires_at=${now},created_at=${new Date(now.getTime() - 60_000)} where operation_id=${operationId}`.execute(
    database,
  );
const reviewHistory = async () => ({
  requests: (
    await sql`select * from safety.manual_review_requests`.execute(database)
  ).rows,
  decisions: (
    await sql`select * from safety.review_decisions`.execute(database)
  ).rows,
  messages: (await sql`select * from messaging.app_messages`.execute(database))
    .rows,
});
const resolution = async () => {
  const rows = await sql<{
    status: string;
    hasPayload: boolean;
    candidates: number;
  }>`select c.resolution_status as status, o.payload_ciphertext is not null as "hasPayload", (select count(*)::int from safety.deidentified_versions d where d.safety_case_id=c.safety_case_id) as candidates from safety.safety_cases c join safety.check_operations o on o.operation_id=c.operation_id where c.safety_case_id=${caseId}`.execute(
    database,
  );
  return rows.rows[0]!;
};
const rejectReview = async (
  store: KyselySafetyHandlingStore,
  decision: "KEEP_BLOCKED" | "REQUIRES_DEIDENTIFICATION",
) => {
  const adminId = "40000000-0000-4000-8000-000000000002";
  await sql`insert into admin.accounts (admin_id, email_ciphertext, email_algorithm, email_key_version, email_index, email_index_key_version, password_hash, totp_secret_ciphertext, totp_algorithm, totp_key_version, created_at, updated_at) values (${adminId}, decode('01','hex'), 'AES-256-GCM/v1', 1, decode('02','hex'), 1, 'isolated-fixture-hash', decode('03','hex'), 'AES-256-GCM/v1', 1, ${now}, ${now})`.execute(
    database,
  );
  const reviewId = (await store.submitManualReview(
    accountId,
    caseId,
    "explicit-review",
  ))!;
  const reviews = new KyselyAdminReviewStore(database, {
    cipher,
    identifiers: { next: () => crypto.randomUUID() },
    clock: { now: () => now },
  });
  expect(
    await reviews.decide({
      adminId,
      reviewRequestId: reviewId,
      decision,
      reasonCode: "PROTECT_IDENTITY",
      reasonZh: "请移除可以识别当事人的信息。",
      requestId: "isolated-decision",
    }),
  ).toBe(true);
  expect(await store.loadManualReviewResult(accountId, reviewId)).toMatchObject(
    { outcome: "NOT_APPROVED" },
  );
  return reviewId;
};

const candidate = {
  sanitizedText: "联系某人",
  edits: [
    {
      originalSpan: { startCodePoint: 2, endCodePoint: 4 },
      originalText: "张三",
      replacementText: "某人",
      reasonCode: "RISK",
      explanationZh: "隐藏姓名",
    },
  ],
  preservedIntentSummaryZh: "保留联系意图",
  internalSafetyValid: true as const,
  providerOperationId: "provider",
};
const createStore = () =>
  new KyselySafetyHandlingStore(database, {
    cipher,
    identifiers: { next: () => crypto.randomUUID() },
    clock: { now: () => now },
    candidateTtlMilliseconds: 60_000,
  });
