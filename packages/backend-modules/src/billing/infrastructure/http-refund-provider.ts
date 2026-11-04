import type { RefundProvider } from "../application/process-refund.js";
import type {
  PaymentGatewayConfig,
  PaymentGatewayFetch,
} from "./http-payment-order-provider.js";

export class HttpRefundProvider implements RefundProvider {
  public constructor(
    private readonly config: PaymentGatewayConfig,
    private readonly fetcher: PaymentGatewayFetch = fetch,
  ) {}

  public async refund(input: Parameters<RefundProvider["refund"]>[0]) {
    const response = await this.fetcher(this.config.endpoint, {
      method: "POST",
      headers: {
        authorization: `Bearer ${this.config.apiKey}`,
        "content-type": "application/json",
        "idempotency-key": input.allocationId,
      },
      body: JSON.stringify(input),
    });
    if (!response.ok) throw new Error("Refund gateway rejected request");
    const value = await response.json();
    if (!isResult(value)) throw new Error("Invalid refund gateway response");
    return value;
  }
}

const isResult = (value: unknown): value is { providerRefundId: string } => {
  if (!value || typeof value !== "object") return false;
  const providerRefundId = (value as Record<string, unknown>).providerRefundId;
  return typeof providerRefundId === "string" && providerRefundId.length > 0;
};
