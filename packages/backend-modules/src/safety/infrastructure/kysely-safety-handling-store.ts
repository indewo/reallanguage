import type { ColumnType, Generated, JSONColumnType, Kysely } from "kysely";
import type {
  BlockedSafetySource,
  DeidentifiedCandidate,
  DeidentificationEdit,
  SafetyHandlingStore,
  ManualReviewResult,
} from "../application/handle-blocked-safety.js";
import type {
  ContentSafetyPayloadCipher,
  SafetyRiskSpan,
} from "./kysely-content-safety-gateway.js";

type Timestamp = ColumnType<Date, Date | undefined, Date>;
interface HandlingOperationsTable {
  operation_id: string;
  user_id: string;
  source_type: BlockedSafetySource["sourceType"];
  content_shape: BlockedSafetySource["contentShape"];
  payload_ciphertext: Uint8Array | null;
  payload_algorithm: string | null;
  payload_key_version: number | null;
  expires_at: ColumnType<Date, Date, Date>;
}
interface HandlingCasesTable {
  safety_case_id: string;
  operation_id: string;
  user_id: string;
  reason_code: string;
  user_reason_zh: string;
  risk_spans: JSONColumnType<readonly SafetyRiskSpan[], string, string>;
  resolution_status: Generated<
    "BLOCKED" | "DEIDENTIFIED_ACCEPTED" | "RETURNED_TO_EDIT" | "REVIEW_PENDING"
  >;
  resolution_command_id: string | null;
}
interface DeidentifiedVersionsTable {
  deidentified_version_id: string;
  safety_case_id: string;
  user_id: string;
  payload_ciphertext: Uint8Array;
  payload_algorithm: string;
  payload_key_version: number;
  edits: JSONColumnType<readonly DeidentificationEdit[], string, never>;
  preserved_intent_summary_zh: string;
  provider_operation_id: string;
  internal_safety_valid: true;
  expires_at: ColumnType<Date, Date, Date>;
  created_at: Timestamp;
}
interface ManualReviewRequestsTable {
  review_request_id: string;
  safety_case_id: string;
  user_id: string;
  command_id: string;
  status: Generated<"PENDING" | "APPROVED" | "NOT_APPROVED">;
  row_version: Generated<number>;
  created_at: Timestamp;
  updated_at: Timestamp;
}
interface ManualReviewDecisionsTable {
  review_request_id: string;
  user_id: string;
  reason_code: string;
  reason_zh: string;
}
export interface SafetyHandlingDatabase {
  "safety.check_operations": HandlingOperationsTable;
  "safety.safety_cases": HandlingCasesTable;
  "safety.deidentified_versions": DeidentifiedVersionsTable;
  "safety.manual_review_requests": ManualReviewRequestsTable;
  "safety.review_decisions": ManualReviewDecisionsTable;
}

export class KyselySafetyHandlingStore implements SafetyHandlingStore {
  public constructor(
    private readonly database: Kysely<SafetyHandlingDatabase>,
    private readonly dependencies: Readonly<{
      cipher: ContentSafetyPayloadCipher;
      identifiers: { next(): string };
      clock: { now(): Date };
      candidateTtlMilliseconds: number;
    }>,
  ) {}

  public async loadManualReviewStatus(
    accountId: string,
    safetyCaseId: string,
    reviewRequestId: string,
  ): Promise<"PENDING" | "APPROVED" | "NOT_APPROVED" | null> {
    const review = await this.database
      .selectFrom("safety.manual_review_requests")
      .select("status")
      .where("user_id", "=", accountId)
      .where("safety_case_id", "=", safetyCaseId)
      .where("review_request_id", "=", reviewRequestId)
      .executeTakeFirst();
    return review?.status ?? null;
  }

  public async loadBlocked(
    accountId: string,
    safetyCaseId: string,
  ): Promise<BlockedSafetySource | null> {
    const row = await this.database
      .selectFrom("safety.safety_cases as cases")
      .innerJoin(
        "safety.check_operations as operations",
        "operations.operation_id",
        "cases.operation_id",
      )
      .leftJoin(
        "safety.manual_review_requests as reviews",
        "reviews.safety_case_id",
        "cases.safety_case_id",
      )
      .select([
        "cases.safety_case_id",
        "cases.reason_code",
        "cases.user_reason_zh",
        "cases.risk_spans",
        "operations.source_type",
        "operations.content_shape",
        "operations.payload_ciphertext",
        "operations.payload_algorithm",
        "operations.payload_key_version",
      ])
      .where("cases.user_id", "=", accountId)
      .where("cases.safety_case_id", "=", safetyCaseId)
      .where("operations.user_id", "=", accountId)
      .where((expression) =>
        expression.or([
          expression("cases.resolution_status", "=", "BLOCKED"),
          expression("reviews.status", "=", "NOT_APPROVED"),
        ]),
      )
      .where((expression) =>
        expression.or([
          expression(
            "operations.expires_at",
            ">",
            this.dependencies.clock.now(),
          ),
          expression.and([
            expression("reviews.user_id", "=", accountId),
            expression("reviews.status", "=", "NOT_APPROVED"),
          ]),
        ]),
      )
      .executeTakeFirst();
    if (
      !row?.payload_ciphertext ||
      !row.payload_algorithm ||
      !row.payload_key_version
    )
      return null;
    return {
      safetyCaseId: row.safety_case_id,
      text: await this.dependencies.cipher.unprotect({
        ciphertext: row.payload_ciphertext,
        algorithm: row.payload_algorithm,
        keyVersion: row.payload_key_version,
      }),
      sourceType: row.source_type,
      contentShape: row.content_shape,
      reasonCode: row.reason_code,
      userReasonZh: row.user_reason_zh,
      riskSpans: parseJsonArray<SafetyRiskSpan>(row.risk_spans),
    };
  }

  public async loadCandidate(
    accountId: string,
    safetyCaseId: string,
  ): Promise<DeidentifiedCandidate | null> {
    const row = await this.database
      .selectFrom("safety.deidentified_versions")
      .selectAll()
      .where("user_id", "=", accountId)
      .where("safety_case_id", "=", safetyCaseId)
      .where("expires_at", ">", this.dependencies.clock.now())
      .executeTakeFirst();
    if (!row) return null;
    return {
      sanitizedText: await this.dependencies.cipher.unprotect({
        ciphertext: row.payload_ciphertext,
        algorithm: row.payload_algorithm,
        keyVersion: row.payload_key_version,
      }),
      edits: parseJsonArray<DeidentificationEdit>(row.edits),
      preservedIntentSummaryZh: row.preserved_intent_summary_zh,
      internalSafetyValid: true,
      providerOperationId: row.provider_operation_id,
    };
  }

  public async saveCandidate(
    accountId: string,
    safetyCaseId: string,
    candidate: DeidentifiedCandidate,
  ): Promise<void> {
    const payload = await this.dependencies.cipher.protect(
      candidate.sanitizedText,
    );
    await this.database.transaction().execute(async (transaction) => {
      const source = await transaction
        .selectFrom("safety.safety_cases")
        .select(["operation_id", "resolution_status"])
        .where("user_id", "=", accountId)
        .where("safety_case_id", "=", safetyCaseId)
        .forUpdate()
        .executeTakeFirst();
      if (!source) throw new Error("SAFETY_SOURCE_UNAVAILABLE");
      const review = await transaction
        .selectFrom("safety.manual_review_requests")
        .select("status")
        .where("user_id", "=", accountId)
        .where("safety_case_id", "=", safetyCaseId)
        .executeTakeFirst();
      const returnedReview = review?.status === "NOT_APPROVED";
      if (
        (!returnedReview && source.resolution_status !== "BLOCKED") ||
        (review && !returnedReview)
      )
        throw new Error("SAFETY_SOURCE_UNAVAILABLE");
      const now = this.dependencies.clock.now();
      let available = transaction
        .selectFrom("safety.check_operations")
        .select("operation_id")
        .where("user_id", "=", accountId)
        .where("operation_id", "=", source.operation_id)
        .where("payload_ciphertext", "is not", null);
      if (!returnedReview) available = available.where("expires_at", ">", now);
      if (!(await available.executeTakeFirst()))
        throw new Error("SAFETY_SOURCE_UNAVAILABLE");
      await transaction
        .insertInto("safety.deidentified_versions")
        .values({
          deidentified_version_id: this.dependencies.identifiers.next(),
          safety_case_id: safetyCaseId,
          user_id: accountId,
          payload_ciphertext: payload.ciphertext,
          payload_algorithm: payload.algorithm,
          payload_key_version: payload.keyVersion,
          edits: JSON.stringify(candidate.edits),
          preserved_intent_summary_zh: candidate.preservedIntentSummaryZh,
          provider_operation_id: candidate.providerOperationId,
          internal_safety_valid: true,
          expires_at: new Date(
            now.getTime() + this.dependencies.candidateTtlMilliseconds,
          ),
          created_at: now,
        })
        .onConflict((conflict) => conflict.column("safety_case_id").doNothing())
        .execute();
    });
  }

  public async hasExpiredCandidate(
    accountId: string,
    safetyCaseId: string,
  ): Promise<boolean> {
    const row = await this.database
      .selectFrom("safety.deidentified_versions")
      .select("deidentified_version_id")
      .where("user_id", "=", accountId)
      .where("safety_case_id", "=", safetyCaseId)
      .where("expires_at", "<=", this.dependencies.clock.now())
      .executeTakeFirst();
    return row !== undefined;
  }

  public acceptCandidate(
    accountId: string,
    safetyCaseId: string,
    commandId: string,
  ) {
    return this.resolve(
      accountId,
      safetyCaseId,
      commandId,
      "DEIDENTIFIED_ACCEPTED",
      true,
    );
  }
  public returnToEdit(
    accountId: string,
    safetyCaseId: string,
    commandId: string,
  ) {
    return this.resolve(
      accountId,
      safetyCaseId,
      commandId,
      "RETURNED_TO_EDIT",
      true,
    );
  }

  public async submitManualReview(
    accountId: string,
    safetyCaseId: string,
    commandId: string,
  ): Promise<string | null> {
    return this.database.transaction().execute(async (transaction) => {
      // Retention and candidate acceptance lock this same case first. Recheck
      // the payload only after obtaining the lock, never from a stale UI read.
      const source = await transaction
        .selectFrom("safety.safety_cases")
        .select(["operation_id", "resolution_status"])
        .where("user_id", "=", accountId)
        .where("safety_case_id", "=", safetyCaseId)
        .forUpdate()
        .executeTakeFirst();
      if (!source) return null;
      const existing = await transaction
        .selectFrom("safety.manual_review_requests")
        .select(["review_request_id", "safety_case_id"])
        .where("user_id", "=", accountId)
        .where("command_id", "=", commandId)
        .executeTakeFirst();
      if (existing)
        return existing.safety_case_id === safetyCaseId
          ? existing.review_request_id
          : null;
      if (source.resolution_status !== "BLOCKED") return null;
      const now = this.dependencies.clock.now();
      const payload = await transaction
        .selectFrom("safety.check_operations")
        .select("operation_id")
        .where("operation_id", "=", source.operation_id)
        .where("user_id", "=", accountId)
        .where("payload_ciphertext", "is not", null)
        .where("expires_at", ">", now)
        .executeTakeFirst();
      if (!payload) return null;
      const reviewRequestId = this.dependencies.identifiers.next();
      await transaction
        .updateTable("safety.safety_cases")
        .set({
          resolution_status: "REVIEW_PENDING",
          resolution_command_id: commandId,
        })
        .where("user_id", "=", accountId)
        .where("safety_case_id", "=", safetyCaseId)
        .executeTakeFirstOrThrow();
      await transaction
        .insertInto("safety.manual_review_requests")
        .values({
          review_request_id: reviewRequestId,
          safety_case_id: safetyCaseId,
          user_id: accountId,
          command_id: commandId,
          created_at: now,
          updated_at: now,
        })
        .execute();
      return reviewRequestId;
    });
  }

  public async loadManualReviewResult(
    accountId: string,
    reviewRequestId: string,
  ): Promise<ManualReviewResult | null> {
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
      .leftJoin("safety.review_decisions as decisions", (join) =>
        join
          .onRef(
            "decisions.review_request_id",
            "=",
            "reviews.review_request_id",
          )
          .onRef("decisions.user_id", "=", "reviews.user_id"),
      )
      .select([
        "reviews.status",
        "cases.safety_case_id",
        "cases.reason_code",
        "cases.user_reason_zh",
        "cases.risk_spans",
        "decisions.reason_code as review_reason_code",
        "decisions.reason_zh as review_reason_zh",
        "operations.source_type",
        "operations.content_shape",
        "operations.payload_ciphertext",
        "operations.payload_algorithm",
        "operations.payload_key_version",
      ])
      .where("reviews.user_id", "=", accountId)
      .where("cases.user_id", "=", accountId)
      .where("operations.user_id", "=", accountId)
      .where("reviews.review_request_id", "=", reviewRequestId)
      .executeTakeFirst();
    if (
      !row ||
      row.status === "PENDING" ||
      !row.payload_ciphertext ||
      !row.payload_algorithm ||
      !row.payload_key_version
    )
      return null;
    return {
      outcome: row.status,
      source: {
        safetyCaseId: row.safety_case_id,
        text: await this.dependencies.cipher.unprotect({
          ciphertext: row.payload_ciphertext,
          algorithm: row.payload_algorithm,
          keyVersion: row.payload_key_version,
        }),
        sourceType: row.source_type,
        contentShape: row.content_shape,
        reasonCode: row.review_reason_code ?? row.reason_code,
        userReasonZh: row.review_reason_zh ?? row.user_reason_zh,
        riskSpans: parseJsonArray<SafetyRiskSpan>(row.risk_spans),
      },
    };
  }

  private async resolve(
    accountId: string,
    safetyCaseId: string,
    commandId: string,
    status: "DEIDENTIFIED_ACCEPTED" | "RETURNED_TO_EDIT",
    clearPayload: boolean,
  ): Promise<boolean> {
    return this.database.transaction().execute(async (transaction) => {
      const existing = await transaction
        .selectFrom("safety.safety_cases")
        .select(["operation_id", "resolution_status", "resolution_command_id"])
        .where("user_id", "=", accountId)
        .where("safety_case_id", "=", safetyCaseId)
        .forUpdate()
        .executeTakeFirst();
      if (!existing) return false;
      if (
        existing.resolution_status === status &&
        existing.resolution_command_id === commandId
      )
        return true;
      const review = await transaction
        .selectFrom("safety.manual_review_requests")
        .select("status")
        .where("user_id", "=", accountId)
        .where("safety_case_id", "=", safetyCaseId)
        .executeTakeFirst();
      const returnedReview = review?.status === "NOT_APPROVED";
      const unresolved =
        existing.resolution_status === "BLOCKED" ||
        (existing.resolution_status === "REVIEW_PENDING" && returnedReview);
      if (!unresolved || (review && !returnedReview)) return false;
      if (returnedReview) {
        // Reviewed sources remain explicitly held beyond the ordinary TTL,
        // but a deleted source must never be resurrected from stale UI data.
        const source = await transaction
          .selectFrom("safety.check_operations")
          .select("operation_id")
          .where("user_id", "=", accountId)
          .where("operation_id", "=", existing.operation_id)
          .where("payload_ciphertext", "is not", null)
          .where("payload_algorithm", "is not", null)
          .where("payload_key_version", "is not", null)
          .executeTakeFirst();
        if (!source) return false;
      }
      if (status === "DEIDENTIFIED_ACCEPTED") {
        const now = this.dependencies.clock.now();
        let candidateQuery = transaction
          .selectFrom("safety.deidentified_versions as candidate")
          .innerJoin("safety.check_operations as source", (join) =>
            join.on("source.operation_id", "=", existing.operation_id),
          )
          .select("candidate.deidentified_version_id")
          .where("candidate.user_id", "=", accountId)
          .where("candidate.safety_case_id", "=", safetyCaseId)
          .where("candidate.expires_at", ">", now)
          .where("source.user_id", "=", accountId)
          .where("source.payload_ciphertext", "is not", null);
        if (!returnedReview)
          candidateQuery = candidateQuery.where("source.expires_at", ">", now);
        const candidate = await candidateQuery.executeTakeFirst();
        if (!candidate) return false;
      }
      await transaction
        .updateTable("safety.safety_cases")
        .set({
          resolution_status: status,
          resolution_command_id: commandId,
          // Risk evidence contains slices of the original source, so it must
          // be removed atomically with that source, not kept in review holds.
          ...(clearPayload ? { risk_spans: "[]" } : {}),
        })
        .where("user_id", "=", accountId)
        .where("safety_case_id", "=", safetyCaseId)
        .where("resolution_status", "=", existing.resolution_status)
        .executeTakeFirstOrThrow();
      await transaction
        .deleteFrom("safety.deidentified_versions")
        .where("user_id", "=", accountId)
        .where("safety_case_id", "=", safetyCaseId)
        .execute();
      if (clearPayload)
        await transaction
          .updateTable("safety.check_operations")
          .set({
            payload_ciphertext: null,
            payload_algorithm: null,
            payload_key_version: null,
          })
          .where("user_id", "=", accountId)
          .where("operation_id", "=", existing.operation_id)
          .execute();
      return true;
    });
  }
}

const parseJsonArray = <T>(value: unknown): readonly T[] => {
  const parsed: unknown = typeof value === "string" ? JSON.parse(value) : value;
  if (!Array.isArray(parsed)) throw new Error("Invalid safety JSON payload");
  return parsed as readonly T[];
};
