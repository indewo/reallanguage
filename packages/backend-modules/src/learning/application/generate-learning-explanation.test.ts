import { describe, expect, it, vi } from "vitest";
import { GenerateLearningExplanation } from "./generate-learning-explanation.js";
const settings = {
  formalityLevel: 3,
  slangEnabled: false,
  basicVocabulary: "DAILY_CORE",
  purposeVocabularies: [],
  styleReference: "NATURAL_CONCISE",
};
const command = {
  accountId: "a",
  chapter: {
    chapterId: "c1",
    sentences: [
      {
        sentenceId: "u1",
        textEn: "I was happy.",
        textZh: "我很高兴。",
        isFocus: true,
      },
    ],
  },
  focusSentenceId: "u1",
  round: 1,
  commandId: "cmd",
};
describe("GenerateLearningExplanation", () => {
  const legacyExpression = {
    kind: "EXPRESSION",
    expressionId: "x1",
    explanationZh: "高兴的；描述一个人的感受。",
    phonetic: null,
  };
  const details = {
    contextualMeaningZh: "高兴的",
    usageZh: "这里在 was 后说明主语过去的感受。",
    expressionHintZh: "也可以用 feel happy 描述这种感受。",
  };
  const expressionCommand = {
    ...command,
    requestedExpression: { expressionId: "x1", display: "happy" },
  };

  it.each([details.expressionHintZh, null])(
    "delivers separate contextual meaning and usage with an optional useful hint: %s",
    async (expressionHintZh) => {
      const generate = vi.fn().mockResolvedValue({
        ...legacyExpression,
        ...details,
        contextualMeaningZh: ` ${details.contextualMeaningZh} `,
        usageZh: ` ${details.usageZh} `,
        expressionHintZh,
      });
      const app = new GenerateLearningExplanation(
        { read: async () => settings },
        { generate },
      );
      await expect(app.execute(expressionCommand)).resolves.toEqual({
        ...legacyExpression,
        ...details,
        expressionHintZh,
      });
      expect(generate).toHaveBeenCalledTimes(1);
      expect(generate).toHaveBeenCalledWith({ ...expressionCommand, settings });
    },
  );

  it.each([
    {},
    { contextualMeaningZh: null, usageZh: null, expressionHintZh: null },
    { usageZh: null },
  ])(
    "delivers legacy explanation without inventing a structured group: %j",
    async (group) => {
      const app = new GenerateLearningExplanation(
        { read: async () => settings },
        { generate: async () => ({ ...legacyExpression, ...group }) },
      );
      await expect(app.execute(expressionCommand)).resolves.toEqual(
        legacyExpression,
      );
    },
  );

  it.each([
    { contextualMeaningZh: "高兴的" },
    { contextualMeaningZh: "高兴的", usageZh: "说明感受。" },
    { usageZh: "说明感受。", expressionHintZh: null },
    {
      contextualMeaningZh: null,
      usageZh: "说明感受。",
      expressionHintZh: null,
    },
    { expressionHintZh: "一个表达提示。" },
    { ...details, contextualMeaningZh: " " },
    { ...details, usageZh: "\n" },
    { ...details, expressionHintZh: " " },
    { ...details, contextualMeaningZh: 1 },
    { ...details, usageZh: {} },
    { ...details, expressionHintZh: false },
  ])(
    "rejects a partial or invalid expression detail group: %j",
    async (group) => {
      const app = new GenerateLearningExplanation(
        { read: async () => settings },
        { generate: async () => ({ ...legacyExpression, ...group }) },
      );
      await expect(app.execute(expressionCommand)).rejects.toThrow(
        "Invalid expression detail output",
      );
    },
  );

  it("does not deliver the previous expression's structured explanation for another clicked expression", async () => {
    const generate = vi
      .fn()
      .mockResolvedValue({ ...legacyExpression, ...details });
    const app = new GenerateLearningExplanation(
      { read: async () => settings },
      { generate },
    );
    await expect(app.execute(expressionCommand)).resolves.toMatchObject(
      details,
    );
    await expect(
      app.execute({
        ...expressionCommand,
        commandId: "another-expression-command",
        requestedExpression: { expressionId: "x2", display: "was" },
      }),
    ).rejects.toThrow("Invalid expression explanation output");
    expect(generate).toHaveBeenCalledTimes(2);
  });

  it("delivers the separate bilingual example instead of losing it behind the meaning field", async () => {
    const naturalExample = {
      textEn: "She looks happy today.",
      textZh: "她今天看起来很高兴。",
    };
    const app = new GenerateLearningExplanation(
      { read: async () => settings },
      {
        generate: async () => ({
          kind: "EXPRESSION",
          expressionId: "x1",
          explanationZh: "高兴的",
          phonetic: null,
          naturalExample,
        }),
      },
    );
    expect(
      await app.execute({
        ...command,
        requestedExpression: { expressionId: "x1", display: "happy" },
      }),
    ).toMatchObject({ naturalExample });
  });

  it.each([
    { textEn: "She looks happy." },
    { textEn: "", textZh: "她高兴。" },
    { textEn: "She looks happy.", textZh: " " },
  ])(
    "rejects a partial example rather than displaying it as successful: %j",
    async (naturalExample) => {
      const app = new GenerateLearningExplanation(
        { read: async () => settings },
        {
          generate: async () => ({
            kind: "EXPRESSION",
            expressionId: "x1",
            explanationZh: "高兴的",
            phonetic: null,
            naturalExample,
          }),
        },
      );
      await expect(
        app.execute({
          ...command,
          requestedExpression: { expressionId: "x1", display: "happy" },
        }),
      ).rejects.toThrow("Invalid expression example output");
    },
  );
  it("uses authoritative settings for a fixed sentence without rewriting it", async () => {
    const result = await new GenerateLearningExplanation(
      { read: async () => settings },
      {
        generate: async (input) => {
          expect(input.chapter).toEqual(command.chapter);
          expect(input.settings).toEqual(settings);
          return { kind: "SENTENCE", explanationZh: "这句表达过去的感受。" };
        },
      },
    ).execute(command);
    expect(result.kind).toBe("SENTENCE");
  });
  it("requires prior base explanation for later rounds and exact clicked expression identity", async () => {
    const app = new GenerateLearningExplanation(
      { read: async () => settings },
      {
        generate: async () => ({
          kind: "EXPRESSION",
          expressionId: "x1",
          explanationZh: "高兴的",
          phonetic: null,
        }),
      },
    );
    await expect(app.execute({ ...command, round: 2 })).rejects.toThrow(
      "Invalid learning explanation input",
    );
    await expect(
      app.execute({
        ...command,
        requestedExpression: { expressionId: "wrong", display: "happy" },
      }),
    ).rejects.toThrow("Invalid expression explanation output");
  });
});
