import type { PaymentMethod } from "./manage-recharge.js";
import type { RefundResult } from "./manage-refund.js";

export type RefundReviewDecision = "APPROVE" | "REJECT";
export type RefundAllocation = Readonly<{
  allocationId: string;
  merchantOrderId: string;
  providerOrderId: string;
  paymentMethod: PaymentMethod;
  amountFen: number;
  refunded: boolean;
}>;
export type ApprovedRefund = Readonly<{
  requestId: string;
  amountFen: number;
  allocations: readonly RefundAllocation[];
}>;
export interface RefundExecutionStore {
  review(input: {
    requestId: string;
    decision: RefundReviewDecision;
    reviewerId: string;
  }): Promise<RefundResult | null>;
  prepare(requestId: string): Promise<ApprovedRefund | null>;
  markAllocationRefunded(input: {
    allocationId: string;
    providerRefundId: string;
  }): Promise<boolean>;
  complete(requestId: string): Promise<boolean>;
}
export interface RefundProvider {
  refund(input: {
    refundRequestId: string;
    allocationId: string;
    merchantOrderId: string;
    providerOrderId: string;
    paymentMethod: PaymentMethod;
    amountFen: number;
    currency: "CNY";
  }): Promise<{ providerRefundId: string }>;
}

export interface ApprovedRefundQueue {
  listApproved(limit: number): Promise<readonly string[]>;
}

export type RefundReconciliationSummary = Readonly<{
  inspected: number;
  completed: number;
  failed: number;
  failedRequestIds: readonly string[];
}>;

export class ReviewRefund {
  public constructor(
    private readonly refunds: Pick<RefundExecutionStore, "review">,
  ) {}
  public execute(command: Parameters<RefundExecutionStore["review"]>[0]) {
    return this.refunds.review(command);
  }
}

export class ExecuteApprovedRefund {
  public constructor(
    private readonly refunds: Omit<RefundExecutionStore, "review">,
    private readonly provider: RefundProvider,
  ) {}
  public async execute(command: { requestId: string }): Promise<boolean> {
    const refund = await this.refunds.prepare(command.requestId);
    if (!refund) return false;
    for (const allocation of refund.allocations) {
      if (allocation.refunded) continue;
      const result = await this.provider.refund({
        refundRequestId: refund.requestId,
        allocationId: allocation.allocationId,
        merchantOrderId: allocation.merchantOrderId,
        providerOrderId: allocation.providerOrderId,
        paymentMethod: allocation.paymentMethod,
        amountFen: allocation.amountFen,
        currency: "CNY",
      });
      if (
        !(await this.refunds.markAllocationRefunded({
          allocationId: allocation.allocationId,
          providerRefundId: result.providerRefundId,
        }))
      )
        return false;
    }
    return this.refunds.complete(refund.requestId);
  }
}

export class ReconcileApprovedRefunds {
  public constructor(
    private readonly queue: ApprovedRefundQueue,
    private readonly executor: Pick<ExecuteApprovedRefund, "execute">,
  ) {}

  public async execute(command: {
    limit: number;
  }): Promise<RefundReconciliationSummary> {
    if (!Number.isSafeInteger(command.limit) || command.limit < 1) {
      throw new Error("Refund reconciliation limit must be a positive integer");
    }
    const requestIds = await this.queue.listApproved(command.limit);
    let completed = 0;
    const failedRequestIds: string[] = [];
    for (const requestId of requestIds) {
      try {
        if (await this.executor.execute({ requestId })) completed += 1;
        else failedRequestIds.push(requestId);
      } catch {
        failedRequestIds.push(requestId);
      }
    }
    return {
      inspected: requestIds.length,
      completed,
      failed: failedRequestIds.length,
      failedRequestIds,
    };
  }
}
