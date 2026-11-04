import { describe, expect, it } from "vitest";
import { learningTargetSchema } from "./index.js";
import { readFile } from "node:fs/promises";
import { parse } from "yaml";

const target = {
  targetId: "tea-target",
  unitId: "tea-unit",
  display: "tea",
  explanationZh: "茶",
  type: "WORD",
  structureParts: [],
  relatedVocabulary: [],
  selectedByDefault: true,
};
const example = {
  textEn: "She drinks tea after lunch.",
  textZh: "她午饭后喝茶。",
};

describe("learning target candidate example compatibility", () => {
  it("documents the same optional public example and strict bilingual object in OpenAPI", async () => {
    const document = parse(
      await readFile(
        new URL("../openapi/real-language-v1.yaml", import.meta.url),
        "utf8",
      ),
    );
    const target =
      document.components.schemas.LearningTargetExtractionResult.properties
        .targets.items;
    expect(target.properties.candidateExample).toEqual({
      type: ["object", "null"],
      additionalProperties: false,
      required: ["textEn", "textZh"],
      properties: {
        textEn: { type: "string", pattern: "\\S" },
        textZh: { type: "string", pattern: "\\S" },
      },
    });
    expect(target.required ?? []).not.toContain("candidateExample");
  });
  it("preserves one complete bilingual candidate example without making it a formal knowledge example", () => {
    expect(
      learningTargetSchema.parse({ ...target, candidateExample: example }),
    ).toEqual({ ...target, candidateExample: example });
  });
  it.each([{}, { candidateExample: null }])(
    "reads an older delivery without inventing an example: %j",
    (extra) => {
      expect(learningTargetSchema.parse({ ...target, ...extra })).toEqual({
        ...target,
        ...extra,
      });
    },
  );
  it.each(
    [
      {},
      { textEn: example.textEn },
      { textZh: example.textZh },
      { ...example, textEn: " \n" },
      { ...example, textZh: "\u00a0\u3000" },
      { ...example, textEn: 1 },
      { ...example, textZh: null },
      { ...example, pronunciationText: "extra" },
      [],
      "not an object",
    ].map((value) => [value]),
  )("rejects malformed candidate example %j", (candidateExample) => {
    expect(
      learningTargetSchema.safeParse({ ...target, candidateExample }).success,
    ).toBe(false);
  });
});
