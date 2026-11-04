import { describe, expect, it, vi } from "vitest";

import { HttpRefundProvider } from "./http-refund-provider.js";

describe("HttpRefundProvider", () => {
  it("uses the allocation id as the provider idempotency key", async () => {
    const fetcher = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ providerRefundId: "provider-refund-1" }),
    });
    const provider = new HttpRefundProvider(
      { endpoint: "https://payment.example.test/refunds", apiKey: "secret" },
      fetcher,
    );

    await expect(
      provider.refund({
        refundRequestId: "refund-1",
        allocationId: "allocation-1",
        merchantOrderId: "order-1",
        providerOrderId: "provider-order-1",
        paymentMethod: "WECHAT_PAY",
        amountFen: 500,
        currency: "CNY",
      }),
    ).resolves.toEqual({ providerRefundId: "provider-refund-1" });
    expect(fetcher).toHaveBeenCalledWith(
      "https://payment.example.test/refunds",
      expect.objectContaining({
        method: "POST",
        headers: expect.objectContaining({
          "idempotency-key": "allocation-1",
        }),
      }),
    );
  });

  it("fails closed on malformed provider responses", async () => {
    const provider = new HttpRefundProvider(
      { endpoint: "https://payment.example.test/refunds", apiKey: "secret" },
      vi.fn().mockResolvedValue({ ok: true, json: async () => ({}) }),
    );
    await expect(
      provider.refund({
        refundRequestId: "refund-1",
        allocationId: "allocation-1",
        merchantOrderId: "order-1",
        providerOrderId: "provider-order-1",
        paymentMethod: "WECHAT_PAY",
        amountFen: 500,
        currency: "CNY",
      }),
    ).rejects.toThrow("Invalid refund gateway response");
  });
});
