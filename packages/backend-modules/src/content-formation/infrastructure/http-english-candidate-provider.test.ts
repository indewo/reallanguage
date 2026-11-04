import { describe, expect, it, vi } from "vitest";
import { HttpEnglishCandidateProvider } from "./http-english-candidate-provider.js";

describe("HttpEnglishCandidateProvider", () => {
  it("uses the versioned generation contract and stable chapter command", async () => {
    const fetcher = vi
      .fn()
      .mockResolvedValue({ ok: true, json: async () => ({ sentences: [] }) });
    await new HttpEnglishCandidateProvider(
      { endpoint: "https://provider.test/tasks", apiKey: "secret" },
      fetcher,
    ).generate({
      accountId: "a",
      commandId: "cmd:c1",
      sourceType: "DIARY",
      units: [
        { unitId: "u0", order: 0, textZh: "我这周都在做新版本。" },
        { unitId: "u1", order: 1, textZh: "今天我在开发 APP。" },
      ],
      chapter: { chapterId: "c1", unitIds: ["u1"] },
      selectedUnitIds: ["u1"],
      knownKnowledge: [
        {
          ref: "PATTERN:p1",
          type: "PATTERN",
          display: "used to + verb",
          meaningZh: "表示过去习惯",
        },
      ],
      settings: {
        formalityLevel: 3,
        slangEnabled: false,
        basicVocabulary: "DAILY_CORE",
        purposeVocabularies: [],
        styleReference: "NATURAL_CONCISE",
      },
    });
    expect(fetcher).toHaveBeenCalledWith(
      "https://provider.test/tasks",
      expect.objectContaining({
        headers: expect.objectContaining({ "Idempotency-Key": "cmd:c1" }),
        body: expect.stringContaining("ENGLISH_CANDIDATE_GENERATION_V1"),
      }),
    );
    expect(fetcher.mock.calls[0]![1]!.body).toContain(
      "ENGLISH_FIDELITY_CHECK_V1",
    );
    const input = JSON.parse(fetcher.mock.calls[0]![1]!.body).input;
    expect(input.knownKnowledge).toEqual([
      {
        ref: "PATTERN:p1",
        type: "PATTERN",
        display: "used to + verb",
        meaningZh: "表示过去习惯",
      },
    ]);
    expect(input.rules.reuseOnlyProvidedKnowledgeRefs).toBe(true);
    expect(input.sourceContextUnits).toEqual([
      { unitId: "u0", order: 0, textZh: "我这周都在做新版本。" },
      { unitId: "u1", order: 1, textZh: "今天我在开发 APP。" },
    ]);
    expect(input.units).toEqual([
      { unitId: "u1", order: 1, textZh: "今天我在开发 APP。" },
    ]);
    expect(input.rules).toMatchObject({
      faithfulDoesNotMeanWordForWord: true,
      useCompleteSourceContext: true,
      inferMinimalPragmaticSceneWhenIncomplete: true,
      inferredSceneGuidesWordingOnly: true,
      neverAssertInferredDetailsAsUserFacts: true,
      neverImportContextOnlyClaimsIntoTarget: true,
      naturalContemporaryAmericanEnglish: true,
      rejectContextFreeLiteralTranslation: true,
    });
  });
});
