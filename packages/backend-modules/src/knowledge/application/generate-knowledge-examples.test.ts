import { describe, expect, it, vi } from "vitest";
import { GenerateKnowledgeExamples } from "./generate-knowledge-examples.js";

const settings = {
  formalityLevel: 3,
  slangEnabled: false,
  basicVocabulary: "CET_4" as const,
  purposeVocabularies: [] as const,
  styleReference: "",
  voiceEnabled: true,
  autoPlayEnabled: false,
  speechSpeedLevel: 3,
};
const command = {
  accountId: "account",
  commandId: "command",
  itemId: "item",
  targetType: "VOCABULARY" as const,
  display: "run",
  meaningZh: "经营",
  sourceScene: [{ textEn: "She runs a cafe.", textZh: "她经营一家咖啡店。" }],
};
const generated = {
  itemId: "item",
  phonetic: "/rʌn/",
  pronunciationText: "run",
  representativeScene: "经营社区咖啡店",
  naturalRevalidation: null,
  examples: [0, 1, 2].map((index) => ({
    textEn: `Example ${index}`,
    textZh: `例句 ${index}`,
    pronunciationText: `Example ${index}`,
  })),
};

describe("GenerateKnowledgeExamples", () => {
  it("uses authoritative settings and accepts exactly three stable examples", async () => {
    const generate = vi.fn().mockResolvedValue(generated);
    const useCase = new GenerateKnowledgeExamples(
      { read: async () => settings },
      { generate },
    );

    await expect(useCase.execute(command)).resolves.toEqual(generated);
    expect(generate).toHaveBeenCalledWith({ ...command, settings });
  });

  it("rejects identity drift, duplicate examples, and unreliable empty detail", async () => {
    for (const invalid of [
      { ...generated, itemId: "other" },
      {
        ...generated,
        examples: [
          generated.examples[0]!,
          generated.examples[0]!,
          generated.examples[2]!,
        ],
      },
      { ...generated, representativeScene: "" },
    ]) {
      const useCase = new GenerateKnowledgeExamples(
        { read: async () => settings },
        { generate: async () => invalid },
      );
      await expect(useCase.execute(command)).rejects.toThrow(
        "Invalid knowledge example output",
      );
    }
  });
});
