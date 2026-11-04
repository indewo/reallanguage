import { describe, expect, it } from "vitest";
import { learningTargetSchema } from "./index.js";

const evidence = {
  version: 1,
  startUtf16: 2,
  endUtf16: 14,
  surfaceForm: "used to live",
  normalizedForm: "used to + verb",
  allowedForms: ["used to live"],
};
const target = {
  targetId: "t",
  unitId: "u",
  display: "used to + verb",
  explanationZh: "过去经常",
  type: "PATTERN",
  structureParts: ["used to", "verb"],
  relatedVocabulary: [],
  selectedByDefault: true,
};
describe("learning target evidence wire compatibility", () => {
  it.each([[" "], ["used to", "\n"], ["\u00a0\u3000"], [""]])(
    "rejects explicitly blank structure parts %j without repairing them",
    (...structureParts) => {
      expect(
        learningTargetSchema.safeParse({ ...target, structureParts }).success,
      ).toBe(false);
    },
  );
  it("requires structure for a new evidenced pattern but preserves legacy absence", () => {
    expect(
      learningTargetSchema.safeParse({
        ...target,
        sourceEvidence: evidence,
        structureParts: [],
      }).success,
    ).toBe(false);
    for (const sourceEvidence of [null, undefined]) {
      const legacy = { ...target, sourceEvidence, structureParts: [] };
      expect(learningTargetSchema.parse(legacy)).toEqual(legacy);
    }
  });
  it("preserves real structure text and order without imposing a quota", () => {
    const candidate = {
      ...target,
      sourceEvidence: evidence,
      structureParts: ["  used to + 动词原形  "],
    };
    expect(learningTargetSchema.parse(candidate)).toEqual(candidate);
    for (const type of ["WORD", "FIXED_EXPRESSION"]) {
      const item = { ...target, type, structureParts: [] };
      expect(learningTargetSchema.parse(item)).toEqual(item);
    }
  });
  const word = (display: string, surfaceForm: string) => ({
    ...target,
    type: "WORD",
    display,
    structureParts: [],
    sourceEvidence: {
      ...evidence,
      startUtf16: 16,
      endUtf16: 16 + surfaceForm.length,
      surfaceForm,
      normalizedForm: display,
      allowedForms: [surfaceForm],
    },
  });
  it.each([
    ["tea", "coffee"],
    ["work", "worked"],
    ["tea and coffee", "tea and coffee"],
  ])("rejects a WORD display/source mismatch %s / %s", (display, surface) => {
    expect(
      learningTargetSchema.safeParse(word(display!, surface!)).success,
    ).toBe(false);
  });
  it.each(["Worked", "I‘m", "I’m", "Iʼm", "I＇m", "part-time"])(
    "keeps exact word display %s, with lemma metadata independent",
    (surface) => {
      const candidate = word(surface.toLowerCase(), surface);
      candidate.sourceEvidence.normalizedForm = "lemma metadata";
      expect(learningTargetSchema.parse(candidate)).toEqual(candidate);
    },
  );
  it("preserves abstract fixed-expression labels and old WORD responses without guessed evidence", () => {
    const candidate = {
      ...target,
      type: "FIXED_EXPRESSION",
      display: "work up the courage",
      sourceEvidence: {
        ...evidence,
        startUtf16: 0,
        endUtf16: 21,
        surfaceForm: "worked up the courage",
        normalizedForm: "work up the courage",
        allowedForms: ["worked up the courage"],
      },
    };
    expect(learningTargetSchema.parse(candidate)).toEqual(candidate);
    expect(
      learningTargetSchema.parse({ ...target, type: "WORD", display: "work" })
        .sourceEvidence,
    ).toBeUndefined();
  });
  it("does not strip source evidence from a new API response", () => {
    expect(
      learningTargetSchema.parse({ ...target, sourceEvidence: evidence }),
    ).toEqual({ ...target, sourceEvidence: evidence });
  });
  it.each([
    { startUtf16: "2" },
    { endUtf16: 2 },
    { normalizedForm: " " },
    { allowedForms: ["lived"] },
    { surfaceForm: "" },
    { version: 2 },
  ])("rejects malformed evidence %j", (change) => {
    expect(
      learningTargetSchema.safeParse({
        ...target,
        sourceEvidence: { ...evidence, ...change },
      }).success,
    ).toBe(false);
  });
  it("keeps old responses readable without inventing evidence", () => {
    expect(learningTargetSchema.parse(target)).toEqual(target);
    expect(
      learningTargetSchema.parse({ ...target, sourceEvidence: null }),
    ).toEqual({ ...target, sourceEvidence: null });
  });
});
