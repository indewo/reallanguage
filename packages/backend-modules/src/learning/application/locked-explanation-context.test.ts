import { describe, expect, it, vi } from "vitest";
import { GenerateLearningExplanation } from "./generate-learning-explanation.js";
import { HttpLearningExplanationProvider } from "../infrastructure/http-learning-explanation-provider.js";
const settings = {
  formalityLevel: 3,
  slangEnabled: false,
  basicVocabulary: "CET_4",
  purposeVocabularies: [],
  styleReference: "NATURAL_CONCISE",
};
const evidence = (startUtf16 = 8, surfaceForm = "tea") => ({
  version: 1 as const,
  startUtf16,
  endUtf16: startUtf16 + surfaceForm.length,
  surfaceForm,
  normalizedForm: surfaceForm,
  allowedForms: [surfaceForm],
});
const word = {
  targetId: "locked-tea",
  type: "WORD",
  display: "tea",
  explanationZh: "茶",
  structureParts: [],
  sourceEvidence: evidence(),
  relatedVocabulary: [],
};
const related = {
  display: "tea",
  senseZh: "茶",
  unitType: "WORD",
  sourceEvidence: evidence(),
};
const pattern = {
  targetId: "locked-pattern",
  type: "PATTERN",
  display: "drink X and Y",
  explanationZh: "喝两种饮品",
  structureParts: ["动词和并列宾语"],
  sourceEvidence: evidence(2, "drink tea and tea"),
  relatedVocabulary: [related],
};
const base = {
  accountId: "isolated-locked",
  chapter: {
    chapterId: "c1",
    sentences: [
      {
        sentenceId: "s1",
        textEn: "I drink tea and tea.",
        textZh: "我喝茶和茶。",
        isFocus: true,
      },
    ],
  },
  focusSentenceId: "s1",
  round: 1,
  commandId: "locked-explanation",
};
const requestedExpression = {
  expressionId: "word-2",
  display: "tea",
  startUtf16: 8,
  endUtf16: 11,
};
const result = {
  kind: "EXPRESSION",
  expressionId: "word-2",
  explanationZh: "这里指茶。",
  phonetic: null,
  contextualMeaningZh: "茶",
  usageZh: "这里指饮品。",
  expressionHintZh: null,
};
const command = (extra: unknown) =>
  ({ ...base, ...(extra as object) }) as Parameters<
    GenerateLearningExplanation["execute"]
  >[0];
const service = (
  output: unknown = { kind: "SENTENCE", explanationZh: "整句讲解。" },
) => {
  const generate = vi.fn().mockResolvedValue(output);
  return {
    app: new GenerateLearningExplanation(
      { read: async () => settings },
      { generate },
    ),
    generate,
  };
};
describe("locked explanation meaning and occurrence", () => {
  it("delivers legacy unknown patterns without synthesizing structure parts", async () => {
    const { app, generate } = service();
    const legacy = {
      ...pattern,
      sourceEvidence: null,
      relatedVocabulary: null,
      structureParts: [],
    };
    await expect(
      app.execute(command({ lockedTargets: [legacy] })),
    ).resolves.toMatchObject({ kind: "SENTENCE" });
    expect(generate).toHaveBeenCalledWith(
      expect.objectContaining({ lockedTargets: [legacy] }),
    );
  });
  it("rejects ambiguous duplicate sentence identity before a contextual explanation request", async () => {
    const { app, generate } = service();
    await expect(
      app.execute(
        command({
          lockedTargets: [],
          chapter: {
            ...base.chapter,
            sentences: [base.chapter.sentences[0], base.chapter.sentences[0]],
          },
        }),
      ),
    ).rejects.toThrow();
    expect(generate).not.toHaveBeenCalled();
  });
  it.each(["I‘m", "café", "2pm", "3D-printed"])(
    "accepts a complete Unicode source token without changing original offsets: %s",
    async (display) => {
      const textEn = `😀 ${display} works.`;
      const { app } = service(result);
      const output = await app.execute(
        command({
          lockedTargets: [],
          chapter: {
            ...base.chapter,
            sentences: [{ ...base.chapter.sentences[0], textEn }],
          },
          requestedExpression: {
            ...requestedExpression,
            display,
            startUtf16: 3,
            endUtf16: 3 + display.length,
          },
        }),
      );
      expect(output).toMatchObject({ kind: "EXPRESSION" });
    },
  );
  it.each([
    null,
    {},
    [{ ...word, targetId: " " }],
    [word, { ...word, targetId: "duplicate" }],
    [{ ...word, sourceEvidence: evidence(2) }],
    [{ ...word, display: "coffee" }],
    [{ ...word, type: "UNKNOWN" }],
    [{ ...pattern, structureParts: [] }],
    [
      {
        ...pattern,
        relatedVocabulary: [
          { ...related, sourceEvidence: evidence(9, "ea"), display: "ea" },
        ],
      },
    ],
    [
      word,
      { ...pattern, relatedVocabulary: [{ ...related, senseZh: "晚餐" }] },
    ],
    [{ ...pattern, relatedVocabulary: [related, related] }],
    [{ ...word, sourceEvidence: undefined }],
    [
      {
        ...pattern,
        relatedVocabulary: [{ ...related, knowledgeId: "invented" }],
      },
    ],
  ])(
    "rejects corrupt context before any paid request %j",
    async (lockedTargets) => {
      const { app, generate } = service();
      await expect(app.execute(command({ lockedTargets }))).rejects.toThrow();
      expect(generate).not.toHaveBeenCalled();
    },
  );
  it.each([
    { expressionId: "old", display: "tea" },
    { ...requestedExpression, endUtf16: undefined },
    { ...requestedExpression, startUtf16: 9 },
    { ...requestedExpression, display: "ea", startUtf16: 9 },
    { ...requestedExpression, display: "coffee" },
    { ...requestedExpression, startUtf16: 8.5 },
  ])(
    "requires complete real clicked boundaries when the locked context is present %j",
    async (requestedExpression) => {
      const { app, generate } = service(result);
      await expect(
        app.execute(command({ lockedTargets: [], requestedExpression })),
      ).rejects.toThrow();
      expect(generate).not.toHaveBeenCalled();
    },
  );
  it("retains a selected pattern and its related word without making that word selected", async () => {
    const fetcher = vi
      .fn<typeof fetch>()
      .mockResolvedValue(new Response(JSON.stringify(result)));
    const app = new GenerateLearningExplanation(
      { read: async () => settings },
      new HttpLearningExplanationProvider(
        { endpoint: "https://isolated-provider.test", apiKey: "isolated-test" },
        fetcher,
      ),
    );
    await app.execute(
      command({ lockedTargets: [pattern], requestedExpression }),
    );
    const input = JSON.parse(String(fetcher.mock.calls[0]?.[1]?.body)).input;
    expect(input.lockedTargets).toEqual([pattern]);
    expect(input.lockedTargets).toHaveLength(1);
    expect(input.requestedExpression).toEqual(requestedExpression);
    expect(
      new Headers(fetcher.mock.calls[0]?.[1]?.headers).get("Idempotency-Key"),
    ).toBe(base.commandId);
    expect(fetcher).toHaveBeenCalledTimes(1);
  });
  it.each(["茶叶", "晚餐", " 茶 ", null, undefined])(
    "rejects changed or unavailable known meaning %j",
    async (contextualMeaningZh) => {
      const { app } = service({ ...result, contextualMeaningZh });
      await expect(
        app.execute(command({ lockedTargets: [pattern], requestedExpression })),
      ).rejects.toThrow();
    },
  );
  it("preserves the exact raw confirmed meaning without trimming it", async () => {
    const { app } = service({ ...result, contextualMeaningZh: " 茶 " });
    expect(
      await app.execute(
        command({
          lockedTargets: [{ ...word, explanationZh: " 茶 " }],
          requestedExpression,
        }),
      ),
    ).toMatchObject({ contextualMeaningZh: " 茶 " });
  });
  it("binds a repeated word to its own sense and does not borrow the other occurrence", async () => {
    const { app } = service({ ...result, contextualMeaningZh: "第二处茶" });
    const second = {
      ...word,
      targetId: "second",
      explanationZh: "第二处茶",
      sourceEvidence: evidence(16),
    };
    expect(
      await app.execute(
        command({
          lockedTargets: [word, second],
          requestedExpression: {
            ...requestedExpression,
            startUtf16: 16,
            endUtf16: 19,
          },
        }),
      ),
    ).toMatchObject({ contextualMeaningZh: "第二处茶" });
    await expect(
      app.execute(
        command({ lockedTargets: [word, second], requestedExpression }),
      ),
    ).rejects.toThrow();
  });
  it("does not infer an occurrence from old null evidence", async () => {
    const { app } = service({
      ...result,
      contextualMeaningZh: "当前点击的说明",
    });
    expect(
      await app.execute(
        command({
          lockedTargets: [{ ...word, sourceEvidence: null }],
          requestedExpression,
        }),
      ),
    ).toMatchObject({ contextualMeaningZh: "当前点击的说明" });
  });
  it("keeps legacy requests and legacy detail normalization unchanged", async () => {
    const { app } = service({ ...result, contextualMeaningZh: " 茶 " });
    expect(
      await app.execute(
        command({
          requestedExpression: { expressionId: "word-2", display: "tea" },
        }),
      ),
    ).toMatchObject({ contextualMeaningZh: "茶" });
  });
});
