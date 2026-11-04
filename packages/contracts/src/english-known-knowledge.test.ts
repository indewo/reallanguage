import { describe, expect, it } from "vitest";
import { readFile } from "node:fs/promises";
import { parse } from "yaml";
import { englishCandidateGenerationRequestSchema } from "./index.js";

const base = {
  sourceType: "DIARY",
  units: [{ unitId: "u1", order: 0, textZh: "我以前步行去学校。" }],
  chapters: [{ chapterId: "c1", unitIds: ["u1"] }],
  selectedUnitIds: ["u1"],
  commandId: "cmd",
};
const item = {
  ref: "PATTERN:p1",
  type: "PATTERN",
  display: "used to + verb",
  meaningZh: "表示过去习惯",
};
describe("English known knowledge wire input", () => {
  it("documents the same optional bounded known input in the published API", async () => {
    const document = parse(
      await readFile(
        new URL("../openapi/real-language-v1.yaml", import.meta.url),
        "utf8",
      ),
    );
    const request =
      document.components.schemas.EnglishCandidateGenerationRequest;
    expect(request.required).not.toContain("knownKnowledge");
    expect(request.properties.knownKnowledge).toMatchObject({
      type: "array",
      maxItems: 16,
      items: { $ref: "#/components/schemas/KnownKnowledgeCandidate" },
    });
    expect(document.components.schemas.KnownKnowledgeCandidate).toMatchObject({
      additionalProperties: false,
      required: ["ref", "type", "display", "meaningZh"],
    });
  });
  it("preserves the old no-knowledge request and the explicit ordered known snapshot", () => {
    expect(englishCandidateGenerationRequestSchema.parse(base)).toEqual(base);
    expect(
      englishCandidateGenerationRequestSchema.parse({
        ...base,
        knownKnowledge: [item],
      }),
    ).toEqual({ ...base, knownKnowledge: [item] });
  });
  it.each([
    null,
    [item, item],
    [{ ...item, type: "OTHER" }],
    [{ ...item, ref: "VOCABULARY:p1" }],
    [{ ...item, meaningZh: " " }],
    Array.from({ length: 17 }, (_, i) => ({ ...item, ref: `PATTERN:p${i}` })),
    [{ ...item, display: "x".repeat(4001) }],
  ])("rejects malformed or over-budget knowledge %j", (knownKnowledge) => {
    expect(
      englishCandidateGenerationRequestSchema.safeParse({
        ...base,
        knownKnowledge,
      }).success,
    ).toBe(false);
  });
});
