import { describe, expect, it, vi } from "vitest";
import { OpenAiLocalClient } from "./openai-metered-provider.js";
describe("exact existing learning target generation contract", () => {
  it("requires nullable explicit identity and preserves facts and old sense instead of filling card quotas", async () => {
    const fetcher = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          output_text: JSON.stringify({
            targets: [],
            queryableExpressions: [],
          }),
        }),
      ),
    );
    const input = {
      chapters: [
        {
          chapterId: "c1",
          sentences: [
            {
              unitId: "u1",
              reusedKnowledge: [
                {
                  ref: "PATTERN:p1",
                  type: "PATTERN",
                  display: "used to + verb",
                  meaningZh: "过去的习惯",
                },
              ],
            },
          ],
        },
      ],
    };
    await new OpenAiLocalClient(
      "isolated-test-only",
      "same-model",
      fetcher,
    ).generateJson("LEARNING_TARGET_EXTRACTION_V1", input);
    const body = JSON.parse(String(fetcher.mock.calls[0]![1].body));
    const item = body.text.format.schema.properties.targets.items;
    expect(item.properties.reusedKnowledgeRef).toEqual({
      type: ["string", "null"],
    });
    expect(item.required).toContain("reusedKnowledgeRef");
    expect(body.instructions).toContain("reusedKnowledge");
    expect(body.instructions).toContain("exact meaningZh");
    expect(body.instructions).toContain("not a quota");
    expect(body.model).toBe("same-model");
    expect(JSON.parse(body.input).input).toEqual(input);
    expect(fetcher).toHaveBeenCalledTimes(1);
  });
});
