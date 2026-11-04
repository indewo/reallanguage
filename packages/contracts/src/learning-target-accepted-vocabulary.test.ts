import { describe, expect, it } from "vitest";
import { learningTargetExtractionRequestSchema } from "./index.js";

const evidence = {
  version: 1,
  startUtf16: 8,
  endUtf16: 11,
  surfaceForm: "tea",
  normalizedForm: "tea",
  allowedForms: ["tea"],
};
const acceptedVocabulary = {
  display: "tea",
  senseZh: "茶",
  unitType: "WORD",
  sourceEvidence: evidence,
};
const base = {
  chapters: [
    {
      chapterId: "c1",
      sentences: [
        {
          unitId: "u1",
          textEn: "I drink tea.",
          textZh: "我喝茶。",
          isFocus: true,
        },
      ],
    },
  ],
  commandId: "accepted-word",
  requestedExpression: { unitId: "u1", expressionId: "word:8", display: "tea" },
};
describe("accepted current-word explanation input", () => {
  it("preserves the full prior explanation without turning it into knowledge identity", () => {
    const value = {
      ...base,
      requestedExpression: { ...base.requestedExpression, acceptedVocabulary },
    };
    expect(learningTargetExtractionRequestSchema.parse(value)).toEqual(value);
  });
  it("keeps older requests without a snapshot compatible", () => {
    expect(learningTargetExtractionRequestSchema.parse(base)).toEqual(base);
  });
  it.each([
    null,
    {},
    { ...acceptedVocabulary, senseZh: " " },
    { ...acceptedVocabulary, senseZh: 3 },
    { ...acceptedVocabulary, unitType: "PATTERN" },
    { ...acceptedVocabulary, sourceEvidence: null },
    {
      ...acceptedVocabulary,
      sourceEvidence: { ...evidence, allowedForms: ["coffee"] },
    },
    { ...acceptedVocabulary, itemRef: "invented" },
    { ...acceptedVocabulary, sourceEvidence: { ...evidence, extra: true } },
  ])("rejects malformed or extra accepted fields %j", (accepted) => {
    expect(
      learningTargetExtractionRequestSchema.safeParse({
        ...base,
        requestedExpression: {
          ...base.requestedExpression,
          acceptedVocabulary: accepted,
        },
      }).success,
    ).toBe(false);
  });
});
