import { describe, expect, it, vi } from "vitest";

import {
  ExecuteApprovedRefund,
  ReconcileApprovedRefunds,
  ReviewRefund,
  type RefundExecutionStore,
} from "./process-refund.js";

const approved = {
  requestId: "refund-1",
  amountFen: 3_000,
  allocations: [
    {
      allocationId: "allocation-1",
      merchantOrderId: "order-1",
      providerOrderId: "provider-order-1",
      paymentMethod: "WECHAT_PAY" as const,
      amountFen: 2_000,
      refunded: false,
    },
    {
      allocationId: "allocation-2",
      merchantOrderId: "order-2",
      providerOrderId: "provider-order-2",
      paymentMethod: "ALIPAY" as const,
      amountFen: 1_000,
      refunded: true,
    },
  ],
};

describe("refund processing", () => {
  it("records a manual review decision without invoking a payment provider", async () => {
    const review = vi.fn().mockResolvedValue({
      requestId: "refund-1",
      status: "APPROVED",
    });
    const application = new ReviewRefund({ review });

    await expect(
      application.execute({
        requestId: "refund-1",
        decision: "APPROVE",
        reviewerId: "reviewer-1",
      }),
    ).resolves.toEqual({ requestId: "refund-1", status: "APPROVED" });
    expect(review).toHaveBeenCalledWith({
      requestId: "refund-1",
      decision: "APPROVE",
      reviewerId: "reviewer-1",
    });
  });

  it("refunds only pending allocations and settles after every allocation succeeds", async () => {
    const store: RefundExecutionStore = {
      review: vi.fn(),
      prepare: vi.fn().mockResolvedValue(approved),
      markAllocationRefunded: vi.fn().mockResolvedValue(true),
      complete: vi.fn().mockResolvedValue(true),
    };
    const refund = vi.fn().mockResolvedValue({
      providerRefundId: "provider-refund-1",
    });
    const application = new ExecuteApprovedRefund(store, { refund });

    await expect(application.execute({ requestId: "refund-1" })).resolves.toBe(
      true,
    );
    expect(refund).toHaveBeenCalledTimes(1);
    expect(refund).toHaveBeenCalledWith({
      refundRequestId: "refund-1",
      allocationId: "allocation-1",
      merchantOrderId: "order-1",
      providerOrderId: "provider-order-1",
      paymentMethod: "WECHAT_PAY",
      amountFen: 2_000,
      currency: "CNY",
    });
    expect(store.markAllocationRefunded).toHaveBeenCalledWith({
      allocationId: "allocation-1",
      providerRefundId: "provider-refund-1",
    });
    expect(store.complete).toHaveBeenCalledWith("refund-1");
  });

  it("does not settle the wallet when a provider refund fails", async () => {
    const store: RefundExecutionStore = {
      review: vi.fn(),
      prepare: vi.fn().mockResolvedValue({
        ...approved,
        allocations: [approved.allocations[0]],
      }),
      markAllocationRefunded: vi.fn(),
      complete: vi.fn(),
    };
    const application = new ExecuteApprovedRefund(store, {
      refund: vi.fn().mockRejectedValue(new Error("provider unavailable")),
    });

    await expect(
      application.execute({ requestId: "refund-1" }),
    ).rejects.toThrow("provider unavailable");
    expect(store.complete).not.toHaveBeenCalled();
  });

  it("reconciles an approved batch while isolating failures for the next run", async () => {
    const listApproved = vi
      .fn()
      .mockResolvedValue(["refund-1", "refund-2", "refund-3"]);
    const execute = vi
      .fn()
      .mockResolvedValueOnce(true)
      .mockRejectedValueOnce(new Error("provider unavailable"))
      .mockResolvedValueOnce(false);
    const reconciliation = new ReconcileApprovedRefunds(
      { listApproved },
      { execute },
    );

    await expect(reconciliation.execute({ limit: 25 })).resolves.toEqual({
      inspected: 3,
      completed: 1,
      failed: 2,
      failedRequestIds: ["refund-2", "refund-3"],
    });
    expect(listApproved).toHaveBeenCalledWith(25);
    expect(execute).toHaveBeenCalledTimes(3);
  });

  it("rejects an unsafe reconciliation batch size", async () => {
    const reconciliation = new ReconcileApprovedRefunds(
      { listApproved: vi.fn() },
      { execute: vi.fn() },
    );
    await expect(reconciliation.execute({ limit: 0 })).rejects.toThrow(
      "positive integer",
    );
  });
});
