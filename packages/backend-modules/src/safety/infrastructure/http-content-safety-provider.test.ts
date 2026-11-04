import { describe, expect, it, vi } from "vitest";

import { HttpContentSafetyProvider } from "./http-content-safety-provider.js";

describe("HttpContentSafetyProvider", () => {
  it("sends the versioned task with stable provider idempotency", async () => {
    const fetch = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          decision: "PASS",
          policyVersion: "policy-1",
          providerOperationId: "provider-1",
          contextConsidered: true,
        }),
        { status: 200 },
      ),
    );
    const provider = new HttpContentSafetyProvider(
      { endpoint: "https://safety.example.test/check", apiKey: "secret" },
      fetch,
    );

    await expect(
      provider.check({
        accountId: "account-1",
        operationId: "operation-1",
        text: "完整上下文",
        sourceType: "DIARY",
        contentShape: "ARTICLE",
      }),
    ).resolves.toMatchObject({ decision: "PASS" });
    expect(fetch).toHaveBeenCalledWith(
      "https://safety.example.test/check",
      expect.objectContaining({
        headers: expect.objectContaining({ "Idempotency-Key": "operation-1" }),
      }),
    );
  });

  it("rejects a blocked span that does not exactly reference the source", async () => {
    const fetch = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          decision: "BLOCK",
          policyVersion: "policy-1",
          providerOperationId: "provider-1",
          reasonCode: "CONFIRMED_RISK",
          userReasonZh: "明确风险",
          confidence: 0.98,
          contextConsidered: true,
          riskSpans: [
            {
              startCodePoint: 0,
              endCodePoint: 2,
              text: "不匹配",
              reasonCode: "RISK",
            },
          ],
        }),
        { status: 200 },
      ),
    );
    const provider = new HttpContentSafetyProvider(
      { endpoint: "https://safety.example.test/check", apiKey: "secret" },
      fetch,
    );

    await expect(
      provider.check({
        accountId: "account-1",
        operationId: "operation-1",
        text: "完整上下文",
        sourceType: "DIARY",
        contentShape: "ARTICLE",
      }),
    ).rejects.toThrow("does not match");
  });
});
