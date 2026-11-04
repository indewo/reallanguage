import type { ColumnType, Generated, Kysely } from "kysely";

import type { ContentSafetyPayloadCipher } from "../../safety/infrastructure/kysely-content-safety-gateway.js";
import type {
  AdminReviewDecision,
  AdminReviewStore,
  PendingAdminReview,
  PendingAdminReviewDetails,
} from "../application/review-manual-content.js";

type Timestamp = ColumnType<Date, Date | undefined, Date>;
type SourceType = "DIARY" | "RECORDING" | "AI_SCENE";

interface ReviewRequestsTable {
  review_request_id: string;
  safety_case_id: string;
  user_id: string;
  command_id: string;
  status: Generated<"PENDING" | "APPROVED" | "NOT_APPROVED">;
  row_version: Generated<number>;
  created_at: Timestamp;
  updated_at: Timestamp;
}
interface SafetyCasesTable {
  safety_case_id: string;
  operation_id: string;
  user_id: string;
  reason_code: string;
  user_reason_zh: string;
}
interface SafetyOperationsTable {
  operation_id: string;
  user_id: string;
  source_type: SourceType;
  content_shape: "ARTICLE" | "DIALOGUE";
  payload_ciphertext: Uint8Array | null;
  payload_algorithm: string | null;
  payload_key_version: number | null;
}
interface ReviewDecisionsTable {
  review_decision_id: string;
  review_request_id: string;
  user_id: string;
  reviewer_admin_id: string;
  decision: AdminReviewDecision;
  reason_code: string;
  reason_zh: string;
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
interface AppMessagesTable {
  message_id: string;
  user_id: string;
  kind: "MANUAL_REVIEW_RESULT" | "SYSTEM";
  summary_zh: string;
  related_content_label_zh: string | null;
  target_route: "MANUAL_REVIEW_RESULT" | null;
  target_resource_id: string | null;
  read_at: Date | null;
  expires_at: Date | null;
  row_version: Generated<number>;
  created_at: Timestamp;
  updated_at: Timestamp;
}

export interface AdminReviewDatabase {
  "safety.manual_review_requests": ReviewRequestsTable;
  "safety.safety_cases": SafetyCasesTable;
  "safety.check_operations": SafetyOperationsTable;
  "safety.review_decisions": ReviewDecisionsTable;
  "platform.audit_events": AuditEventsTable;
  "messaging.app_messages": AppMessagesTable;
}

export class KyselyAdminReviewStore implements AdminReviewStore {
  public constructor(
    private readonly database: Kysely<AdminReviewDatabase>,
    private readonly dependencies: Readonly<{
      cipher: ContentSafetyPayloadCipher;
      identifiers: { next(): string };
      clock: { now(): Date };
    }>,
  ) {}

  public async listPending(): Promise<readonly PendingAdminReview[]> {
    const rows = await this.database
      .selectFrom("safety.manual_review_requests as reviews")
      .innerJoin(
        "safety.safety_cases as cases",
        "cases.safety_case_id",
        "reviews.safety_case_id",
      )
      .innerJoin(
        "safety.check_operations as operations",
        "operations.operation_id",
        "cases.operation_id",
      )
      .select([
        "reviews.review_request_id",
        "reviews.created_at",
        "cases.reason_code",
        "cases.user_reason_zh",
        "operations.source_type",
      ])
      .where("reviews.status", "=", "PENDING")
      .orderBy("reviews.created_at", "asc")
      .execute();
    return rows.map((row) => ({
      reviewRequestId: row.review_request_id,
      sourceType: row.source_type,
      reasonCode: row.reason_code,
      userReasonZh: row.user_reason_zh,
      createdAt: new Date(row.created_at),
    }));
  }

  public async loadPendingDetails(
    adminId: string,
    reviewRequestId: string,
    requestId: string,
  ): Promise<PendingAdminReviewDetails | null> {
    const row = await this.database
      .selectFrom("safety.manual_review_requests as reviews")
      .innerJoin(
        "safety.safety_cases as cases",
        "cases.safety_case_id",
        "reviews.safety_case_id",
      )
      .innerJoin(
        "safety.check_operations as operations",
        "operations.operation_id",
        "cases.operation_id",
      )
      .select([
        "reviews.review_request_id",
        "reviews.created_at",
        "reviews.user_id",
        "cases.reason_code",
        "cases.user_reason_zh",
        "operations.source_type",
        "operations.content_shape",
        "operations.payload_ciphertext",
        "operations.payload_algorithm",
        "operations.payload_key_version",
      ])
      .where("reviews.review_request_id", "=", reviewRequestId)
      .where("reviews.status", "=", "PENDING")
      .executeTakeFirst();
    if (
      !row?.payload_ciphertext ||
      !row.payload_algorithm ||
      !row.payload_key_version
    ) {
      return null;
    }
    const text = await this.dependencies.cipher.unprotect({
      ciphertext: row.payload_ciphertext,
      algorithm: row.payload_algorithm,
      keyVersion: row.payload_key_version,
    });
    await this.database
      .insertInto("platform.audit_events")
      .values({
        audit_event_id: this.dependencies.identifiers.next(),
        user_id: row.user_id,
        actor_type: "ADMIN",
        actor_id: adminId,
        action_type: "MANUAL_REVIEW_VIEWED",
        resource_type: "MANUAL_REVIEW",
        resource_id: reviewRequestId,
        result: "SUCCESS",
        reason_code: null,
        request_id: requestId,
        created_at: this.dependencies.clock.now(),
      })
      .execute();
    return {
      reviewRequestId: row.review_request_id,
      sourceType: row.source_type,
      contentShape: row.content_shape,
      reasonCode: row.reason_code,
      userReasonZh: row.user_reason_zh,
      createdAt: new Date(row.created_at),
      text,
    };
  }

  public async decide(input: {
    adminId: string;
    reviewRequestId: string;
    decision: AdminReviewDecision;
    reasonCode: string;
    reasonZh: string;
    requestId: string;
  }): Promise<boolean> {
    return this.database.transaction().execute(async (transaction) => {
      const existing = await transaction
        .selectFrom("safety.review_decisions")
        .select(["reviewer_admin_id", "decision", "reason_code", "reason_zh"])
        .where("review_request_id", "=", input.reviewRequestId)
        .executeTakeFirst();
      if (existing) {
        return (
          existing.reviewer_admin_id === input.adminId &&
          existing.decision === input.decision &&
          existing.reason_code === input.reasonCode &&
          existing.reason_zh === input.reasonZh
        );
      }
      const review = await transaction
        .selectFrom("safety.manual_review_requests as reviews")
        .innerJoin(
          "safety.safety_cases as cases",
          "cases.safety_case_id",
          "reviews.safety_case_id",
        )
        .innerJoin(
          "safety.check_operations as operations",
          "operations.operation_id",
          "cases.operation_id",
        )
        .select(["reviews.user_id", "reviews.status", "operations.source_type"])
        .where("reviews.review_request_id", "=", input.reviewRequestId)
        .executeTakeFirst();
      if (!review || review.status !== "PENDING") return false;
      const status = input.decision === "APPROVE" ? "APPROVED" : "NOT_APPROVED";
      const updated = await transaction
        .updateTable("safety.manual_review_requests")
        .set((expression) => ({
          status,
          row_version: expression("row_version", "+", 1),
          updated_at: this.dependencies.clock.now(),
        }))
        .where("review_request_id", "=", input.reviewRequestId)
        .where("status", "=", "PENDING")
        .executeTakeFirst();
      if (Number(updated.numUpdatedRows) !== 1) return false;

      const decisionId = this.dependencies.identifiers.next();
      const timestamp = this.dependencies.clock.now();
      await transaction
        .insertInto("safety.review_decisions")
        .values({
          review_decision_id: decisionId,
          review_request_id: input.reviewRequestId,
          user_id: review.user_id,
          reviewer_admin_id: input.adminId,
          decision: input.decision,
          reason_code: input.reasonCode,
          reason_zh: input.reasonZh,
          created_at: timestamp,
        })
        .execute();
      await transaction
        .insertInto("messaging.app_messages")
        .values({
          message_id: this.dependencies.identifiers.next(),
          user_id: review.user_id,
          kind: "MANUAL_REVIEW_RESULT",
          summary_zh: decisionSummary(input.decision),
          related_content_label_zh: sourceLabel(review.source_type),
          target_route: "MANUAL_REVIEW_RESULT",
          target_resource_id: input.reviewRequestId,
          read_at: null,
          expires_at: null,
          created_at: timestamp,
          updated_at: timestamp,
        })
        .execute();
      await transaction
        .insertInto("platform.audit_events")
        .values({
          audit_event_id: this.dependencies.identifiers.next(),
          user_id: review.user_id,
          actor_type: "ADMIN",
          actor_id: input.adminId,
          action_type: "MANUAL_REVIEW_DECIDED",
          resource_type: "MANUAL_REVIEW",
          resource_id: input.reviewRequestId,
          result: input.decision,
          reason_code: input.reasonCode,
          request_id: input.requestId,
          created_at: timestamp,
        })
        .execute();
      return true;
    });
  }
}

const decisionSummary = (decision: AdminReviewDecision): string => {
  if (decision === "APPROVE") return "本次内容已通过人工复核。";
  if (decision === "REQUIRES_DEIDENTIFICATION") {
    return "本次内容需要进一步脱敏处理。";
  }
  return "本次内容维持安全拦截。";
};

const sourceLabel = (sourceType: SourceType): string => {
  if (sourceType === "DIARY") return "日记";
  if (sourceType === "RECORDING") return "录音转写";
  return "AI 场景";
};
