import { createHash } from "node:crypto";

export type ContentGenerationSettings = Readonly<{
  formalityLevel: number;
  slangEnabled: boolean;
  basicVocabulary: string;
  purposeVocabularies: readonly string[];
  styleReference: string;
}>;
export interface ContentGenerationSettingsReader {
  read(accountId: string): Promise<ContentGenerationSettings>;
}
export type EnglishGenerationUnit = Readonly<{
  unitId: string;
  order: number;
  textZh: string;
}>;
export type EnglishGenerationChapter = Readonly<{
  chapterId: string;
  unitIds: readonly string[];
}>;
export type FidelityEvidence = Readonly<{
  facts: boolean;
  entities: boolean;
  time: boolean;
  conditions: boolean;
  causality: boolean;
  logic: boolean;
  tone: boolean;
  questions: boolean;
  noHallucination: boolean;
}>;
export type GeneratedEnglishCandidate = Readonly<{
  candidateId: string;
  english: string;
  chineseCorrespondenceZh: string;
  meaningClaims: readonly string[];
  styleFit: string;
  reusedKnowledgeRefs: readonly string[];
  fidelity: FidelityEvidence;
}>;
export type GeneratedEnglishSentence = Readonly<{
  sourceUnitId: string;
  candidates: readonly GeneratedEnglishCandidate[];
}>;
export type KnownKnowledgeCandidate = Readonly<{
  ref: string;
  type: "VOCABULARY" | "PATTERN";
  display: string;
  meaningZh: string;
}>;
export type EnglishCandidateProviderCommand = Readonly<{
  accountId: string;
  sourceType: "DIARY" | "RECORDING" | "AI_SCENE";
  units: readonly EnglishGenerationUnit[];
  chapter: EnglishGenerationChapter;
  selectedUnitIds: readonly string[];
  settings: ContentGenerationSettings;
  commandId: string;
  knownKnowledge?: readonly KnownKnowledgeCandidate[] | undefined;
}>;
export interface EnglishCandidateProvider {
  generate(
    command: EnglishCandidateProviderCommand,
  ): Promise<readonly GeneratedEnglishSentence[]>;
}
export type GenerateEnglishCandidatesCommand = Omit<
  EnglishCandidateProviderCommand,
  "chapter" | "settings"
> &
  Readonly<{
    chapters: readonly EnglishGenerationChapter[];
  }>;

export class GenerateEnglishCandidates {
  public constructor(
    private readonly settings: ContentGenerationSettingsReader,
    private readonly provider: EnglishCandidateProvider,
  ) {}
  public async execute(command: GenerateEnglishCandidatesCommand) {
    validateInput(command);
    const settings = await this.settings.read(command.accountId);
    const unitById = new Map(command.units.map((unit) => [unit.unitId, unit]));
    const chapters = [];
    for (const chapter of command.chapters) {
      const allowedKnowledgeRefs = new Set(
        (command.knownKnowledge ?? []).map((item) => item.ref),
      );
      const providerCommand = {
        accountId: command.accountId,
        sourceType: command.sourceType,
        units: command.units,
        chapter,
        selectedUnitIds: command.selectedUnitIds,
        settings,
        knownKnowledge: command.knownKnowledge ?? [],
        commandId: `${command.commandId}:${chapter.chapterId}`,
      };
      const generated = await this.provider.generate(providerCommand);
      const usable = usableSentences(
        chapter,
        generated,
        allowedKnowledgeRefs,
        unitById,
      );
      const missingUnitIds = chapter.unitIds.filter((id) => !usable.has(id));
      if (missingUnitIds.length) {
        const correctionChapter = {
          ...chapter,
          unitIds: missingUnitIds,
        };
        const corrected = await this.provider.generate({
          ...providerCommand,
          chapter: correctionChapter,
          selectedUnitIds: missingUnitIds,
          commandId: `${providerCommand.commandId}:correction`,
        });
        for (const [unitId, sentence] of usableSentences(
          correctionChapter,
          corrected,
          allowedKnowledgeRefs,
          unitById,
        )) {
          usable.set(unitId, sentence);
        }
      }
      if (chapter.unitIds.some((id) => !usable.has(id)))
        throw new Error("Invalid English candidate output");
      const completeSentences = chapter.unitIds.map((id) => usable.get(id)!);
      chapters.push({
        chapterId: chapter.chapterId,
        sentences: completeSentences.map((sentence) => ({
          sourceUnitId: sentence.sourceUnitId,
          textZh: unitById.get(sentence.sourceUnitId)!.textZh,
          isFocus: command.selectedUnitIds.includes(sentence.sourceUnitId),
          candidates: sentence.candidates.map((candidate, candidateIndex) => ({
            candidateId: ownedCandidateId(
              command,
              chapter.chapterId,
              sentence.sourceUnitId,
              candidateIndex,
            ),
            english: candidate.english,
            chineseCorrespondenceZh: candidate.chineseCorrespondenceZh,
            meaningClaims: candidate.meaningClaims,
            styleFit: candidate.styleFit,
            reusedKnowledgeRefs: candidate.reusedKnowledgeRefs,
          })),
        })),
      });
    }
    return { chapters };
  }
}

const validateInput = (command: GenerateEnglishCandidatesCommand) => {
  const knowledge = command.knownKnowledge ?? [];
  if (
    knowledge.length > 16 ||
    new Set(knowledge.map((item) => item.ref)).size !== knowledge.length ||
    knowledge.some(
      (item) =>
        !["VOCABULARY", "PATTERN"].includes(item.type) ||
        !item.ref.startsWith(`${item.type}:`) ||
        !item.ref.slice(item.type.length + 1).trim() ||
        item.ref.length > 128 ||
        /\s/u.test(item.ref) ||
        !item.display.trim() ||
        !item.meaningZh.trim(),
    ) ||
    knowledge.reduce(
      (total, item) =>
        total +
        Array.from(item.display).length +
        Array.from(item.meaningZh).length,
      0,
    ) > 4000
  ) {
    throw new Error("Invalid English generation input");
  }
  const unitIds = new Set(command.units.map((unit) => unit.unitId));
  const ordered = [...command.units].sort((a, b) => a.order - b.order);
  const chapterUnitIds = command.chapters.flatMap((chapter) => chapter.unitIds);
  if (
    ordered.some((unit, index) => unit.order !== index) ||
    unitIds.size !== command.units.length ||
    command.chapters.length === 0 ||
    command.chapters.some(
      (chapter) =>
        !chapter.chapterId ||
        chapter.unitIds.length < 1 ||
        chapter.unitIds.length > 4 ||
        chapter.unitIds.some((id) => !unitIds.has(id)),
    ) ||
    new Set(command.chapters.map((chapter) => chapter.chapterId)).size !==
      command.chapters.length ||
    command.selectedUnitIds.length < 1 ||
    command.selectedUnitIds.length > 3 ||
    new Set(command.selectedUnitIds).size !== command.selectedUnitIds.length ||
    command.selectedUnitIds.some(
      (id) => !unitIds.has(id) || !chapterUnitIds.includes(id),
    )
  )
    throw new Error("Invalid English generation input");
};

const usableSentences = (
  chapter: EnglishGenerationChapter,
  generated: readonly GeneratedEnglishSentence[],
  allowedKnowledgeRefs: ReadonlySet<string>,
  unitById: ReadonlyMap<string, EnglishGenerationUnit>,
): Map<string, GeneratedEnglishSentence> => {
  const requiredUnitIds = new Set(chapter.unitIds);
  const result = new Map<string, GeneratedEnglishSentence>();
  for (const sentence of generated) {
    if (!requiredUnitIds.has(sentence.sourceUnitId)) continue;
    const unit = unitById.get(sentence.sourceUnitId);
    if (!unit) continue;
    const candidates = sentence.candidates
      .filter(
        (candidate) =>
          candidate.english.trim() &&
          Object.values(candidate.fidelity).every((passed) => passed === true),
      )
      .slice(0, 3)
      .map((candidate) => ({
        ...candidate,
        english: candidate.english.trim(),
        chineseCorrespondenceZh:
          candidate.chineseCorrespondenceZh.trim() || unit.textZh,
        meaningClaims: candidate.meaningClaims.filter((claim) => claim.trim()),
        styleFit: candidate.styleFit.trim() || "自然、简洁",
        reusedKnowledgeRefs: [
          ...new Set(
            candidate.reusedKnowledgeRefs.filter((ref) =>
              allowedKnowledgeRefs.has(ref),
            ),
          ),
        ],
      }));
    if (!candidates.length) continue;
    const existing = result.get(sentence.sourceUnitId);
    result.set(sentence.sourceUnitId, {
      sourceUnitId: sentence.sourceUnitId,
      candidates: [...(existing?.candidates ?? []), ...candidates].slice(0, 3),
    });
  }
  return result;
};

const ownedCandidateId = (
  command: GenerateEnglishCandidatesCommand,
  chapterId: string,
  sourceUnitId: string,
  candidateIndex: number,
): string => {
  const digest = createHash("sha256")
    .update(
      JSON.stringify([
        command.accountId,
        command.commandId,
        chapterId,
        sourceUnitId,
        candidateIndex,
      ]),
    )
    .digest("hex")
    .slice(0, 32);
  return `english-${digest}`;
};
