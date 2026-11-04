import { describe, expect, it, vi } from "vitest";
import { OpenAiLocalClient } from "./openai-metered-provider.js";

describe("English generation known-knowledge provider contract", () => {
  const input = {
    knownKnowledge: [
      {
        ref: "PATTERN:p1",
        type: "PATTERN",
        display: "used to + verb",
        meaningZh: "过去的习惯",
      },
    ],
    units: [{ unitId: "u1", textZh: "我以前常常步行去学校。" }],
  };
  const request = async () => {
    const fetcher = vi
      .fn()
      .mockResolvedValue(
        new Response(
          JSON.stringify({ output_text: JSON.stringify({ sentences: [] }) }),
        ),
      );
    await new OpenAiLocalClient("test-key", "test-model", fetcher).generateJson(
      "ENGLISH_CANDIDATE_GENERATION_V1",
      input,
    );
    expect(fetcher).toHaveBeenCalledTimes(1);
    expect(fetcher.mock.calls[0]![0]).toBe(
      "https://api.openai.com/v1/responses",
    );
    return JSON.parse(String(fetcher.mock.calls[0]![1].body));
  };
  it("uses a strict complete existing candidate output schema without changing models", async () => {
    const body = await request();
    expect(body.model).toBe("test-model");
    expect(body.text.format).toMatchObject({
      type: "json_schema",
      strict: true,
    });
    const schema = body.text.format.schema;
    expect(schema.additionalProperties).toBe(false);
    expect(schema.required).toEqual(["sentences"]);
    const candidate =
      schema.properties.sentences.items.properties.candidates.items;
    expect(candidate.additionalProperties).toBe(false);
    expect(candidate.required).toEqual(Object.keys(candidate.properties));
    expect(candidate.properties.reusedKnowledgeRefs).toEqual({
      type: "array",
      items: { type: "string" },
    });
    expect(candidate.properties.fidelity.required).toContain("noHallucination");
  });
  it("supplies the exact known input and only permits evidence-based natural reuse, not quotas", async () => {
    const body = await request();
    expect(JSON.parse(body.input).input).toEqual(input);
    expect(body.instructions).toContain(
      "knownKnowledge is an ordered allowlist",
    );
    expect(body.instructions).toContain(
      "Original meaning and natural expression take priority",
    );
    expect(body.instructions).toContain("empty reusedKnowledgeRefs");
    expect(body.instructions).toContain("actually used in that candidate");
    expect(body.instructions).toContain("not a quota");
  });
});
