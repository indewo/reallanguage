import { describe, expect, it, vi } from "vitest";
import { HttpSemanticUnitSplitProvider } from "./http-semantic-unit-split-provider.js";

describe("HttpSemanticUnitSplitProvider", () => {
  it("uses the versioned task and stable idempotency identity", async () => {
    const fetcher = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        units: [
          {
            unitId: "u1",
            order: 0,
            sourceSpans: [{ startCodePoint: 0, endCodePoint: 2 }],
            textZh: "你好",
            speakerRole: null,
            semanticPurposeZh: "问候",
            recommendationReasonZh: null,
          },
        ],
      }),
    });
    const provider = new HttpSemanticUnitSplitProvider(
      { endpoint: "https://provider.test/tasks", apiKey: "secret" },
      fetcher,
    );
    await provider.split({
      accountId: "a",
      text: "你好",
      contentShape: "ARTICLE",
      commandId: "cmd-1",
    });
    expect(fetcher).toHaveBeenCalledWith(
      "https://provider.test/tasks",
      expect.objectContaining({
        headers: expect.objectContaining({
          "Idempotency-Key": "cmd-1:minimum-v2",
        }),
        body: expect.stringContaining("SEMANTIC_UNIT_SPLIT_V1"),
      }),
    );
  });
});
