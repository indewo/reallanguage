import type { PaymentMethod, RechargeOrderStatus } from "./manage-recharge.js";

export type PaymentEvent = Readonly<{
  providerEventId: string;
  providerOrderId: string;
  merchantOrderId: string;
  paymentMethod: PaymentMethod;
  amountFen: number;
  currency: "CNY";
  status: Extract<
    RechargeOrderStatus,
    "IN_PROGRESS" | "PAID" | "FAILED" | "CANCELLED"
  >;
}>;
export type PaymentEventEnvelope = Readonly<{
  payloadBase64: string;
  signature: string;
  timestamp: string;
}>;
export interface PaymentWebhookVerifier {
  verify(envelope: PaymentEventEnvelope): PaymentEvent | null;
}
export interface PaymentSettlementStore {
  apply(event: PaymentEvent): Promise<boolean>;
}
export class ApplyPaymentEvent {
  public constructor(
    private readonly verifier: PaymentWebhookVerifier,
    private readonly settlements: PaymentSettlementStore,
  ) {}
  public async execute(envelope: PaymentEventEnvelope) {
    const event = this.verifier.verify(envelope);
    if (!event) return false;
    return this.settlements.apply(event);
  }
}
