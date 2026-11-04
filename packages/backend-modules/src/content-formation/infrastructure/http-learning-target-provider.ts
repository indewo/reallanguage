import {
  InvalidLearningTargetOutputError,
  isValidLearningTargetCandidateExample,
  type LearningTargetCandidateExample,
  type LearningTargetProvider,
  type LearningTargetProviderCommand,
} from "../application/extract-learning-targets.js";
import {
  learningTargetFitsVocabularyBoundary,
  parseTargetSourceEvidence,
  sourceWordAtOffset,
  targetEvidenceMatchesSentence,
  wordTargetMatchesSurface,
  type TargetSourceEvidence,
} from "../application/target-source-evidence.js";
import {
  validRelatedVocabularyGroup,
  type RelatedVocabularyItem,
} from "../application/target-related-vocabulary.js";
import {
  validAcceptedVocabularyInput,
  targetMatchesAcceptedVocabulary,
} from "../application/target-accepted-vocabulary.js";
export class HttpLearningTargetProvider implements LearningTargetProvider {
  public constructor(
    private readonly config: Readonly<{ endpoint: string; apiKey: string }>,
    private readonly fetchImplementation: typeof fetch = fetch,
  ) {}
  public async extract(command: LearningTargetProviderCommand) {
    if (
      !validAcceptedVocabularyInput(
        command.requestedExpression,
        command.chapters
          .flatMap((chapter) => chapter.sentences)
          .find(
            (sentence) =>
              sentence.unitId === command.requestedExpression?.unitId,
          )?.textEn,
      )
    )
      throw new Error("Invalid accepted vocabulary");
    const response = await this.fetchImplementation(this.config.endpoint, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${this.config.apiKey}`,
        "Content-Type": "application/json",
        "Idempotency-Key": command.commandId,
        "X-RealLanguage-Account-Id": command.accountId,
      },
      body: JSON.stringify({
        task: "LEARNING_TARGET_EXTRACTION_V1",
        input: {
          chapters: command.chapters,
          settings: command.settings,
          requestedExpression: command.requestedExpression ?? null,
        },
      }),
    });
    if (!response.ok)
      throw new Error("Learning target provider request failed");
    try {
      const value: unknown = await response.json();
      if (
        !record(value) ||
        !Array.isArray(value.targets) ||
        !Array.isArray(value.queryableExpressions)
      )
        throw new InvalidLearningTargetProviderOutputError(
          "Invalid learning target provider response",
        );
      return {
        targets: value.targets.map((item) => {
          const target = alignTargetToSource(item, command);
          if (
            !targetMatchesAcceptedVocabulary(
              target,
              command.requestedExpression,
            )
          )
            throw new InvalidLearningTargetProviderOutputError(
              "Invalid accepted vocabulary output",
            );
          return target;
        }),
        queryableExpressions:
          value.queryableExpressions.map(parseExpression),
      };
    } catch (error) {
      if (error instanceof InvalidLearningTargetProviderOutputError)
        throw error;
      throw new InvalidLearningTargetProviderOutputError(
        error instanceof Error
          ? error.message
          : "Invalid learning target provider response",
      );
    }
  }
}

export class InvalidLearningTargetProviderOutputError extends InvalidLearningTargetOutputError {}
const alignTargetToSource = (
  value: unknown,
  command: LearningTargetProviderCommand,
) => {
  // Parse every evidence field before correcting only the provider's position.
  let target = parseTarget(value);
  if (
    target.type === "WORD" &&
    !wordTargetMatchesSurface(target.display, target.sourceEvidence.surfaceForm)
  )
    throw new InvalidLearningTargetProviderOutputError(
      "Invalid learning target candidate",
    );
  const sentences = command.chapters
    .flatMap((chapter) => chapter.sentences)
    .filter((sentence) => sentence.unitId === target.unitId);
  const sentence = sentences[0];
  const requested = command.requestedExpression;
  if (
    sentences.length !== 1 ||
    !sentence?.isFocus ||
    (requested && requested.unitId !== target.unitId)
  )
    throw new InvalidLearningTargetProviderOutputError(
      "Invalid learning target candidate",
    );
  const source = sentence.textEn;
  if (Array.isArray(target.relatedVocabularyItems)) {
    target = {
      ...target,
      relatedVocabularyItems: target.relatedVocabularyItems.map(
        (item): RelatedVocabularyItem => {
          if (
            !record(item) ||
            Object.keys(item).length !== 6 ||
            ![
              "itemRef",
              "unitId",
              "display",
              "senseZh",
              "unitType",
              "sourceEvidence",
            ].every((key) => key in item) ||
            typeof item.itemRef !== "string" ||
            typeof item.unitId !== "string" ||
            typeof item.display !== "string" ||
            typeof item.senseZh !== "string" ||
            (item.unitType !== "WORD" &&
              item.unitType !== "FIXED_EXPRESSION") ||
            item.unitId !== target.unitId ||
            !record(item.sourceEvidence)
          )
            throw new Error("Invalid learning target candidate");
          const aligned = alignSourceEvidence(
            parseProviderSourceEvidence(item.sourceEvidence),
            source,
          );
          return {
            itemRef: item.itemRef,
            unitId: item.unitId,
            display: item.display,
            senseZh: item.senseZh,
            unitType: item.unitType,
            sourceEvidence: aligned,
          };
        },
      ),
    };
  }
  if (!validRelatedVocabularyGroup(target, source))
    throw new Error("Invalid learning target candidate");
  const evidence = target.sourceEvidence;
  const at = (startUtf16: number): TargetSourceEvidence => ({
    ...evidence,
    startUtf16,
    endUtf16: startUtf16 + evidence.surfaceForm.length,
  });
  if (requested?.expressionId.startsWith("word:")) {
    const start = Number(requested.expressionId.slice(5));
    const word = sourceWordAtOffset(source, start);
    if (
      !/^word:(0|[1-9]\d*)$/u.test(requested.expressionId) ||
      !Number.isSafeInteger(start) ||
      word !== evidence.surfaceForm ||
      requested.display?.trim().toLowerCase() !== word?.toLowerCase() ||
      !targetEvidenceMatchesSentence(at(start), source)
    )
      throw new Error("Invalid learning target candidate");
    // A user's clicked occurrence is authoritative, even for repeated words.
    const alignedTarget = { ...target, sourceEvidence: at(start) };
    if (!learningTargetFitsVocabularyBoundary(alignedTarget, source))
      throw new Error("Invalid learning target candidate");
    return alignedTarget;
  }
  const alignedTarget = {
    ...target,
    sourceEvidence: alignSourceEvidence(evidence, source),
  };
  if (!learningTargetFitsVocabularyBoundary(alignedTarget, source))
    throw new Error("Invalid learning target candidate");
  return alignedTarget;
};

const alignSourceEvidence = (
  evidence: TargetSourceEvidence,
  source: string,
): TargetSourceEvidence => {
  if (targetEvidenceMatchesSentence(evidence, source)) return evidence;

  let aligned: TargetSourceEvidence | undefined;
  for (
    let start = source.indexOf(evidence.surfaceForm);
    start !== -1;
    start = source.indexOf(evidence.surfaceForm, start + 1)
  ) {
    const candidate = {
      ...evidence,
      startUtf16: start,
      endUtf16: start + evidence.surfaceForm.length,
    };
    if (!targetEvidenceMatchesSentence(candidate, source)) continue;
    if (aligned) throw new Error("Invalid learning target candidate");
    aligned = candidate;
  }
  if (!aligned) throw new Error("Invalid learning target candidate");
  return aligned;
};
const parseTarget = (value: unknown) => {
  if (
    !record(value) ||
    typeof value.targetId !== "string" ||
    typeof value.unitId !== "string" ||
    typeof value.display !== "string" ||
    typeof value.explanationZh !== "string" ||
    (value.candidateExample != null &&
      !isValidLearningTargetCandidateExample(value.candidateExample)) ||
    !["WORD", "FIXED_EXPRESSION", "PATTERN"].includes(String(value.type)) ||
    !Array.isArray(value.structureParts) ||
    value.structureParts.some(
      (part) => typeof part !== "string" || !part.trim(),
    ) ||
    (value.type === "PATTERN" && value.structureParts.length === 0) ||
    !Array.isArray(value.relatedVocabulary) ||
    value.relatedVocabulary.some((part) => typeof part !== "string") ||
    value.selectedByDefault !== true ||
    (value.reusedKnowledgeRef != null &&
      (typeof value.reusedKnowledgeRef !== "string" ||
        value.reusedKnowledgeRef.length > 128 ||
        !/^(?:VOCABULARY|PATTERN):[^\s]+$/u.test(value.reusedKnowledgeRef)))
  )
    throw new Error("Invalid learning target candidate");
  let sourceEvidence;
  try {
    sourceEvidence = parseProviderSourceEvidence(value.sourceEvidence);
  } catch {
    throw new Error("Invalid learning target candidate");
  }
  return {
    targetId: value.targetId,
    unitId: value.unitId,
    display: value.display,
    explanationZh: value.explanationZh,
    type: value.type as "WORD" | "FIXED_EXPRESSION" | "PATTERN",
    structureParts: value.structureParts as string[],
    relatedVocabulary: value.relatedVocabulary as string[],
    ...(value.relatedVocabularyItems !== undefined
      ? {
          relatedVocabularyItems: value.relatedVocabularyItems as
            readonly RelatedVocabularyItem[] | null,
        }
      : {}),
    ...(value.relatedVocabularyItemRefs !== undefined
      ? {
          relatedVocabularyItemRefs: value.relatedVocabularyItemRefs as
            readonly string[] | null,
        }
      : {}),
    selectedByDefault: true as const,
    sourceEvidence,
    ...(value.reusedKnowledgeRef !== undefined
      ? { reusedKnowledgeRef: value.reusedKnowledgeRef as string | null }
      : {}),
    ...(value.candidateExample !== undefined
      ? {
          candidateExample:
            value.candidateExample as LearningTargetCandidateExample | null,
        }
      : {}),
  };
};

/** Provider positions and allowedForms are transport metadata, not semantic
 * authority. When all six fields are present and the literal source form is
 * usable, rebuild those two derived values before exact current-sentence
 * alignment. Missing semantic fields, multiple forms and non-literal content
 * remain invalid. */
const parseProviderSourceEvidence = (value: unknown): TargetSourceEvidence => {
  if (!record(value) || Object.keys(value).length !== 6)
    throw new Error("Invalid target source evidence");
  const required = [
    "version",
    "startUtf16",
    "endUtf16",
    "surfaceForm",
    "normalizedForm",
    "allowedForms",
  ];
  if (
    !required.every((key) => key in value) ||
    value.version !== 1 ||
    !Number.isSafeInteger(value.startUtf16) ||
    !Number.isSafeInteger(value.endUtf16) ||
    Number(value.startUtf16) < 0 ||
    Number(value.endUtf16) <= Number(value.startUtf16) ||
    typeof value.surfaceForm !== "string" ||
    !value.surfaceForm.trim() ||
    typeof value.normalizedForm !== "string" ||
    !value.normalizedForm.trim() ||
    !Array.isArray(value.allowedForms) ||
    value.allowedForms.length !== 1 ||
    typeof value.allowedForms[0] !== "string" ||
    !value.allowedForms[0].trim()
  )
    throw new Error("Invalid target source evidence");
  return parseTargetSourceEvidence({
    version: 1,
    startUtf16: Number(value.startUtf16),
    endUtf16: Number(value.startUtf16) + value.surfaceForm.length,
    surfaceForm: value.surfaceForm,
    normalizedForm: value.normalizedForm,
    allowedForms: [value.surfaceForm],
  });
};
const parseExpression = (value: unknown) => {
  if (
    !record(value) ||
    typeof value.expressionId !== "string" ||
    typeof value.unitId !== "string" ||
    typeof value.display !== "string" ||
    (value.explanationZh !== undefined &&
      typeof value.explanationZh !== "string")
  )
    throw new Error("Invalid queryable expression");
  return {
    expressionId: value.expressionId,
    unitId: value.unitId,
    display: value.display,
    ...(value.explanationZh !== undefined
      ? { explanationZh: value.explanationZh }
      : {}),
  };
};
const record = (value: unknown): value is Record<string, unknown> =>
  typeof value === "object" && value !== null;
