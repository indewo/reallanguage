import { PGlite } from "@electric-sql/pglite";
import { migrateToLatest } from "@reallanguage/database";
import { Kysely, PGliteDialect, sql } from "kysely";
import { afterEach, beforeEach, describe, expect, it } from "vitest";

import { AesGcmContentSafetyPayloadProtector } from "../../safety/infrastructure/aes-gcm-content-safety-payload-protector.js";
import {
  KyselyAdminReviewStore,
  type AdminReviewDatabase,
} from "./kysely-admin-review-store.js";

const userId = "30000000-0000-4000-8000-000000000001";
const adminId = "40000000-0000-4000-8000-000000000001";
const operationId = "10000000-0000-4000-8000-000000000001";
const caseId = "20000000-0000-4000-8000-000000000001";
const reviewId = "50000000-0000-4000-8000-000000000001";
const now = new Date("2026-09-04T12:00:00.000Z");
const cipher = new AesGcmContentSafetyPayloadProtector({
  key: Uint8Array.from({ length: 32 }, () => 9),
  version: 1,
});
let database: Kysely<AdminReviewDatabase>;
let store: KyselyAdminReviewStore;

beforeEach(async () => {
  database = new Kysely({
    dialect: new PGliteDialect({ pglite: new PGlite() }),
  });
  await migrateToLatest(database);
  const payload = await cipher.protect("需要人工查看的必要内容");
  await sql`insert into identity.accounts (account_id, birth_year_month_ciphertext, birth_year_month_algorithm, birth_year_month_key_version, age_eligible, age_eligibility_confirmed_at, email_verified_at, created_at, updated_at) values (${userId}, decode('01','hex'), 'AES-256-GCM/v1', 1, true, ${now}, ${now}, ${now}, ${now})`.execute(
    database,
  );
  await sql`insert into admin.accounts (admin_id, email_ciphertext, email_algorithm, email_key_version, email_index, email_index_key_version, password_hash, totp_secret_ciphertext, totp_algorithm, totp_key_version, created_at, updated_at) values (${adminId}, decode('01','hex'), 'AES-256-GCM/v1', 1, decode('02','hex'), 1, 'hash', decode('03','hex'), 'AES-256-GCM/v1', 1, ${now}, ${now})`.execute(
    database,
  );
  await sql`insert into safety.check_operations (operation_id, user_id, command_id, request_digest, source_type, content_shape, payload_ciphertext, payload_algorithm, payload_key_version, status, safety_case_id, expires_at, created_at, updated_at) values (${operationId}, ${userId}, 'check', decode('01','hex'), 'DIARY', 'ARTICLE', ${payload.ciphertext}, ${payload.algorithm}, ${payload.keyVersion}, 'BLOCKED', ${caseId}, ${new Date(now.getTime() + 60_000)}, ${now}, ${now})`.execute(
    database,
  );
  await sql`insert into safety.safety_cases (safety_case_id, operation_id, user_id, policy_version, reason_code, user_reason_zh, risk_spans, confidence, context_considered, resolution_status, created_at, updated_at) values (${caseId}, ${operationId}, ${userId}, 'p1', 'PERSONAL_DATA', '包含可识别个人信息', '[]'::jsonb, 0.9, true, 'REVIEW_PENDING', ${now}, ${now})`.execute(
    database,
  );
  await sql`insert into safety.manual_review_requests (review_request_id, safety_case_id, user_id, command_id, status, created_at, updated_at) values (${reviewId}, ${caseId}, ${userId}, 'review-1', 'PENDING', ${now}, ${now})`.execute(
    database,
  );
  let value = 1;
  store = new KyselyAdminReviewStore(database, {
    cipher,
    identifiers: {
      next: () =>
        `60000000-0000-4000-8000-${String(value++).padStart(12, "0")}`,
    },
    clock: { now: () => now },
  });
});

afterEach(async () => database.destroy());

describe("KyselyAdminReviewStore", () => {
  it("lists pending work without returning private content", async () => {
    const queue = await store.listPending();
    expect(queue).toEqual([
      {
        reviewRequestId: reviewId,
        sourceType: "DIARY",
        reasonCode: "PERSONAL_DATA",
        userReasonZh: "包含可识别个人信息",
        createdAt: now,
      },
    ]);
    expect(JSON.stringify(queue)).not.toContain("需要人工查看的必要内容");
  });

  it("reveals necessary content only through a review id and audits the view", async () => {
    await expect(
      store.loadPendingDetails(adminId, reviewId, "request-view"),
    ).resolves.toMatchObject({
      reviewRequestId: reviewId,
      text: "需要人工查看的必要内容",
      sourceType: "DIARY",
    });
    const audit = await sql<{
      action_type: string;
      actor_id: string;
      resource_id: string;
    }>`select action_type, actor_id, resource_id from platform.audit_events`.execute(
      database,
    );
    expect(audit.rows).toEqual([
      {
        action_type: "MANUAL_REVIEW_VIEWED",
        actor_id: adminId,
        resource_id: reviewId,
      },
    ]);
    expect(JSON.stringify(audit.rows)).not.toContain("需要人工查看的必要内容");
  });

  it("records one immutable decision, notifies the user, and rejects conflicting repeats", async () => {
    const command = {
      adminId,
      reviewRequestId: reviewId,
      decision: "APPROVE" as const,
      reasonCode: "CONTEXT_ACCEPTABLE",
      reasonZh: "结合上下文可继续处理。",
      requestId: "request-decide",
    };
    await expect(store.decide(command)).resolves.toBe(true);
    await expect(store.decide(command)).resolves.toBe(true);
    await expect(
      store.decide({ ...command, decision: "KEEP_BLOCKED" }),
    ).resolves.toBe(false);

    const review = await sql<{
      status: string;
    }>`select status from safety.manual_review_requests where review_request_id=${reviewId}`.execute(
      database,
    );
    expect(review.rows[0]?.status).toBe("APPROVED");
    expect(
      (await sql`select * from safety.review_decisions`.execute(database)).rows,
    ).toHaveLength(1);
    const messages = await sql<{
      kind: string;
      target_resource_id: string;
    }>`select kind, target_resource_id from messaging.app_messages`.execute(
      database,
    );
    expect(messages.rows).toEqual([
      { kind: "MANUAL_REVIEW_RESULT", target_resource_id: reviewId },
    ]);
  });
});
