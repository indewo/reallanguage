import { describe, expect, it, vi } from "vitest";
import { GenerateEnglishCandidates } from "./generate-english-candidates.js";

const command = {
  accountId: "account",
  sourceType: "DIARY" as const,
  units: [
    { unitId: "u1", order: 0, textZh: "今天我很高兴。" },
    { unitId: "u2", order: 1, textZh: "我和朋友聊了很久。" },
  ],
  chapters: [{ chapterId: "c1", unitIds: ["u1", "u2"] }],
  selectedUnitIds: ["u2"],
  commandId: "cmd",
};
const settings = {
  formalityLevel: 3,
  slangEnabled: false,
  basicVocabulary: "DAILY_CORE" as const,
  purposeVocabularies: ["DAILY_LIFE"] as const,
  styleReference: "NATURAL_CONCISE",
};
const candidate = (sourceUnitId: string, english: string) => ({
  sourceUnitId,
  candidates: [
    {
      candidateId: `${sourceUnitId}-1`,
      english,
      chineseCorrespondenceZh:
        sourceUnitId === "u1" ? "今天我很高兴。" : "我和朋友聊了很久。",
      meaningClaims: ["claim"] as string[],
      styleFit: "natural",
      reusedKnowledgeRefs: [] as string[],
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
});

describe("GenerateEnglishCandidates", () => {
  it("loads authoritative settings and returns only fully verified complete chapters", async () => {
    const read = vi.fn().mockResolvedValue(settings);
    const generate = vi
      .fn()
      .mockResolvedValue([
        candidate("u1", "I was very happy today."),
        candidate("u2", "I talked with my friend for a long time."),
      ]);
    const result = await new GenerateEnglishCandidates(
      { read },
      { generate },
    ).execute(command);
    expect(read).toHaveBeenCalledWith("account");
    expect(generate).toHaveBeenCalledWith(
      expect.objectContaining({
        settings,
        commandId: "cmd:c1",
        chapter: command.chapters[0],
      }),
    );
    expect(
      result.chapters[0]?.sentences.map((sentence) => sentence.sourceUnitId),
    ).toEqual(["u1", "u2"]);
    expect(result.chapters[0]?.sentences[1]?.isFocus).toBe(true);
  });

  it("retries incomplete or unfaithful output once and rejects it if correction still fails", async () => {
    const application = (output: unknown) =>
      new GenerateEnglishCandidates(
        { read: async () => settings },
        { generate: async () => output as never },
      ).execute(command);
    await expect(application([candidate("u1", "Good.")])).rejects.toThrow(
      "Invalid English candidate output",
    );
    const failed = [candidate("u1", "Good."), candidate("u2", "Talked.")];
    failed[1]!.candidates[0]!.fidelity.facts = false;
    await expect(application(failed)).rejects.toThrow(
      "Invalid English candidate output",
    );
  });

  it("owns candidate identities so repeated provider ids across overlapping chapters cannot fail", async () => {
    const overlappingCommand = {
      ...command,
      chapters: [
        { chapterId: "c1", unitIds: ["u1", "u2"] },
        { chapterId: "c2", unitIds: ["u2"] },
      ],
    };
    const generate = vi.fn().mockImplementation(({ chapter }) =>
      Promise.resolve(
        chapter.unitIds.map((unitId: string) => {
          const result = candidate(unitId, `English for ${unitId}.`);
          result.candidates[0]!.candidateId = "provider-repeated-id";
          return result;
        }),
      ),
    );
    const application = new GenerateEnglishCandidates(
      { read: async () => settings },
      { generate },
    );
    const first = await application.execute(overlappingCommand);
    const second = await application.execute(overlappingCommand);
    const firstIds = first.chapters.flatMap((chapter) =>
      chapter.sentences.flatMap((sentence) =>
        sentence.candidates.map((item) => item.candidateId),
      ),
    );
    const secondIds = second.chapters.flatMap((chapter) =>
      chapter.sentences.flatMap((sentence) =>
        sentence.candidates.map((item) => item.candidateId),
      ),
    );
    expect(new Set(firstIds).size).toBe(firstIds.length);
    expect(secondIds).toEqual(firstIds);
    expect(firstIds.every((id) => id.startsWith("english-"))).toBe(true);
  });

  it("uses a correction request when the first model response is structurally invalid", async () => {
    const generate = vi
      .fn()
      .mockResolvedValueOnce([candidate("u1", "Incomplete.")])
      .mockResolvedValueOnce([
        candidate("u1", "I was very happy today."),
        candidate("u2", "I talked with my friend for a long time."),
      ]);
    const result = await new GenerateEnglishCandidates(
      { read: async () => settings },
      { generate },
    ).execute(command);
    expect(result.chapters).toHaveLength(1);
    expect(generate).toHaveBeenNthCalledWith(
      2,
      expect.objectContaining({
        commandId: "cmd:c1:correction",
        chapter: { chapterId: "c1", unitIds: ["u2"] },
        selectedUnitIds: ["u2"],
      }),
    );
  });

  it("keeps usable output, restores order, and sanitizes optional reuse metadata", async () => {
    const second = candidate("u2", "I talked with my friend.");
    second.candidates[0]!.reusedKnowledgeRefs = [
      "VOCABULARY:known",
      "VOCABULARY:known",
      "VOCABULARY:invented",
    ];
    const first = candidate("u1", "I was happy today.");
    first.candidates[0]!.chineseCorrespondenceZh = "";
    first.candidates[0]!.styleFit = "";
    const result = await new GenerateEnglishCandidates(
      { read: async () => settings },
      { generate: async () => [second, first] },
    ).execute({
      ...command,
      knownKnowledge: [
        {
          ref: "VOCABULARY:known",
          type: "VOCABULARY",
          display: "known",
          meaningZh: "已知的",
        },
      ],
    });
    const sentences = result.chapters[0]!.sentences;
    expect(sentences.map((sentence) => sentence.sourceUnitId)).toEqual([
      "u1",
      "u2",
    ]);
    expect(sentences[0]!.candidates[0]).toMatchObject({
      chineseCorrespondenceZh: "今天我很高兴。",
      styleFit: "自然、简洁",
    });
    expect(sentences[1]!.candidates[0]!.reusedKnowledgeRefs).toEqual([
      "VOCABULARY:known",
    ]);
  });

  it("rejects a chapter that references unknown units or omits the focus", async () => {
    const app = new GenerateEnglishCandidates(
      { read: async () => settings },
      { generate: async () => [] },
    );
    await expect(
      app.execute({
        ...command,
        chapters: [{ chapterId: "bad", unitIds: ["missing"] }],
      }),
    ).rejects.toThrow("Invalid English generation input");
    await expect(
      app.execute({
        ...command,
        chapters: [{ chapterId: "bad", unitIds: ["u1"] }],
      }),
    ).rejects.toThrow("Invalid English generation input");
  });
});
