import { describe, expect, it } from "vitest";
import { learningExplanationResultSchema } from "./index.js";

const legacy = {
  kind: "EXPRESSION",
  expressionId: "x",
  explanationZh: "高兴的",
  phonetic: null,
};
describe("learning explanation example compatibility", () => {
  const details = {
    contextualMeaningZh: "高兴的",
    usageZh: "在 be 后说明感受。",
    expressionHintZh: "也可说 feel happy。",
  };
  it.each([details.expressionHintZh, null])(
    "keeps the complete expression detail group and a nullable hint: %s",
    (expressionHintZh) => {
      expect(
        learningExplanationResultSchema.parse({
          ...legacy,
          ...details,
          expressionHintZh,
        }),
      ).toEqual({ ...legacy, ...details, expressionHintZh });
    },
  );
  it.each([
    {},
    { contextualMeaningZh: null, usageZh: null, expressionHintZh: null },
    { usageZh: null },
  ])(
    "accepts the legacy nullish detail group without inventing content: %j",
    (group) => {
      expect(
        learningExplanationResultSchema.parse({ ...legacy, ...group }),
      ).toEqual({ ...legacy, ...group });
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
  ])("rejects a partial or invalid expression detail group: %j", (group) => {
    expect(
      learningExplanationResultSchema.safeParse({ ...legacy, ...group })
        .success,
    ).toBe(false);
  });
  it("keeps a delivered bilingual example and accepts an older missing example honestly", () => {
    const naturalExample = {
      textEn: "She looks happy.",
      textZh: "她看起来很高兴。",
    };
    expect(
      learningExplanationResultSchema.parse({ ...legacy, naturalExample }),
    ).toMatchObject({ naturalExample });
    expect(learningExplanationResultSchema.parse(legacy)).not.toHaveProperty(
      "naturalExample",
    );
  });
  it.each([
    { textEn: "She looks happy." },
    { textEn: " ", textZh: "她很高兴。" },
  ])("rejects partial example %j", (naturalExample) => {
    expect(
      learningExplanationResultSchema.safeParse({ ...legacy, naturalExample })
        .success,
    ).toBe(false);
  });
});
