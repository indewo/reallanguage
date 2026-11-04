import { PGlite } from "@electric-sql/pglite";
import { Module } from "@nestjs/common";
import type { NestFastifyApplication } from "@nestjs/platform-fastify";
import { Kysely, PGliteDialect, sql } from "kysely";
import { migrateToLatest } from "@reallanguage/database";
import {
  AesGcmContentSafetyPayloadProtector,
  HandleBlockedSafety,
  KyselySafetyHandlingStore,
  type SafetyHandlingDatabase,
} from "@reallanguage/backend-modules/safety";
import {
  DecideAdminReview,
  GetPendingAdminReview,
  ListPendingAdminReviews,
  KyselyAdminReviewStore,
  type AdminReviewDatabase,
} from "@reallanguage/backend-modules/admin";
import {
  ListAppMessages,
  MarkAllAppMessagesRead,
  KyselyAppMessageReader,
  type MessagingDatabase,
} from "@reallanguage/backend-modules/messaging";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createHttpApp } from "../create-app.js";
import { AUTHENTICATE_ACCESS_TOKEN } from "./initial-settings.controller.js";
import {
  HANDLE_BLOCKED_SAFETY_APPLICATION,
  SafetyHandlingController,
} from "./safety-handling.controller.js";
import { ADMIN_SESSION_AUTHENTICATOR } from "./admin-session.controller.js";
import {
  ADMIN_REVIEW_LIST_APPLICATION,
  ADMIN_REVIEW_DETAILS_APPLICATION,
  ADMIN_REVIEW_DECISION_APPLICATION,
  AdminReviewController,
} from "./admin-review.controller.js";
import {
  APP_MESSAGES_APPLICATION,
  MARK_ALL_APP_MESSAGES_READ_APPLICATION,
  AppMessagesController,
} from "./app-messages.controller.js";

const owner = "30000000-0000-4000-8000-000000000051";
const other = "30000000-0000-4000-8000-000000000052";
const adminId = "40000000-0000-4000-8000-000000000051";
const caseId = "20000000-0000-4000-8000-000000000051";
const operationId = "10000000-0000-4000-8000-000000000051";
const now = new Date("2026-09-08T12:00:00Z");
const headers = { authorization: "Bearer fixture-owner" };
const adminHeaders = { authorization: "Bearer fixture-admin" };
let database: Kysely<SafetyHandlingDatabase & AdminReviewDatabase>;
let app: NestFastifyApplication;
const provider = { deidentify: vi.fn() };
beforeEach(async () => {
  database = new Kysely({
    dialect: new PGliteDialect({ pglite: new PGlite() }),
  });
  await migrateToLatest(database);
  const cipher = new AesGcmContentSafetyPayloadProtector({
    key: Uint8Array.from({ length: 32 }, () => 7),
    version: 1,
  });
  const payload = await cipher.protect("联系张三");
  for (const id of [owner, other])
    await sql`insert into identity.accounts (account_id,birth_year_month_ciphertext,birth_year_month_algorithm,birth_year_month_key_version,age_eligible,age_eligibility_confirmed_at,email_verified_at) values (${id},decode('01','hex'),'AES-256-GCM/v1',1,true,${now},${now})`.execute(
      database,
    );
  await sql`insert into admin.accounts (admin_id,email_ciphertext,email_algorithm,email_key_version,email_index,email_index_key_version,password_hash,totp_secret_ciphertext,totp_algorithm,totp_key_version) values (${adminId},decode('01','hex'),'AES-256-GCM/v1',1,decode('02','hex'),1,'isolated-fixture',decode('03','hex'),'AES-256-GCM/v1',1)`.execute(
    database,
  );
  await sql`insert into safety.check_operations (operation_id,user_id,command_id,request_digest,source_type,content_shape,payload_ciphertext,payload_algorithm,payload_key_version,status,safety_case_id,expires_at,created_at,updated_at) values (${operationId},${owner},'isolated-check',decode('01','hex'),'DIARY','ARTICLE',${payload.ciphertext},${payload.algorithm},${payload.keyVersion},'BLOCKED',${caseId},${new Date(now.getTime() + 60000)},${now},${now})`.execute(
    database,
  );
  await sql`insert into safety.safety_cases (safety_case_id,operation_id,user_id,policy_version,reason_code,user_reason_zh,risk_spans,confidence,context_considered) values (${caseId},${operationId},${owner},'p1','AUTO_REASON','自动拦截说明','[]'::jsonb,0.9,true)`.execute(
    database,
  );
  const dependencies = {
    cipher,
    identifiers: { next: () => crypto.randomUUID() },
    clock: { now: () => now },
  };
  const handling = new KyselySafetyHandlingStore(database, {
    ...dependencies,
    candidateTtlMilliseconds: 60000,
  });
  await handling.saveCandidate(owner, caseId, {
    sanitizedText: "联系某人",
    edits: [
      {
        originalSpan: { startCodePoint: 2, endCodePoint: 4 },
        originalText: "张三",
        replacementText: "某人",
        explanationZh: "隐藏姓名",
        reasonCode: "IDENTITY",
      },
    ],
    preservedIntentSummaryZh: "保留联系意图",
    internalSafetyValid: true,
    providerOperationId: "isolated-cached-candidate",
  });
  provider.deidentify
    .mockReset()
    .mockRejectedValue(
      new Error("No provider calls allowed in this integration test"),
    );
  const reviews = new KyselyAdminReviewStore(database, dependencies);
  // Messaging's declared row_version uses a string representation; SQL itself
  // is the same real database, without a mocked read or write result.
  const messages = new KyselyAppMessageReader(
    database as unknown as Kysely<MessagingDatabase>,
    dependencies.clock,
  );
  @Module({
    controllers: [
      SafetyHandlingController,
      AdminReviewController,
      AppMessagesController,
    ],
    providers: [
      {
        provide: AUTHENTICATE_ACCESS_TOKEN,
        useValue: {
          authenticate: async (token: string) =>
            token === "fixture-owner"
              ? owner
              : token === "fixture-other"
                ? other
                : null,
        },
      },
      {
        provide: ADMIN_SESSION_AUTHENTICATOR,
        useValue: {
          execute: async (token: string) =>
            token === "fixture-admin"
              ? { status: "AUTHENTICATED", adminId }
              : { status: "INVALID_OR_EXPIRED" },
        },
      },
      {
        provide: HANDLE_BLOCKED_SAFETY_APPLICATION,
        useValue: new HandleBlockedSafety(handling, provider),
      },
      {
        provide: ADMIN_REVIEW_LIST_APPLICATION,
        useValue: new ListPendingAdminReviews(reviews),
      },
      {
        provide: ADMIN_REVIEW_DETAILS_APPLICATION,
        useValue: new GetPendingAdminReview(reviews),
      },
      {
        provide: ADMIN_REVIEW_DECISION_APPLICATION,
        useValue: new DecideAdminReview(reviews),
      },
      {
        provide: APP_MESSAGES_APPLICATION,
        useValue: new ListAppMessages(messages),
      },
      {
        provide: MARK_ALL_APP_MESSAGES_READ_APPLICATION,
        useValue: new MarkAllAppMessagesRead(messages, dependencies.clock),
      },
    ],
  })
  class IsolatedManualReviewModule {}
  app = await createHttpApp(IsolatedManualReviewModule);
});
afterEach(async () => {
  await app?.close();
  await database.destroy();
});

describe("explicit manual review through real HTTP and isolated database", () => {
  it.each([
    ["APPROVE", "APPROVED"],
    ["KEEP_BLOCKED", "NOT_APPROVED"],
  ] as const)(
    "replays an acknowledged %s decision as its current state, not a new pending review",
    async (decision, reviewStatus) => {
      const submitUrl = `/api/v1/safety/cases/${caseId}/manual-reviews`;
      const payload = { commandId: "response-lost-review" };
      const submitted = await app.inject({
        method: "POST",
        url: submitUrl,
        headers,
        payload,
      });
      expect(submitted.statusCode).toBe(200);
      const reviewId = submitted.json().reviewRequestId as string;
      expect(submitted.json()).toEqual({
        status: "SUBMITTED",
        reviewRequestId: reviewId,
        reviewStatus: "PENDING",
      });
      const decided = await app.inject({
        method: "POST",
        url: `/api/v1/admin/manual-reviews/${reviewId}/decisions`,
        headers: adminHeaders,
        payload: {
          decision,
          reasonCode: "REVIEW_COMPLETE",
          reasonZh: "人工复核已完成。",
        },
      });
      expect(decided.statusCode).toBe(200);
      // Replaying a receipt never requires re-reading sensitive source content.
      await sql`update safety.check_operations set payload_ciphertext=null,payload_algorithm=null,payload_key_version=null where operation_id=${operationId}`.execute(
        database,
      );
      const before = await manualHistory();
      const replayed = await app.inject({
        method: "POST",
        url: submitUrl,
        headers,
        payload,
      });
      expect(replayed.statusCode).toBe(200);
      expect(replayed.json()).toEqual({
        status: "SUBMITTED",
        reviewRequestId: reviewId,
        reviewStatus,
      });
      expect(await manualHistory()).toEqual(before);
      expect(
        (
          await app.inject({
            method: "POST",
            url: submitUrl,
            headers: { authorization: "Bearer fixture-other" },
            payload,
          })
        ).statusCode,
      ).toBe(404);
      expect(
        (
          await app.inject({
            method: "POST",
            url: `/api/v1/safety/cases/20000000-0000-4000-8000-000000000099/manual-reviews`,
            headers,
            payload,
          })
        ).statusCode,
      ).toBe(404);
      expect(await manualHistory()).toEqual(before);
      expect(
        (
          await app.inject({
            method: "GET",
            url: `/api/v1/safety/cases/manual-reviews/${reviewId}`,
            headers,
          })
        ).statusCode,
      ).toBe(404);
      expect(provider.deidentify).not.toHaveBeenCalled();
    },
  );
  it.each(["accept-deidentified", "return-to-edit"])(
    "restores %s after rejection, preserving the decision and refusing a repeated different action",
    async (action) => {
      const submitted = await app.inject({
        method: "POST",
        url: `/api/v1/safety/cases/${caseId}/manual-reviews`,
        headers,
        payload: { commandId: "explicit-review" },
      });
      expect(submitted.statusCode).toBe(200);
      const reviewId = submitted.json().reviewRequestId as string;
      const reviewUrl = `/api/v1/admin/manual-reviews/${reviewId}/decisions`;
      const decision = {
        decision: "KEEP_BLOCKED",
        reasonCode: "IDENTITY_STILL_PRESENT",
        reasonZh: "请去除可识别姓名，再继续学习。",
      };
      expect(
        (
          await app.inject({
            method: "POST",
            url: reviewUrl,
            headers,
            payload: decision,
          })
        ).statusCode,
      ).toBe(401);
      expect(
        (
          await app.inject({
            method: "POST",
            url: `/api/v1/safety/cases/${caseId}/${action}`,
            headers,
            payload: { commandId: "not-before-decision" },
          })
        ).statusCode,
      ).toBe(404);
      expect(
        (
          await app.inject({
            method: "POST",
            url: reviewUrl,
            headers: adminHeaders,
            payload: decision,
          })
        ).statusCode,
      ).toBe(200);
      expect(
        (
          await app.inject({
            method: "POST",
            url: reviewUrl,
            headers: adminHeaders,
            payload: decision,
          })
        ).statusCode,
      ).toBe(200);
      expect(
        (
          await app.inject({
            method: "POST",
            url: reviewUrl,
            headers: adminHeaders,
            payload: { ...decision, decision: "APPROVE" },
          })
        ).statusCode,
      ).toBe(404);
      const messageResponse = await app.inject({
        method: "GET",
        url: "/api/v1/messages",
        headers,
      });
      expect(messageResponse.statusCode).toBe(200);
      expect(messageResponse.json().messages).toHaveLength(1);
      expect(messageResponse.json().messages[0].target).toEqual({
        route: "MANUAL_REVIEW_RESULT",
        resourceId: reviewId,
      });
      const resultUrl = `/api/v1/safety/cases/manual-reviews/${reviewId}`;
      expect(
        (await app.inject({ method: "GET", url: resultUrl, headers })).json(),
      ).toMatchObject({
        outcome: "NOT_APPROVED",
        source: {
          safetyCaseId: caseId,
          text: "联系张三",
          userReasonZh: decision.reasonZh,
        },
      });
      const otherHeaders = { authorization: "Bearer fixture-other" };
      expect(
        (
          await app.inject({
            method: "GET",
            url: resultUrl,
            headers: otherHeaders,
          })
        ).statusCode,
      ).toBe(404);
      expect(
        (
          await app.inject({
            method: "GET",
            url: "/api/v1/messages",
            headers: otherHeaders,
          })
        ).json().messages,
      ).toEqual([]);
      expect(
        (
          await app.inject({
            method: "POST",
            url: `/api/v1/safety/cases/${caseId}/${action}`,
            headers: otherHeaders,
            payload: { commandId: "cross-account" },
          })
        ).statusCode,
      ).toBe(404);
      const prepared = await app.inject({
        method: "POST",
        url: `/api/v1/safety/cases/${caseId}/handling`,
        headers,
      });
      expect(prepared.statusCode).toBe(200);
      expect(prepared.json().deidentifiedSource.text).toBe("联系某人");
      for (let attempt = 0; attempt < 2; attempt++)
        expect(
          (
            await app.inject({
              method: "POST",
              url: `/api/v1/safety/cases/${caseId}/${action}`,
              headers,
              payload: { commandId: "user-resolves" },
            })
          ).json(),
        ).toEqual({ status: "COMPLETED" });
      expect(
        (
          await app.inject({
            method: "POST",
            url: `/api/v1/safety/cases/${caseId}/${action}`,
            headers,
            payload: { commandId: "different-command" },
          })
        ).statusCode,
      ).toBe(404);
      const saved = (
        await sql<{
          status: string;
          decision: string;
          reason_zh: string;
          has_payload: boolean;
        }>`select r.status,d.decision,d.reason_zh,o.payload_ciphertext is not null as has_payload from safety.manual_review_requests r join safety.review_decisions d on d.review_request_id=r.review_request_id join safety.safety_cases c on c.safety_case_id=r.safety_case_id join safety.check_operations o on o.operation_id=c.operation_id where r.review_request_id=${reviewId}`.execute(
          database,
        )
      ).rows;
      expect(saved).toEqual([
        {
          status: "NOT_APPROVED",
          decision: "KEEP_BLOCKED",
          reason_zh: decision.reasonZh,
          has_payload: false,
        },
      ]);
      expect(
        (await app.inject({ method: "GET", url: resultUrl, headers }))
          .statusCode,
      ).toBe(404);
      expect(provider.deidentify).not.toHaveBeenCalled();
    },
  );
});

async function manualHistory() {
  return {
    reviews: (
      await sql`select * from safety.manual_review_requests order by review_request_id`.execute(
        database,
      )
    ).rows,
    decisions: (
      await sql`select * from safety.review_decisions order by review_request_id`.execute(
        database,
      )
    ).rows,
    messages: (
      await sql`select * from messaging.app_messages order by message_id`.execute(
        database,
      )
    ).rows,
  };
}
