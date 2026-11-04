import { createHash, timingSafeEqual } from "node:crypto";
import type { ColumnType, Generated, JSONColumnType, Kysely } from "kysely";

import type {
  CheckContentSafetyCommand,
  ContentSafetyGateway,
  ContentSafetyGatewayResult,
} from "../application/check-content-safety.js";

type Timestamp = ColumnType<Date, Date | undefined, Date>;
type RequiredTimestamp = ColumnType<Date, Date, Date>;

interface SafetyCheckOperationsTable {
  operation_id: string;
  user_id: string;
  command_id: string;
  request_digest: Uint8Array;
  source_type: "DIARY" | "RECORDING" | "AI_SCENE";
  content_shape: "ARTICLE" | "DIALOGUE";
  payload_ciphertext: Uint8Array | null;
  payload_algorithm: string | null;
  payload_key_version: number | null;
  status: Generated<"PROCESSING" | "PASSED" | "BLOCKED" | "FAILED">;
  policy_version: string | null;
  provider_operation_id: string | null;
  safety_case_id: string | null;
  expires_at: RequiredTimestamp;
  row_version: Generated<number>;
  created_at: Timestamp;
  updated_at: Timestamp;
}

export type SafetyRiskSpan = Readonly<{
  startCodePoint: number;
  endCodePoint: number;
  text: string;
  reasonCode: string;
}>;

interface SafetyCasesTable {
  safety_case_id: string;
  operation_id: string;
  user_id: string;
  policy_version: string;
  reason_code: string;
  user_reason_zh: string;
  risk_spans: JSONColumnType<readonly SafetyRiskSpan[], string, never>;
  confidence: number;
  context_considered: boolean;
  row_version: Generated<number>;
  created_at: Timestamp;
  updated_at: Timestamp;
}

export interface ContentSafetyDatabase {
  "safety.check_operations": SafetyCheckOperationsTable;
  "safety.safety_cases": SafetyCasesTable;
}

export interface ContentSafetyPayloadProtector {
  protect(text: string): Promise<
    Readonly<{
      ciphertext: Uint8Array;
      algorithm: string;
      keyVersion: number;
    }>
  >;
}

export interface ContentSafetyPayloadCipher extends ContentSafetyPayloadProtector {
  unprotect(
    input: Readonly<{
      ciphertext: Uint8Array;
      algorithm: string;
      keyVersion: number;
    }>,
  ): Promise<string>;
}

export type ContentSafetyProviderOutcome =
  | Readonly<{
      decision: "PASS";
      policyVersion: string;
      providerOperationId: string;
      contextConsidered: true;
    }>
  | Readonly<{
      decision: "BLOCK";
      policyVersion: string;
      providerOperationId: string;
      reasonCode: string;
      userReasonZh: string;
      riskSpans: readonly SafetyRiskSpan[];
      confidence: number;
      contextConsidered: true;
    }>;

export interface ContentSafetyProvider {
  check(
    input: Readonly<{
      accountId: string;
      operationId: string;
      text: string;
      sourceType: CheckContentSafetyCommand["sourceType"];
      contentShape: CheckContentSafetyCommand["contentShape"];
    }>,
  ): Promise<ContentSafetyProviderOutcome>;
}

export type KyselyContentSafetyDependencies = Readonly<{
  clock: { now(): Date };
  identifiers: { next(): string };
  payloadProtector: ContentSafetyPayloadProtector;
  provider: ContentSafetyProvider;
  payloadTtlMilliseconds: number;
}>;

export class SafetyIdempotencyConflictError extends Error {}
export class SafetyCheckInProgressError extends Error {}

export class KyselyContentSafetyGateway implements ContentSafetyGateway {
  public constructor(
    private readonly database: Kysely<ContentSafetyDatabase>,
    private readonly dependencies: KyselyContentSafetyDependencies,
  ) {
    if (
      !Number.isInteger(dependencies.payloadTtlMilliseconds) ||
      dependencies.payloadTtlMilliseconds <= 0
    ) {
      throw new Error("Safety payload TTL must be a positive integer");
    }
  }

  public async check(
    command: CheckContentSafetyCommand,
  ): Promise<ContentSafetyGatewayResult> {
    const digest = requestDigest(command);
    const existing = await this.find(command.accountId, command.commandId);
    if (existing) {
      const replay = replayResult(existing, digest);
      if (replay) return replay;
      if (existing.status === "PROCESSING") {
        throw new SafetyCheckInProgressError();
      }
    }

    const now = this.dependencies.clock.now();
    const operationId =
      existing?.operation_id ?? this.dependencies.identifiers.next();
    if (!existing) {
      const protectedPayload = await this.dependencies.payloadProtector.protect(
        command.text,
      );
      const inserted = await this.database
        .insertInto("safety.check_operations")
        .values({
          operation_id: operationId,
          user_id: command.accountId,
          command_id: command.commandId,
          request_digest: digest,
          source_type: command.sourceType,
          content_shape: command.contentShape,
          payload_ciphertext: protectedPayload.ciphertext,
          payload_algorithm: protectedPayload.algorithm,
          payload_key_version: protectedPayload.keyVersion,
          expires_at: new Date(
            now.getTime() + this.dependencies.payloadTtlMilliseconds,
          ),
          created_at: now,
          updated_at: now,
        })
        .onConflict((conflict) =>
          conflict.columns(["user_id", "command_id"]).doNothing(),
        )
        .returning("operation_id")
        .executeTakeFirst();
      if (!inserted) {
        const concurrent = await this.find(
          command.accountId,
          command.commandId,
        );
        if (!concurrent) throw new SafetyCheckInProgressError();
        const replay = replayResult(concurrent, digest);
        if (replay) return replay;
        throw new SafetyCheckInProgressError();
      }
    } else {
      await this.database
        .updateTable("safety.check_operations")
        .set({ status: "PROCESSING", updated_at: now })
        .where("operation_id", "=", operationId)
        .where("status", "=", "FAILED")
        .execute();
    }

    try {
      const outcome = await this.dependencies.provider.check({
        accountId: command.accountId,
        operationId,
        text: command.text,
        sourceType: command.sourceType,
        contentShape: command.contentShape,
      });
      return outcome.decision === "PASS"
        ? await this.completePass(operationId, outcome, now)
        : await this.completeBlock(
            command.accountId,
            operationId,
            outcome,
            now,
          );
    } catch (error) {
      await this.database
        .updateTable("safety.check_operations")
        .set({ status: "FAILED", updated_at: this.dependencies.clock.now() })
        .where("operation_id", "=", operationId)
        .where("status", "=", "PROCESSING")
        .execute();
      throw error;
    }
  }

  private async completePass(
    operationId: string,
    outcome: Extract<ContentSafetyProviderOutcome, { decision: "PASS" }>,
    completedAt: Date,
  ): Promise<ContentSafetyGatewayResult> {
    await this.database
      .updateTable("safety.check_operations")
      .set({
        status: "PASSED",
        policy_version: outcome.policyVersion,
        provider_operation_id: outcome.providerOperationId,
        payload_ciphertext: null,
        payload_algorithm: null,
        payload_key_version: null,
        updated_at: completedAt,
      })
      .where("operation_id", "=", operationId)
      .executeTakeFirstOrThrow();
    return { decision: "PASS" };
  }

  private async completeBlock(
    accountId: string,
    operationId: string,
    outcome: Extract<ContentSafetyProviderOutcome, { decision: "BLOCK" }>,
    completedAt: Date,
  ): Promise<ContentSafetyGatewayResult> {
    const safetyCaseId = this.dependencies.identifiers.next();
    await this.database.transaction().execute(async (transaction) => {
      await transaction
        .insertInto("safety.safety_cases")
        .values({
          safety_case_id: safetyCaseId,
          operation_id: operationId,
          user_id: accountId,
          policy_version: outcome.policyVersion,
          reason_code: outcome.reasonCode,
          user_reason_zh: outcome.userReasonZh,
          risk_spans: JSON.stringify(outcome.riskSpans),
          confidence: outcome.confidence,
          context_considered: outcome.contextConsidered,
          created_at: completedAt,
          updated_at: completedAt,
        })
        .execute();
      await transaction
        .updateTable("safety.check_operations")
        .set({
          status: "BLOCKED",
          policy_version: outcome.policyVersion,
          provider_operation_id: outcome.providerOperationId,
          safety_case_id: safetyCaseId,
          updated_at: completedAt,
        })
        .where("operation_id", "=", operationId)
        .executeTakeFirstOrThrow();
    });
    return { decision: "BLOCK", safetyCaseId };
  }

  private find(accountId: string, commandId: string) {
    return this.database
      .selectFrom("safety.check_operations")
      .select(["operation_id", "request_digest", "status", "safety_case_id"])
      .where("user_id", "=", accountId)
      .where("command_id", "=", commandId)
      .executeTakeFirst();
  }
}

const requestDigest = (command: CheckContentSafetyCommand): Uint8Array =>
  new Uint8Array(
    createHash("sha256")
      .update(
        JSON.stringify({
          text: command.text,
          sourceType: command.sourceType,
          contentShape: command.contentShape,
        }),
        "utf8",
      )
      .digest(),
  );

const replayResult = (
  existing: Readonly<{
    request_digest: Uint8Array;
    status: "PROCESSING" | "PASSED" | "BLOCKED" | "FAILED";
    safety_case_id: string | null;
  }>,
  digest: Uint8Array,
): ContentSafetyGatewayResult | null => {
  if (
    existing.request_digest.byteLength !== digest.byteLength ||
    !timingSafeEqual(existing.request_digest, digest)
  ) {
    throw new SafetyIdempotencyConflictError();
  }
  if (existing.status === "PASSED") return { decision: "PASS" };
  if (existing.status === "BLOCKED" && existing.safety_case_id) {
    return { decision: "BLOCK", safetyCaseId: existing.safety_case_id };
  }
  return null;
};
