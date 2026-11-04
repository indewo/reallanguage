import { describe, expect, it, vi } from "vitest";
import { HttpPaymentOrderProvider } from "./http-payment-order-provider.js";

describe("HttpPaymentOrderProvider", () => {
  it("uses a stable merchant idempotency key and validates the gateway result", async () => {
    const fetcher = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        providerOrderId: "provider-1",
        paymentLaunch: "alipays://launch",
      }),
    });
    const result = await new HttpPaymentOrderProvider(
      { endpoint: "https://pay.example/orders", apiKey: "secret" },
      fetcher,
    ).create({
      merchantOrderId: "order-1",
      amountFen: 5000,
      currency: "CNY",
      paymentMethod: "ALIPAY",
    });
    expect(result.providerOrderId).toBe("provider-1");
    expect(fetcher).toHaveBeenCalledWith(
      "https://pay.example/orders",
      expect.objectContaining({
        headers: expect.objectContaining({ "idempotency-key": "order-1" }),
      }),
    );
  });
  it("fails closed for malformed or unsuccessful gateway responses", async () => {
    const failed = vi
      .fn()
      .mockResolvedValue({ ok: false, json: async () => ({}) });
    await expect(
      new HttpPaymentOrderProvider(
        { endpoint: "https://pay.example", apiKey: "secret" },
        failed,
      ).create({
        merchantOrderId: "order",
        amountFen: 100,
        currency: "CNY",
        paymentMethod: "WECHAT_PAY",
      }),
    ).rejects.toThrow();
  });
});
