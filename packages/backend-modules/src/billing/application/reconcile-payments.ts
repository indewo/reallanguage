import type { PaymentEvent } from "./apply-payment-event.js";
import type { PaymentMethod } from "./manage-recharge.js";

export type PendingPayment = Readonly<{
  merchantOrderId: string;
  providerOrderId: string;
  paymentMethod: PaymentMethod;
  amountFen: number;
}>;

export interface PendingPaymentStore {
  listPendingPayments(limit: number): Promise<readonly PendingPayment[]>;
  apply(event: PaymentEvent): Promise<boolean>;
}

export interface PaymentStatusProvider {
  query(input: PendingPayment): Promise<{
    providerEventId: string;
    status: PaymentEvent["status"];
  }>;
}

export type PaymentReconciliationSummary = Readonly<{
  inspected: number;
  settled: number;
  pending: number;
  failed: number;
  failedOrderIds: readonly string[];
}>;

export class ReconcilePendingPayments {
  public constructor(
    private readonly payments: PendingPaymentStore,
    private readonly provider: PaymentStatusProvider,
  ) {}

  public async execute(command: {
    limit: number;
  }): Promise<PaymentReconciliationSummary> {
    if (!Number.isSafeInteger(command.limit) || command.limit < 1)
      throw new Error(
        "Payment reconciliation limit must be a positive integer",
      );
    const orders = await this.payments.listPendingPayments(command.limit);
    let settled = 0;
    let pending = 0;
    const failedOrderIds: string[] = [];
    for (const order of orders) {
      try {
        const result = await this.provider.query(order);
        if (result.status === "IN_PROGRESS") {
          pending += 1;
          continue;
        }
        const applied = await this.payments.apply({
          providerEventId: result.providerEventId,
          providerOrderId: order.providerOrderId,
          merchantOrderId: order.merchantOrderId,
          paymentMethod: order.paymentMethod,
          amountFen: order.amountFen,
          currency: "CNY",
          status: result.status,
        });
        if (applied) settled += 1;
        else failedOrderIds.push(order.merchantOrderId);
      } catch {
        failedOrderIds.push(order.merchantOrderId);
      }
    }
    return {
      inspected: orders.length,
      settled,
      pending,
      failed: failedOrderIds.length,
      failedOrderIds,
    };
  }
}
