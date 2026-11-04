import type { AiResultDeliveryReader } from "../../ai/public.js";
import type { ExistingAiChargeReader } from "../../billing/public.js";
import { validateLearningExplanationOutput } from "./generate-learning-explanation.js";
export type RecoverLearningExplanationCommand = Readonly<{
  accountId: string;
  commandId: string;
  kind: "SENTENCE" | "EXPRESSION";
}>;
export type ExplanationRecoveryStatus =
  | "NO_DELIVERY"
  | "NO_DELIVERY_WITH_ATTEMPT"
  | "LEGACY_UNAVAILABLE"
  | "PROCESSING"
  | "FAILED"
  | "EXPIRED"
  | "INVALID_DELIVERY"
  | "BILLING_UNVERIFIED"
  | "PENDING_SETTLEMENT"
  | "RELEASED"
  | "RECONCILIATION_REQUIRED"
  | "INVALID_RESULT";
export type ExplanationRecoveryResult =
  | { status: ExplanationRecoveryStatus }
  | {
      status: "COMPLETED";
      commandId: string;
      provenance: "EXISTING_DELIVERY";
      result: unknown;
    };
/** No provider/settings/charging capability is available to this use case. */
export class RecoverLearningExplanation {
  public constructor(
    private readonly deliveries: AiResultDeliveryReader,
    private readonly charges: ExistingAiChargeReader,
  ) {}
  public async execute(
    command: RecoverLearningExplanationCommand,
  ): Promise<ExplanationRecoveryResult> {
    if (
      !command.accountId.trim() ||
      !command.commandId.trim() ||
      command.commandId.length > 128 ||
      !["SENTENCE", "EXPRESSION"].includes(command.kind)
    )
      throw new Error("Invalid explanation recovery input");
    const attempts = await this.charges.readOperation(
      command.accountId,
      command.commandId,
    );
    const stored = await this.deliveries.readExisting({
      accountId: command.accountId,
      commandId: command.commandId,
      task:
        command.kind === "EXPRESSION"
          ? "WORD_EXPLANATION_V1"
          : "SENTENCE_EXPLANATION_V1",
      historicalAttemptIds: attempts.map((value) => value.providerAttemptId),
    });
    if (stored.status === "NO_DELIVERY")
      return {
        status: attempts.length ? "NO_DELIVERY_WITH_ATTEMPT" : "NO_DELIVERY",
      };
    if (stored.status !== "COMPLETED") return stored;
    const envelope = record(stored.result);
    const receipt = record(envelope?.receipt);
    const matching = attempts.filter(
      (value) => value.providerAttemptId === stored.providerAttemptId,
    );
    if (
      !receipt ||
      matching.length !== 1 ||
      receipt.providerAttemptId !== stored.providerAttemptId ||
      typeof receipt.modelSource !== "string" ||
      !receipt.modelSource.trim() ||
      typeof receipt.providerCostMicroCny !== "number" ||
      !Number.isSafeInteger(receipt.providerCostMicroCny) ||
      receipt.providerCostMicroCny <= 0
    )
      return { status: "BILLING_UNVERIFIED" };
    const attempt = matching[0]!;
    if (attempt.status === "RESERVED") return { status: "PENDING_SETTLEMENT" };
    if (
      attempt.status === "RELEASED" ||
      attempt.status === "RECONCILIATION_REQUIRED"
    )
      return { status: attempt.status };
    if (
      attempt.status !== "SETTLED" ||
      attempt.modelSource !== receipt.modelSource ||
      attempt.providerCostMicroCny !== receipt.providerCostMicroCny
    )
      return { status: "BILLING_UNVERIFIED" };
    const output = record(envelope?.output);
    if (
      !output ||
      output.kind !== command.kind ||
      (command.kind === "EXPRESSION" &&
        (typeof output.expressionId !== "string" ||
          !output.expressionId.trim() ||
          output.expressionId.length > 128))
    )
      return { status: "INVALID_RESULT" };
    try {
      const result = validateLearningExplanationOutput(
        output,
        command.kind === "EXPRESSION"
          ? {
              requestedExpression: {
                expressionId: output.expressionId as string,
              },
            }
          : {},
        true,
      );
      return {
        status: "COMPLETED",
        commandId: command.commandId,
        provenance: "EXISTING_DELIVERY",
        result,
      };
    } catch {
      return { status: "INVALID_RESULT" };
    }
  }
}
const record = (value: unknown): Record<string, unknown> | null =>
  value !== null && typeof value === "object" && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : null;
