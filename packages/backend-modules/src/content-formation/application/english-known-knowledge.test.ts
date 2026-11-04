import { describe, expect, it, vi } from "vitest";
import {
  GenerateEnglishCandidates,
  type KnownKnowledgeCandidate,
} from "./generate-english-candidates.js";

const knowledge: KnownKnowledgeCandidate = {
  ref: "PATTERN:p1",
  type: "PATTERN",
  display: "used to + verb",
  meaningZh: "表示过去的习惯",
};
const settings = {
  formalityLevel: 3,
  slangEnabled: false,
  basicVocabulary: "DAILY_CORE" as const,
  purposeVocabularies: [],
  styleReference: "NATURAL_CONCISE",
};
const command = {
  accountId: "account",
  sourceType: "DIARY" as const,
  units: [{ unitId: "u1", order: 0, textZh: "我以前常常步行去学校。" }],
  chapters: [{ chapterId: "c1", unitIds: ["u1"] }],
  selectedUnitIds: ["u1"],
  commandId: "stable-command",
  knownKnowledge: [knowledge],
};
const output = (refs: string[]) => [
  {
    sourceUnitId: "u1",
    candidates: [
      {
        candidateId: "e1",
        english: "I used to walk to school.",
        chineseCorrespondenceZh: "我以前常常步行去学校。",
        meaningClaims: ["过去习惯"],
        styleFit: "自然",
        reusedKnowledgeRefs: refs,
        fidelity: {
          facts: true,
          entities: true,
          time: true,
          conditions: true,
          causality: true,
          logic: true,
          tone: true,
          questions: true,
          noHallucination: true,
        },
      },
    ],
  },
];

describe("known knowledge in English generation", () => {
  it("supplies the exact allowed local knowledge and preserves only its explicitly reported use", async () => {
    const generate = vi.fn().mockResolvedValue(output([knowledge.ref]));
    const result = await new GenerateEnglishCandidates(
      { read: async () => settings },
      { generate },
    ).execute(command);
    expect(generate).toHaveBeenCalledWith(
      expect.objectContaining({ knownKnowledge: [knowledge] }),
    );
    expect(
      result.chapters[0]!.sentences[0]!.candidates[0]!.reusedKnowledgeRefs,
    ).toEqual([knowledge.ref]);
  });
  it.each([
    { refs: ["PATTERN:unknown"], expected: [] },
    { refs: ["VOCABULARY:p1"], expected: [] },
    { refs: ["PATTERN:p1", "PATTERN:p1"], expected: ["PATTERN:p1"] },
  ])(
    "drops unknown or wrong-kind references and deduplicates allowed references $refs",
    async ({ refs, expected }) => {
      const result = await new GenerateEnglishCandidates(
        { read: async () => settings },
        { generate: async () => output(refs) },
      ).execute(command);
      expect(
        result.chapters[0]!.sentences[0]!.candidates[0]!.reusedKnowledgeRefs,
      ).toEqual(expected);
    },
  );
  it("does not retain a reference for a legacy request without knowledge", async () => {
    const result = await new GenerateEnglishCandidates(
      { read: async () => settings },
      { generate: async () => output([knowledge.ref]) },
    ).execute({ ...command, knownKnowledge: undefined });
    expect(
      result.chapters[0]!.sentences[0]!.candidates[0]!.reusedKnowledgeRefs,
    ).toEqual([]);
  });
  it("rejects oversized or malformed knowledge before provider use", async () => {
    const generate = vi.fn().mockResolvedValue(output([]));
    const application = new GenerateEnglishCandidates(
      { read: async () => settings },
      { generate },
    );
    const invalid = [
      Array.from({ length: 17 }, (_, i) => ({
        ...knowledge,
        ref: `PATTERN:p${i}`,
      })),
      [{ ...knowledge, display: "x".repeat(4001) }],
      [knowledge, knowledge],
      [{ ...knowledge, ref: "VOCABULARY:p1" }],
      [{ ...knowledge, meaningZh: " " }],
    ];
    for (const knownKnowledge of invalid)
      await expect(
        application.execute({ ...command, knownKnowledge }),
      ).rejects.toThrow("Invalid English generation input");
    expect(generate).not.toHaveBeenCalled();
  });
  it("allows no reuse when the original meaning has no natural match", async () => {
    const result = await new GenerateEnglishCandidates(
      { read: async () => settings },
      { generate: async () => output([]) },
    ).execute(command);
    expect(
      result.chapters[0]!.sentences[0]!.candidates[0]!.reusedKnowledgeRefs,
    ).toEqual([]);
  });
});
