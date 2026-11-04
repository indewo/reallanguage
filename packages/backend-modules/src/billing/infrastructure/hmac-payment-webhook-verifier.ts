import { createHmac, timingSafeEqual } from "node:crypto";
import type {
  PaymentEvent,
  PaymentEventEnvelope,
  PaymentWebhookVerifier,
} from "../application/apply-payment-event.js";

export type PaymentWebhookSecret = Readonly<{ key: Uint8Array }>;
export interface PaymentWebhookClock {
  nowEpochSeconds(): number;
}
export class HmacPaymentWebhookVerifier implements PaymentWebhookVerifier {
  public constructor(
    private readonly secret: PaymentWebhookSecret,
    private readonly clock: PaymentWebhookClock,
    private readonly allowedSkewSeconds = 300,
  ) {}
  public verify(envelope: PaymentEventEnvelope): PaymentEvent | null {
    const timestamp = Number(envelope.timestamp);
    if (
      !Number.isSafeInteger(timestamp) ||
      Math.abs(this.clock.nowEpochSeconds() - timestamp) >
        this.allowedSkewSeconds
    )
      return null;
    const expected = createHmac("sha256", this.secret.key)
      .update(`${envelope.timestamp}.${envelope.payloadBase64}`)
      .digest();
    const actual = decodeBase64(envelope.signature);
    if (
      !actual ||
      actual.length !== expected.length ||
      !timingSafeEqual(actual, expected)
    )
      return null;
    const payload = decodeBase64(envelope.payloadBase64);
    if (!payload) return null;
    try {
      const value = JSON.parse(payload.toString("utf8")) as unknown;
      return isEvent(value) ? value : null;
    } catch {
      return null;
    }
  }
}
const decodeBase64 = (value: string) => {
  if (!/^[A-Za-z0-9+/]+={0,2}$/.test(value)) return null;
  return Buffer.from(value, "base64");
};
const isEvent = (value: unknown): value is PaymentEvent => {
  if (!value || typeof value !== "object") return false;
  const event = value as Record<string, unknown>;
  return (
    typeof event.providerEventId === "string" &&
    event.providerEventId.length > 0 &&
    typeof event.providerOrderId === "string" &&
    event.providerOrderId.length > 0 &&
    typeof event.merchantOrderId === "string" &&
    event.merchantOrderId.length > 0 &&
    (event.paymentMethod === "WECHAT_PAY" ||
      event.paymentMethod === "ALIPAY") &&
    Number.isSafeInteger(event.amountFen) &&
    Number(event.amountFen) > 0 &&
    event.currency === "CNY" &&
    ["IN_PROGRESS", "PAID", "FAILED", "CANCELLED"].includes(
      String(event.status),
    )
  );
};
