import { describe, expect, it, vi } from "vitest";

import { HttpMeteredProviderGateway } from "./http-metered-provider-gateway.js";

const command = {
  accountId: "account-1",
  operationId: "operation-1",
  operationLabelZh: "生成英语表达",
  commandId: "command-1",
  task: "ENGLISH_CANDIDATE_GENERATION_V1",
  callType: "INITIAL_CALL" as const,
  input: { text: "你好" },
};

describe("HttpMeteredProviderGateway", () => {
  it("uses a free quote before a separately idempotent execution", async () => {
    const fetcher = vi
      .fn()
      .mockResolvedValueOnce({
        ok: true,
        json: async () => ({
          providerAttemptId: "attempt-1",
          maximumChargeFen: 80,
        }),
      })
      .mockResolvedValueOnce({
        ok: true,
        json: async () => ({
          output: { candidates: [] },
          receipt: {
            providerAttemptId: "attempt-1",
            modelSource: "provider/model",
            providerCostMicroCny: 112_000,
          },
        }),
      });
    const gateway = new HttpMeteredProviderGateway(
      { endpoint: "https://provider.example.test/tasks", apiKey: "secret" },
      fetcher,
    );

    await expect(gateway.quote(command)).resolves.toMatchObject({
      providerAttemptId: "attempt-1",
    });
    await expect(
      gateway.execute({ ...command, providerAttemptId: "attempt-1" }),
    ).resolves.toMatchObject({
      receipt: { providerAttemptId: "attempt-1" },
    });
    expect(fetcher.mock.calls[0]?.[1]?.headers).toMatchObject({
      "Idempotency-Key": "command-1:quote",
    });
    expect(fetcher.mock.calls[1]?.[1]?.headers).toMatchObject({
      "Idempotency-Key": "attempt-1",
    });
    expect(
      JSON.parse(fetcher.mock.calls[0]?.[1]?.body as string),
    ).toMatchObject({ phase: "QUOTE" });
    expect(
      JSON.parse(fetcher.mock.calls[1]?.[1]?.body as string),
    ).toMatchObject({ phase: "EXECUTE", providerAttemptId: "attempt-1" });
  });

  it("rejects malformed quote and cost receipts", async () => {
    const malformedQuote = new HttpMeteredProviderGateway(
      { endpoint: "https://provider.example.test/tasks", apiKey: "secret" },
      vi.fn().mockResolvedValue({ ok: true, json: async () => ({}) }),
    );
    await expect(malformedQuote.quote(command)).rejects.toThrow(
      "Invalid provider quote",
    );

    const malformedReceipt = new HttpMeteredProviderGateway(
      { endpoint: "https://provider.example.test/tasks", apiKey: "secret" },
      vi.fn().mockResolvedValue({
        ok: true,
        json: async () => ({ output: {}, receipt: {} }),
      }),
    );
    await expect(
      malformedReceipt.execute({ ...command, providerAttemptId: "attempt-1" }),
    ).rejects.toThrow("Invalid provider execution receipt");
  });
});
