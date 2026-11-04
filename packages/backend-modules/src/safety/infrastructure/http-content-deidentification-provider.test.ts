import { describe, expect, it, vi } from "vitest";
import { HttpContentDeidentificationProvider } from "./http-content-deidentification-provider.js";

describe("HttpContentDeidentificationProvider", () => {
  it("uses the confirmed versioned task and case-scoped idempotency", async () => {
    const fetcher = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        sanitizedText: "联系某人",
        edits: [
          {
            originalSpan: { startCodePoint: 2, endCodePoint: 4 },
            originalText: "张三",
            replacementText: "某人",
            reasonCode: "RISK",
            explanationZh: "隐藏姓名",
          },
        ],
        preservedIntentSummaryZh: "保留联系意图",
        internalSafetyValid: true,
        providerOperationId: "provider",
      }),
    });
    const provider = new HttpContentDeidentificationProvider(
      { endpoint: "https://safety.test/tasks", apiKey: "secret" },
      fetcher,
    );
    await provider.deidentify(
      {
        safetyCaseId: "case-1",
        text: "联系张三",
        sourceType: "DIARY",
        contentShape: "ARTICLE",
        reasonCode: "RISK",
        userReasonZh: "原因",
        riskSpans: [],
      },
      "account-1",
    );
    expect(fetcher).toHaveBeenCalledWith(
      "https://safety.test/tasks",
      expect.objectContaining({
        headers: expect.objectContaining({
          "Idempotency-Key": "deidentify:case-1",
        }),
        body: expect.stringContaining("CONTENT_DEIDENTIFICATION_V1"),
      }),
    );
  });
});
