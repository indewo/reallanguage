import { describe, expect, it } from "vitest";
import {
  learningExplanationRequestSchema,
  learningExplanationResultSchema,
} from "./index.js";
const evidence = {
  version: 1,
  startUtf16: 8,
  endUtf16: 11,
  surfaceForm: "tea",
  normalizedForm: "tea",
  allowedForms: ["tea"],
};
const word = {
  targetId: "locked-tea",
  type: "WORD",
  display: "tea",
  explanationZh: "茶",
  structureParts: [],
  sourceEvidence: evidence,
  relatedVocabulary: [],
};
const base = {
  chapter: {
    chapterId: "c1",
    sentences: [
      {
        sentenceId: "s1",
        textEn: "I drink tea.",
        textZh: "我喝茶。",
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
describe("locked learning explanation context", () => {
  it("preserves an unknown legacy pattern without inventing structure parts", () => {
    const value = {
      ...base,
      lockedTargets: [
        {
          ...word,
          type: "PATTERN",
          sourceEvidence: null,
          relatedVocabulary: null,
        },
      ],
    };
    expect(learningExplanationRequestSchema.parse(value)).toEqual(value);
  });
  it("rejects a source-bound new pattern without structure parts", () => {
    expect(
      learningExplanationRequestSchema.safeParse({
        ...base,
        lockedTargets: [{ ...word, type: "PATTERN" }],
      }).success,
    ).toBe(false);
  });
  it("retains selected targets and exact clicked position", () => {
    const value = { ...base, lockedTargets: [word], requestedExpression };
    expect(learningExplanationRequestSchema.parse(value)).toEqual(value);
  });
  it.each([
    {},
    { lockedTargets: [] },
    {
      lockedTargets: [
        { ...word, sourceEvidence: null, relatedVocabulary: null },
      ],
    },
  ])(
    "keeps legacy unknown versus explicit empty context distinct %j",
    (extra) => {
      expect(
        learningExplanationRequestSchema.parse({ ...base, ...extra }),
      ).toEqual({ ...base, ...extra });
    },
  );
  it.each([
    null,
    {},
    [{ ...word, type: "UNKNOWN" }],
    [{ ...word, explanationZh: " " }],
    [{ ...word, extra: "invented" }],
    [{ ...word, sourceEvidence: undefined }],
    [{ ...word, relatedVocabulary: [{ display: "tea", senseZh: "茶" }] }],
  ])("rejects incomplete locked targets %j", (lockedTargets) => {
    expect(
      learningExplanationRequestSchema.safeParse({ ...base, lockedTargets })
        .success,
    ).toBe(false);
  });
  it.each([
    { expressionId: "word-2", display: "tea", startUtf16: 8 },
    { expressionId: "word-2", display: "tea", endUtf16: 11 },
    { ...requestedExpression, startUtf16: 8.5 },
  ])("rejects half or invalid positional groups %j", (requestedExpression) => {
    expect(
      learningExplanationRequestSchema.safeParse({
        ...base,
        requestedExpression,
      }).success,
    ).toBe(false);
  });
  it("does not strip the raw locked contextual meaning at the response boundary", () => {
    const value = {
      kind: "EXPRESSION",
      expressionId: "word-2",
      explanationZh: "该词的说明。",
      phonetic: null,
      contextualMeaningZh: " 茶 ",
      usageZh: "这里指饮品。",
      expressionHintZh: null,
    };
    expect(learningExplanationResultSchema.parse(value)).toEqual(value);
  });
});
