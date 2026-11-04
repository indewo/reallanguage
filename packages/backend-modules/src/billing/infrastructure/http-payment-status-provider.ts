import type { PaymentStatusProvider } from "../application/reconcile-payments.js";
import type {
  PaymentGatewayConfig,
  PaymentGatewayFetch,
} from "./http-payment-order-provider.js";

export class HttpPaymentStatusProvider implements PaymentStatusProvider {
  public constructor(
    private readonly config: PaymentGatewayConfig,
    private readonly fetcher: PaymentGatewayFetch = fetch,
  ) {}

  public async query(input: Parameters<PaymentStatusProvider["query"]>[0]) {
    const response = await this.fetcher(this.config.endpoint, {
      method: "POST",
      headers: {
        authorization: `Bearer ${this.config.apiKey}`,
        "content-type": "application/json",
        "idempotency-key": `query:${input.merchantOrderId}`,
      },
      body: JSON.stringify(input),
    });
    if (!response.ok) throw new Error("Payment status gateway rejected query");
    const value = await response.json();
    if (!isResult(value)) throw new Error("Invalid payment status response");
    return value;
  }
}

const terminalStatuses = new Set([
  "IN_PROGRESS",
  "PAID",
  "FAILED",
  "CANCELLED",
]);
const isResult = (
  value: unknown,
): value is Awaited<ReturnType<PaymentStatusProvider["query"]>> => {
  if (!value || typeof value !== "object") return false;
  const candidate = value as Record<string, unknown>;
  return (
    typeof candidate.providerEventId === "string" &&
    candidate.providerEventId.length > 0 &&
    typeof candidate.status === "string" &&
    terminalStatuses.has(candidate.status)
  );
};
