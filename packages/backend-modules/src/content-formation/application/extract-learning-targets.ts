import { createHash } from "node:crypto";
import {
  learningTargetFitsVocabularyBoundary,
  sourceWordAtOffset,
  targetEvidenceMatchesSentence,
  wordTargetMatchesSurface,
  type TargetSourceEvidence,
} from "./target-source-evidence.js";
import type {
  ContentGenerationSettings,
  ContentGenerationSettingsReader,
  KnownKnowledgeCandidate,
} from "./generate-english-candidates.js";
import {
  validKnownTargetInput,
  knownTargetsMatchSentences,
} from "./target-known-knowledge.js";
import {
  relatedVocabularyMatchesSentences,
  type RelatedVocabularyItem,
} from "./target-related-vocabulary.js";
import {
  validAcceptedVocabularyInput,
  targetMatchesAcceptedVocabulary,
  type RequestedTargetExpression,
} from "./target-accepted-vocabulary.js";

export type ConfirmedLearningSentence = Readonly<{
  unitId: string;
  textZh: string;
  textEn: string;
  isFocus: boolean;
  reusedKnowledge?: readonly KnownKnowledgeCandidate[] | undefined;
}>;
export type ConfirmedLearningChapter = Readonly<{
  chapterId: string;
  sentences: readonly ConfirmedLearningSentence[];
}>;
/** A selection aid, never source evidence, a fixed answer, or a formal knowledge example. */
export type LearningTargetCandidateExample = Readonly<{
  textEn: string;
  textZh: string;
}>;
export const isValidLearningTargetCandidateExample = (
  value: unknown,
): value is LearningTargetCandidateExample => {
  if (value === null || typeof value !== "object" || Array.isArray(value))
    return false;
  const example = value as Record<string, unknown>;
  return (
    Object.keys(example).length === 2 &&
    typeof example.textEn === "string" &&
    example.textEn.trim().length > 0 &&
    typeof example.textZh === "string" &&
    example.textZh.trim().length > 0
  );
};
export type LearningTargetCandidate = Readonly<{
  targetId: string;
  unitId: string;
  display: string;
  explanationZh: string;
  type: "WORD" | "FIXED_EXPRESSION" | "PATTERN";
  structureParts: readonly string[];
  relatedVocabulary: readonly string[];
  relatedVocabularyItems?: readonly RelatedVocabularyItem[] | null | undefined;
  relatedVocabularyItemRefs?: readonly string[] | null | undefined;
  selectedByDefault: true;
  /** Missing only for older stored deliveries; current providers must supply it. */
  sourceEvidence?: TargetSourceEvidence | null;
  reusedKnowledgeRef?: string | null | undefined;
  /** Missing/null is accepted only as an older delivered candidate, never fabricated. */
  candidateExample?: LearningTargetCandidateExample | null | undefined;
}>;
export type QueryableExpressionCandidate = Readonly<{
  expressionId: string;
  unitId: string;
  display: string;
  /** Context-only preview; selecting still creates exactly one validated target. */
  explanationZh?: string | undefined;
}>;
export type LearningTargetProviderCommand = Readonly<{
  accountId: string;
  chapters: readonly ConfirmedLearningChapter[];
  requestedExpression?: RequestedTargetExpression;
  settings: ContentGenerationSettings;
  commandId: string;
}>;
export interface LearningTargetProvider {
  extract(command: LearningTargetProviderCommand): Promise<
    Readonly<{
      targets: readonly LearningTargetCandidate[];
      queryableExpressions: readonly QueryableExpressionCandidate[];
    }>
  >;
}
export type ExtractLearningTargetsCommand = Omit<
  LearningTargetProviderCommand,
  "settings"
>;

export class InvalidLearningTargetInputError extends Error {}
export class InvalidLearningTargetOutputError extends Error {}

export class ExtractLearningTargets {
  public constructor(
    private readonly settings: ContentGenerationSettingsReader,
    private readonly provider: LearningTargetProvider,
  ) {}
  public async execute(command: ExtractLearningTargetsCommand) {
    const focusIds = new Set(
      command.chapters.flatMap((chapter) =>
        chapter.sentences
          .filter((sentence) => sentence.isFocus)
          .map((sentence) => sentence.unitId),
      ),
    );
    const allIds = command.chapters.flatMap((chapter) =>
      chapter.sentences.map((sentence) => sentence.unitId),
    );
    if (
      focusIds.size === 0 ||
      !validKnownTargetInput(command.chapters) ||
      new Set(allIds).size !== allIds.length ||
      command.chapters.some(
        (chapter) =>
          chapter.sentences.length < 1 || chapter.sentences.length > 4,
      ) ||
      (command.requestedExpression &&
        !focusIds.has(command.requestedExpression.unitId))
    )
      throw new InvalidLearningTargetInputError(
        "Invalid learning target input",
      );
    const requested = command.requestedExpression;
    if (
      !validAcceptedVocabularyInput(
        requested,
        command.chapters
          .flatMap((chapter) => chapter.sentences)
          .find((sentence) => sentence.unitId === requested?.unitId)?.textEn,
      )
    )
      throw new InvalidLearningTargetInputError("Invalid accepted vocabulary");
    const wordReferenceMatches = (expression: {
      unitId: string;
      expressionId: string;
      display?: string | undefined;
    }) => {
      if (!expression.expressionId.startsWith("word:")) return true;
      if (
        !/^word:(0|[1-9]\d*)$/u.test(expression.expressionId) ||
        expression.display === undefined
      )
        return false;
      const source = command.chapters
        .flatMap((chapter) => chapter.sentences)
        .find((sentence) => sentence.unitId === expression.unitId)?.textEn;
      const offset = Number(expression.expressionId.slice(5));
      if (source === undefined || !Number.isSafeInteger(offset)) return false;
      const word = sourceWordAtOffset(source, offset);
      return (
        word !== undefined &&
        word.toLowerCase() === expression.display.trim().toLowerCase()
      );
    };
    if (requested && !wordReferenceMatches(requested))
      throw new InvalidLearningTargetInputError("Invalid requested expression");
    if (requested?.display !== undefined) {
      const sentence = command.chapters
        .flatMap((chapter) => chapter.sentences)
        .find((sentence) => sentence.unitId === requested.unitId);
      if (
        !requested.display.trim() ||
        !sentence?.textEn
          .toLowerCase()
          .includes(requested.display.toLowerCase())
      )
        throw new InvalidLearningTargetInputError(
          "Invalid requested expression",
        );
    }
    const settings = await this.settings.read(command.accountId);
    const output = await this.provider.extract({
      ...command,
      settings,
    });
    const targetIds = output.targets.map((target) => target.targetId);
    const expressionIds = output.queryableExpressions.map(
      (item) => item.expressionId,
    );
    if (
      !relatedVocabularyMatchesSentences(command.chapters, output.targets) ||
      !knownTargetsMatchSentences(
        command.chapters,
        output.targets,
        requested !== undefined,
      ) ||
      targetIds.some((id) => !id) ||
      new Set(targetIds).size !== targetIds.length ||
      new Set(expressionIds).size !== expressionIds.length ||
      output.targets.some((target) => {
        const source = command.chapters
          .flatMap((chapter) => chapter.sentences)
          .find((sentence) => sentence.unitId === target.unitId)?.textEn;
        return (
          !focusIds.has(target.unitId) ||
          source === undefined ||
          !learningTargetFitsVocabularyBoundary(target, source) ||
          !targetMatchesAcceptedVocabulary(target, requested) ||
          target.selectedByDefault !== true ||
          !target.display.trim() ||
          !target.explanationZh.trim() ||
          (target.candidateExample != null &&
            !isValidLearningTargetCandidateExample(target.candidateExample)) ||
          (target.sourceEvidence != null &&
            (!targetEvidenceMatchesSentence(
              target.sourceEvidence,
              command.chapters
                .flatMap((chapter) => chapter.sentences)
                .find((sentence) => sentence.unitId === target.unitId)
                ?.textEn ?? "",
            ) ||
              (target.type === "WORD" &&
                !wordTargetMatchesSurface(
                  target.display,
                  target.sourceEvidence.surfaceForm,
                )))) ||
          target.structureParts.some((part) => !part.trim()) ||
          (target.type === "PATTERN" && target.structureParts.length === 0)
        );
      }) ||
      output.queryableExpressions.some(
        (item) =>
          !focusIds.has(item.unitId) ||
          !item.display.trim() ||
          (item.explanationZh !== undefined && !item.explanationZh.trim()) ||
          !wordReferenceMatches(item),
      )
    )
      throw new InvalidLearningTargetOutputError(
        "Invalid learning target output",
      );
    if (command.requestedExpression && output.targets.length !== 1)
      throw new Error("Invalid requested expression result");
    if (
      requested &&
      output.targets.some(
        (target) =>
          target.unitId !== requested.unitId ||
          (requested.expressionId.startsWith("word:") &&
            target.sourceEvidence != null &&
            (target.sourceEvidence.startUtf16 !==
              Number(requested.expressionId.slice(5)) ||
              target.sourceEvidence.surfaceForm.toLowerCase() !==
                requested.display?.trim().toLowerCase())) ||
          (requested.display !== undefined &&
            target.display.trim().toLowerCase() !==
              requested.display.trim().toLowerCase()),
      )
    )
      throw new Error("Invalid requested expression result");
    return {
      ...output,
      targets: requested
        ? output.targets.map((target) => ({
            ...target,
            targetId: `added-${createHash("sha256")
              .update(
                JSON.stringify([
                  command.accountId,
                  command.commandId,
                  requested.unitId,
                  requested.expressionId,
                ]),
              )
              .digest("hex")
              .slice(0, 32)}`,
          }))
        : output.targets,
      learningScopes: [
        settings.basicVocabulary,
        ...settings.purposeVocabularies,
      ],
    };
  }
}
