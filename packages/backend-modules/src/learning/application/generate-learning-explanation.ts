import type {
  ContentGenerationSettings,
  ContentGenerationSettingsReader,
} from "../../content-formation/application/generate-english-candidates.js";
import {
  validLockedExplanationContext,
  validExplanationExpression,
  lockedMeaningForExpression,
  type LockedExplanationTarget,
  type PositionalExplanationExpression,
} from "./locked-explanation-context.js";

export type LearningExplanationCommand = Readonly<{
  accountId: string;
  chapter: Readonly<{
    chapterId: string;
    sentences: readonly Readonly<{
      sentenceId: string;
      textEn: string;
      textZh: string;
      isFocus: boolean;
    }>[];
  }>;
  focusSentenceId: string;
  round: number;
  existingBaseExplanationZh?: string;
  lockedTargets?: readonly LockedExplanationTarget[] | undefined;
  requestedExpression?: PositionalExplanationExpression;
  commandId: string;
}>;
export type LearningExplanationProviderCommand = LearningExplanationCommand &
  Readonly<{ settings: ContentGenerationSettings }>;
export interface LearningExplanationProvider {
  generate(command: LearningExplanationProviderCommand): Promise<unknown>;
}
export class InvalidLearningExplanationInputError extends Error {}
export class GenerateLearningExplanation {
  public constructor(
    private readonly settings: ContentGenerationSettingsReader,
    private readonly provider: LearningExplanationProvider,
  ) {}
  public async execute(command: LearningExplanationCommand) {
    const focus = command.chapter.sentences.find(
      (sentence) =>
        sentence.sentenceId === command.focusSentenceId && sentence.isFocus,
    );
    if (
      !focus ||
      command.chapter.sentences.length > 4 ||
      command.round < 1 ||
      command.round > 3 ||
      (command.lockedTargets !== undefined &&
        new Set(
          command.chapter.sentences.map((sentence) => sentence.sentenceId),
        ).size !== command.chapter.sentences.length) ||
      (command.round > 1 && !command.existingBaseExplanationZh) ||
      !validLockedExplanationContext(command.lockedTargets, focus.textEn) ||
      !validExplanationExpression(
        command.requestedExpression,
        focus.textEn,
        command.lockedTargets !== undefined,
      )
    )
      throw new InvalidLearningExplanationInputError(
        "Invalid learning explanation input",
      );
    const output = await this.provider.generate({
      ...command,
      settings: await this.settings.read(command.accountId),
    });
    return validateLearningExplanationOutput(output, command);
  }
}

/** Pure delivery validation shared by generation and zero-provider recovery. */
export function validateLearningExplanationOutput(
  output: unknown,
  command: Pick<LearningExplanationCommand, "lockedTargets"> & {
    requestedExpression?: Pick<
      PositionalExplanationExpression,
      "expressionId" | "startUtf16" | "endUtf16"
    >;
  },
  preserveRawMeaning = false,
) {
  if (
    !record(output) ||
    typeof output.explanationZh !== "string" ||
    !output.explanationZh.trim()
  )
    throw new Error("Invalid learning explanation output");
  if (command.requestedExpression) {
    if (
      output.kind !== "EXPRESSION" ||
      output.expressionId !== command.requestedExpression.expressionId ||
      (output.phonetic !== null &&
        (typeof output.phonetic !== "string" || output.phonetic.length === 0))
    )
      throw new Error("Invalid expression explanation output");
    let expressionDetails:
      | {
          contextualMeaningZh: string;
          usageZh: string;
          expressionHintZh: string | null;
        }
      | undefined;
    const { contextualMeaningZh, usageZh, expressionHintZh } = output;
    // Historical delivered results can lack the whole group. Never infer it
    // by splitting their full explanation or trigger a second provider call.
    if (
      [contextualMeaningZh, usageZh, expressionHintZh].some(
        (value) => value != null,
      )
    ) {
      if (
        typeof contextualMeaningZh !== "string" ||
        !contextualMeaningZh.trim() ||
        typeof usageZh !== "string" ||
        !usageZh.trim() ||
        (expressionHintZh !== null &&
          (typeof expressionHintZh !== "string" || !expressionHintZh.trim()))
      )
        throw new Error("Invalid expression detail output");
      expressionDetails = {
        contextualMeaningZh:
          command.lockedTargets !== undefined || preserveRawMeaning
            ? contextualMeaningZh
            : contextualMeaningZh.trim(),
        usageZh: usageZh.trim(),
        expressionHintZh:
          expressionHintZh === null ? null : expressionHintZh.trim(),
      };
    }
    if (command.lockedTargets !== undefined) {
      const lockedMeaning = lockedMeaningForExpression(
        command.lockedTargets,
        command.requestedExpression,
      );
      if (
        !expressionDetails ||
        (lockedMeaning !== undefined &&
          expressionDetails.contextualMeaningZh !== lockedMeaning)
      )
        throw new Error("Invalid locked expression meaning output");
    }
    let naturalExample: { textEn: string; textZh: string } | undefined;
    if (output.naturalExample != null) {
      const example = output.naturalExample;
      if (
        !record(example) ||
        typeof example.textEn !== "string" ||
        !example.textEn.trim() ||
        typeof example.textZh !== "string" ||
        !example.textZh.trim()
      )
        throw new Error("Invalid expression example output");
      naturalExample = {
        textEn: example.textEn.trim(),
        textZh: example.textZh.trim(),
      };
    }
    return {
      kind: "EXPRESSION" as const,
      expressionId: output.expressionId,
      explanationZh: output.explanationZh,
      phonetic: output.phonetic,
      ...expressionDetails,
      ...(naturalExample ? { naturalExample } : {}),
    };
  }
  if (output.kind !== "SENTENCE")
    throw new Error("Invalid sentence explanation output");
  return { kind: "SENTENCE" as const, explanationZh: output.explanationZh };
}
const record = (value: unknown): value is Record<string, unknown> =>
  typeof value === "object" && value !== null;
