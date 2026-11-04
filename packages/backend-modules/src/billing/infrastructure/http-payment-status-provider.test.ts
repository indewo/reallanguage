import { describe, expect, it, vi } from "vitest";
import { HttpPaymentStatusProvider } from "./http-payment-status-provider.js";

const order = {
  merchantOrderId: "order-1",
  providerOrderId: "provider-1",
  paymentMethod: "WECHAT_PAY" as const,
  amountFen: 5_000,
};

describe("HttpPaymentStatusProvider", () => {
  it("queries the controlled gateway with a stable identity", async () => {
    const fetcher = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ providerEventId: "query-1", status: "PAID" }),
    });
    const provider = new HttpPaymentStatusProvider(
      { endpoint: "https://payments.example/status", apiKey: "secret" },
      fetcher,
    );
    await expect(provider.query(order)).resolves.toEqual({
      providerEventId: "query-1",
      status: "PAID",
    });
    expect(fetcher).toHaveBeenCalledWith(
      "https://payments.example/status",
      expect.objectContaining({
        headers: expect.objectContaining({
          "idempotency-key": "query:order-1",
        }),
      }),
    );
  });

  it("fails closed on an unknown state", async () => {
    const provider = new HttpPaymentStatusProvider(
      { endpoint: "https://payments.example/status", apiKey: "secret" },
      vi.fn().mockResolvedValue({
        ok: true,
        json: async () => ({ providerEventId: "query-1", status: "UNKNOWN" }),
      }),
    );
    await expect(provider.query(order)).rejects.toThrow(
      "Invalid payment status",
    );
  });
});
