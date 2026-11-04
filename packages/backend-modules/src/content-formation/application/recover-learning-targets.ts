import { createHash } from "node:crypto";
import type { ContentGenerationSettingsReader } from "./generate-english-candidates.js";
import {
  InvalidLearningTargetInputError,
  InvalidLearningTargetOutputError,
  type ExtractLearningTargets,
  type ExtractLearningTargetsCommand,
} from "./extract-learning-targets.js";

export type LearningTargetRecoveryEvent = Readonly<{
  phase: "REPAIR_REQUESTED" | "SAFE_FALLBACK_USED";
  errorName: string;
  errorMessage: string;
}>;

/**
 * Structured output guarantees shape, not relationships between source spans,
 * meanings and knowledge references. Keep strict validation as the authority,
 * but give semantic output one bounded, independently idempotent repair attempt.
 * If both generated deliveries are semantically unusable, sentence learning can
 * still continue with the client's deterministic word picker and no invented
 * target metadata.
 */
export class RecoverLearningTargets {
  public constructor(
    private readonly strict: Pick<ExtractLearningTargets, "execute">,
    private readonly settings: ContentGenerationSettingsReader,
    private readonly report: (event: LearningTargetRecoveryEvent) => void =
      () => undefined,
  ) {}

  public async execute(command: ExtractLearningTargetsCommand) {
    try {
      return await this.strict.execute(command);
    } catch (error) {
      if (error instanceof InvalidLearningTargetInputError) throw error;
      if (!recoverable(error)) throw error;
      this.report(event("REPAIR_REQUESTED", error));
    }

    try {
      return await this.strict.execute({
        ...command,
        commandId: repairCommandId(command.commandId),
      });
    } catch (error) {
      if (error instanceof InvalidLearningTargetInputError) throw error;
      if (!recoverable(error) || command.requestedExpression) throw error;
      this.report(event("SAFE_FALLBACK_USED", error));
    }

    const current = await this.settings.read(command.accountId);
    return {
      targets: [],
      queryableExpressions: [],
      learningScopes: [
        current.basicVocabulary,
        ...current.purposeVocabularies,
      ],
    };
  }
}

const recoverable = (error: unknown): boolean =>
  error instanceof InvalidLearningTargetOutputError;

const event = (
  phase: LearningTargetRecoveryEvent["phase"],
  error: unknown,
): LearningTargetRecoveryEvent => ({
  phase,
  errorName: error instanceof Error ? error.name : "UnknownError",
  errorMessage: error instanceof Error ? error.message : "Unknown error",
});

/** Stable UUID-shaped key: retries replay the repair delivery instead of paying
 * for an unbounded sequence of new model calls. */
const repairCommandId = (commandId: string): string => {
  const hex = createHash("sha256")
    .update(`learning-target-repair-v1:${commandId}`)
    .digest("hex")
    .slice(0, 32)
    .split("");
  hex[12] = "4";
  hex[16] = ["8", "9", "a", "b"][Number.parseInt(hex[16]!, 16) % 4]!;
  return `${hex.slice(0, 8).join("")}-${hex.slice(8, 12).join("")}-${hex.slice(12, 16).join("")}-${hex.slice(16, 20).join("")}-${hex.slice(20).join("")}`;
};
