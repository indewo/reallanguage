import type {
  EnglishCandidateProvider,
  EnglishCandidateProviderCommand,
  GeneratedEnglishSentence,
} from "../application/generate-english-candidates.js";

export class HttpEnglishCandidateProvider implements EnglishCandidateProvider {
  public constructor(
    private readonly config: Readonly<{ endpoint: string; apiKey: string }>,
    private readonly fetchImplementation: typeof fetch = fetch,
  ) {}
  public async generate(
    command: EnglishCandidateProviderCommand,
  ): Promise<readonly GeneratedEnglishSentence[]> {
    const response = await this.fetchImplementation(this.config.endpoint, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${this.config.apiKey}`,
        "Content-Type": "application/json",
        "Idempotency-Key": command.commandId,
        "X-RealLanguage-Account-Id": command.accountId,
      },
      body: JSON.stringify({
        task: "ENGLISH_CANDIDATE_GENERATION_V1",
        requiredVerification: "ENGLISH_FIDELITY_CHECK_V1",
        input: {
          sourceType: command.sourceType,
          // The model needs the complete confirmed source to understand who is
          // speaking, what is happening and which register is natural. The
          // chapter remains the strict output allowlist, so supplying context
          // never authorizes translation of out-of-chapter units.
          sourceContextUnits: command.units,
          units: command.units.filter((unit) =>
            command.chapter.unitIds.includes(unit.unitId),
          ),
          chapter: command.chapter,
          selectedUnitIds: command.selectedUnitIds,
          settings: command.settings,
          knownKnowledge: command.knownKnowledge ?? [],
          rules: {
            simplestViableExpression: true,
            preserveMeaningAndTone: true,
            faithfulDoesNotMeanWordForWord: true,
            useCompleteSourceContext: true,
            inferMinimalPragmaticSceneWhenIncomplete: true,
            inferredSceneGuidesWordingOnly: true,
            neverAssertInferredDetailsAsUserFacts: true,
            neverImportContextOnlyClaimsIntoTarget: true,
            naturalContemporaryAmericanEnglish: true,
            rejectContextFreeLiteralTranslation: true,
            candidatesPerUnit: { min: 1, max: 3 },
            rejectAnyFailedFidelityDimension: true,
            reuseOnlyProvidedKnowledgeRefs: true,
            preserveOriginalMeaningBeforeKnowledgeReuse: true,
            knowledgeOrderIsPreferenceNotRequirement: true,
          },
        },
      }),
    });
    if (!response.ok)
      throw new Error("English candidate provider request failed");
    const value: unknown = await response.json();
    if (!isRecord(value) || !Array.isArray(value.sentences))
      throw new Error("Invalid English candidate provider response");
    return value.sentences.map(parseSentence);
  }
}

const parseSentence = (value: unknown): GeneratedEnglishSentence => {
  if (
    !isRecord(value) ||
    typeof value.sourceUnitId !== "string" ||
    !Array.isArray(value.candidates)
  )
    throw new Error("Invalid generated sentence");
  return {
    sourceUnitId: value.sourceUnitId,
    candidates: value.candidates.map(parseCandidate),
  };
};
const parseCandidate = (value: unknown) => {
  if (
    !isRecord(value) ||
    typeof value.candidateId !== "string" ||
    typeof value.english !== "string" ||
    typeof value.chineseCorrespondenceZh !== "string" ||
    !Array.isArray(value.meaningClaims) ||
    value.meaningClaims.some((item) => typeof item !== "string") ||
    typeof value.styleFit !== "string" ||
    !Array.isArray(value.reusedKnowledgeRefs) ||
    value.reusedKnowledgeRefs.some((item) => typeof item !== "string") ||
    !isRecord(value.fidelity)
  )
    throw new Error("Invalid generated English candidate");
  const dimensions = [
    "facts",
    "entities",
    "time",
    "conditions",
    "causality",
    "logic",
    "tone",
    "questions",
    "noHallucination",
  ] as const;
  const fidelity = value.fidelity as Record<string, unknown>;
  if (dimensions.some((name) => typeof fidelity[name] !== "boolean"))
    throw new Error("Invalid fidelity evidence");
  return {
    candidateId: value.candidateId,
    english: value.english,
    chineseCorrespondenceZh: value.chineseCorrespondenceZh,
    meaningClaims: value.meaningClaims as string[],
    styleFit: value.styleFit,
    reusedKnowledgeRefs: value.reusedKnowledgeRefs as string[],
    fidelity: Object.fromEntries(
      dimensions.map((name) => [name, fidelity[name]]),
    ) as never,
  };
};
const isRecord = (value: unknown): value is Record<string, unknown> =>
  typeof value === "object" && value !== null;
