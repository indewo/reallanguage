import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import { learningTargetSchema } from "./index.js";

const evidence = {
  version: 1,
  startUtf16: 8,
  endUtf16: 11,
  surfaceForm: "tea",
  normalizedForm: "tea",
  allowedForms: ["tea"],
};
const item = {
  itemRef: "local-tea",
  unitId: "u1",
  display: "tea",
  senseZh: "茶",
  unitType: "WORD",
  sourceEvidence: evidence,
};
const base = {
  targetId: "p1",
  unitId: "u1",
  display: "drink X and Y",
  explanationZh: "喝两种饮品",
  type: "PATTERN",
  structureParts: ["动词与并列宾语"],
  relatedVocabulary: ["tea"],
  selectedByDefault: true,
};
const target = {
  ...base,
  relatedVocabularyItems: [item],
  relatedVocabularyItemRefs: [item.itemRef],
};

describe("related vocabulary snapshot contract", () => {
  it("retains the full explanation snapshot and local reference", () => {
    expect(learningTargetSchema.parse(target)).toEqual(target);
  });
  it.each([
    {},
    { relatedVocabularyItems: null, relatedVocabularyItemRefs: null },
  ])("keeps explicit legacy delivery %j", (extra) => {
    expect(learningTargetSchema.parse({ ...base, ...extra })).toEqual({
      ...base,
      ...extra,
    });
  });
  it("allows genuinely empty related items without a quota", () => {
    const value = {
      ...base,
      relatedVocabulary: [],
      relatedVocabularyItems: [],
      relatedVocabularyItemRefs: [],
    };
    expect(learningTargetSchema.parse(value)).toEqual(value);
  });
  it.each([
    { relatedVocabularyItems: [item] },
    { relatedVocabularyItemRefs: [item.itemRef] },
    { relatedVocabularyItems: null },
    { relatedVocabularyItems: [item], relatedVocabularyItemRefs: null },
    { relatedVocabularyItems: [], relatedVocabularyItemRefs: ["missing"] },
    { relatedVocabularyItems: [item], relatedVocabularyItemRefs: [] },
    {
      relatedVocabularyItems: [item],
      relatedVocabularyItemRefs: [item.itemRef, item.itemRef],
    },
    {
      relatedVocabularyItems: [item, item],
      relatedVocabularyItemRefs: [item.itemRef],
    },
    {
      relatedVocabularyItems: [{ ...item, senseZh: " " }],
      relatedVocabularyItemRefs: [item.itemRef],
    },
    {
      relatedVocabularyItems: [{ ...item, unitType: "PATTERN" }],
      relatedVocabularyItemRefs: [item.itemRef],
    },
    {
      relatedVocabularyItems: [{ ...item, unitId: "u2" }],
      relatedVocabularyItemRefs: [item.itemRef],
    },
    {
      relatedVocabularyItems: [{ ...item, display: "coffee" }],
      relatedVocabularyItemRefs: [item.itemRef],
    },
    {
      relatedVocabularyItems: [{ ...item, knowledgeId: "invented" }],
      relatedVocabularyItemRefs: [item.itemRef],
    },
    { relatedVocabularyItems: "tea", relatedVocabularyItemRefs: [] },
    { relatedVocabularyItems: [item], relatedVocabularyItemRefs: [2] },
  ])(
    "rejects an incomplete, unresolvable, or inconsistent group %j",
    (extra) => {
      expect(
        learningTargetSchema.safeParse({ ...base, ...extra }).success,
      ).toBe(false);
    },
  );
  it("rejects a legacy English list inconsistent with the resolved reference order", () => {
    expect(
      learningTargetSchema.safeParse({
        ...target,
        relatedVocabulary: ["coffee"],
      }).success,
    ).toBe(false);
  });
  it("does not allow a WORD to acquire pattern-only related targets", () => {
    expect(
      learningTargetSchema.safeParse({
        ...target,
        type: "WORD",
        display: "tea",
        sourceEvidence: evidence,
      }).success,
    ).toBe(false);
  });
  it("documents the group in public OpenAPI", () => {
    const yaml = readFileSync(
      new URL("../openapi/real-language-v1.yaml", import.meta.url),
      "utf8",
    );
    expect(yaml).toContain("relatedVocabularyItems:");
    expect(yaml).toContain("relatedVocabularyItemRefs:");
    expect(yaml).toContain("RelatedVocabularyItem:");
  });
});
