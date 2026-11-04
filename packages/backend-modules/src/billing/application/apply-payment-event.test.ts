import { describe, expect, it, vi } from "vitest";
import { ApplyPaymentEvent } from "./apply-payment-event.js";

describe("ApplyPaymentEvent", () => {
  const envelope = {
    payloadBase64: "payload",
    signature: "signature",
    timestamp: "1",
  };
  it("rejects an invalid signature before touching the ledger", async () => {
    const apply = vi.fn();
    await expect(
      new ApplyPaymentEvent({ verify: () => null }, { apply }).execute(
        envelope,
      ),
    ).resolves.toBe(false);
    expect(apply).not.toHaveBeenCalled();
  });
  it("passes one verified provider event to atomic settlement", async () => {
    const event = {
      providerEventId: "event",
      providerOrderId: "provider",
      merchantOrderId: "order",
      paymentMethod: "ALIPAY" as const,
      amountFen: 5000,
      currency: "CNY" as const,
      status: "PAID" as const,
    };
    const apply = vi.fn().mockResolvedValue(true);
    await expect(
      new ApplyPaymentEvent({ verify: () => event }, { apply }).execute(
        envelope,
      ),
    ).resolves.toBe(true);
    expect(apply).toHaveBeenCalledWith(event);
  });
});
