import { createHmac } from "node:crypto";
import { describe, expect, it } from "vitest";
import { HmacPaymentWebhookVerifier } from "./hmac-payment-webhook-verifier.js";

describe("HmacPaymentWebhookVerifier", () => {
  const key = Buffer.alloc(32, 7);
  const timestamp = "1788300000";
  const payload = Buffer.from(
    JSON.stringify({
      providerEventId: "event",
      providerOrderId: "provider",
      merchantOrderId: "order",
      paymentMethod: "WECHAT_PAY",
      amountFen: 5000,
      currency: "CNY",
      status: "PAID",
    }),
  ).toString("base64");
  const signature = createHmac("sha256", key)
    .update(`${timestamp}.${payload}`)
    .digest("base64");
  const verifier = new HmacPaymentWebhookVerifier(
    { key },
    { nowEpochSeconds: () => 1788300000 },
  );
  it("accepts only a fresh correctly signed structured payment event", () => {
    expect(
      verifier.verify({ payloadBase64: payload, signature, timestamp }),
    ).toMatchObject({
      merchantOrderId: "order",
      amountFen: 5000,
      status: "PAID",
    });
    expect(
      verifier.verify({
        payloadBase64: payload,
        signature: Buffer.alloc(32).toString("base64"),
        timestamp,
      }),
    ).toBeNull();
  });
  it("rejects stale callbacks before parsing payment facts", () => {
    expect(
      new HmacPaymentWebhookVerifier(
        { key },
        { nowEpochSeconds: () => 1788301000 },
      ).verify({ payloadBase64: payload, signature, timestamp }),
    ).toBeNull();
  });
});
