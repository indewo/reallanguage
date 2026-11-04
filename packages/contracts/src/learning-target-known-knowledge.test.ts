import { describe, expect, it } from "vitest";
import {
  learningTargetExtractionRequestSchema,
  learningTargetSchema,
} from "./index.js";
import { readFile } from "node:fs/promises";
import { parse } from "yaml";
const known = {
  ref: "PATTERN:p1",
  type: "PATTERN",
  display: "used to + verb",
  meaningZh: "过去的习惯",
};
const sentence = {
  unitId: "u1",
  textZh: "我以前常步行。",
  textEn: "I used to walk.",
  isFocus: true,
  reusedKnowledge: [known],
};
const request = {
  commandId: "target-known-1",
  chapters: [{ chapterId: "c1", sentences: [sentence] }],
};
const target = {
  targetId: "t1",
  unitId: "u1",
  display: known.display,
  explanationZh: known.meaningZh,
  type: "PATTERN",
  structureParts: ["used to", "+ verb"],
  relatedVocabulary: [],
  selectedByDefault: true,
  reusedKnowledgeRef: known.ref,
};
describe("learning target known-knowledge wire", () => {
  it("documents per-sentence input and nullable output identity in the same published API", async () => {
    const schemas = parse(
      await readFile(
        new URL("../openapi/real-language-v1.yaml", import.meta.url),
        "utf8",
      ),
    ).components.schemas;
    expect(
      schemas.LearningTargetExtractionRequest.properties.chapters.items
        .properties.sentences.items.properties.reusedKnowledge,
    ).toMatchObject({
      type: "array",
      maxItems: 16,
      items: { $ref: "#/components/schemas/KnownKnowledgeCandidate" },
    });
    expect(
      schemas.LearningTargetExtractionResult.properties.targets.items.properties
        .reusedKnowledgeRef,
    ).toEqual({
      type: ["string", "null"],
      maxLength: 128,
      pattern: "^(VOCABULARY|PATTERN):[^\\s]+$",
    });
  });
  it("retains per-sentence known input and target identity instead of stripping them", () => {
    expect(learningTargetExtractionRequestSchema.parse(request)).toEqual(
      request,
    );
    expect(learningTargetSchema.parse(target)).toEqual(target);
  });
  it.each([7, "", "PATTERN:", "VOCABULARY:has space", "other:1"])(
    "rejects malformed target ref %j",
    (ref) => {
      expect(
        learningTargetSchema.safeParse({ ...target, reusedKnowledgeRef: ref })
          .success,
      ).toBe(false);
    },
  );
  it("rejects duplicate, mismatched and oversized known input without silent stripping", () => {
    for (const items of [
      [known, known],
      [{ ...known, type: "VOCABULARY" }],
      [{ ...known, display: "x".repeat(4001) }],
    ]) {
      expect(
        learningTargetExtractionRequestSchema.safeParse({
          ...request,
          chapters: [
            {
              chapterId: "c1",
              sentences: [{ ...sentence, reusedKnowledge: items }],
            },
          ],
        }).success,
      ).toBe(false);
    }
  });
  it("retains backwards compatibility for missing and explicit null refs", () => {
    const legacy = Object.fromEntries(
      Object.entries(target).filter(([key]) => key !== "reusedKnowledgeRef"),
    );
    expect(learningTargetSchema.safeParse(legacy).success).toBe(true);
    expect(
      learningTargetSchema.safeParse({ ...legacy, reusedKnowledgeRef: null })
        .success,
    ).toBe(true);
  });
});
