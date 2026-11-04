import type { PaymentOrderProvider } from "../application/manage-recharge.js";

export type PaymentGatewayConfig = Readonly<{
  endpoint: string;
  apiKey: string;
}>;
export interface PaymentGatewayResponse {
  ok: boolean;
  json(): Promise<unknown>;
}
export type PaymentGatewayFetch = (
  input: string,
  init: { method: "POST"; headers: Record<string, string>; body: string },
) => Promise<PaymentGatewayResponse>;

export class HttpPaymentOrderProvider implements PaymentOrderProvider {
  public constructor(
    private readonly config: PaymentGatewayConfig,
    private readonly fetcher: PaymentGatewayFetch = fetch,
  ) {}
  public async create(input: Parameters<PaymentOrderProvider["create"]>[0]) {
    const response = await this.fetcher(this.config.endpoint, {
      method: "POST",
      headers: {
        authorization: `Bearer ${this.config.apiKey}`,
        "content-type": "application/json",
        "idempotency-key": input.merchantOrderId,
      },
      body: JSON.stringify(input),
    });
    if (!response.ok) throw new Error("Payment gateway rejected order");
    const value = await response.json();
    if (!isResult(value)) throw new Error("Invalid payment gateway response");
    return value;
  }
}
const isResult = (
  value: unknown,
): value is { providerOrderId: string; paymentLaunch: string } => {
  if (!value || typeof value !== "object") return false;
  const candidate = value as Record<string, unknown>;
  return (
    typeof candidate.providerOrderId === "string" &&
    candidate.providerOrderId.length > 0 &&
    typeof candidate.paymentLaunch === "string" &&
    candidate.paymentLaunch.length > 0
  );
};
