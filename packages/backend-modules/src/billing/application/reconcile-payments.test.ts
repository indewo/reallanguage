import { describe, expect, it, vi } from "vitest";
import { ReconcilePendingPayments } from "./reconcile-payments.js";

const orders = [
  {
    merchantOrderId: "order-1",
    providerOrderId: "provider-1",
    paymentMethod: "WECHAT_PAY" as const,
    amountFen: 5_000,
  },
  {
    merchantOrderId: "order-2",
    providerOrderId: "provider-2",
    paymentMethod: "ALIPAY" as const,
    amountFen: 20_000,
  },
  {
    merchantOrderId: "order-3",
    providerOrderId: "provider-3",
    paymentMethod: "ALIPAY" as const,
    amountFen: 50_000,
  },
];

describe("ReconcilePendingPayments", () => {
  it("settles authoritative terminal states and leaves in-progress orders pending", async () => {
    const apply = vi.fn().mockResolvedValue(true);
    const query = vi
      .fn()
      .mockResolvedValueOnce({ providerEventId: "query-1", status: "PAID" })
      .mockResolvedValueOnce({
        providerEventId: "query-2",
        status: "IN_PROGRESS",
      })
      .mockRejectedValueOnce(new Error("gateway unavailable"));
    const reconciliation = new ReconcilePendingPayments(
      { listPendingPayments: vi.fn().mockResolvedValue(orders), apply },
      { query },
    );

    await expect(reconciliation.execute({ limit: 25 })).resolves.toEqual({
      inspected: 3,
      settled: 1,
      pending: 1,
      failed: 1,
      failedOrderIds: ["order-3"],
    });
    expect(apply).toHaveBeenCalledWith({
      providerEventId: "query-1",
      providerOrderId: "provider-1",
      merchantOrderId: "order-1",
      paymentMethod: "WECHAT_PAY",
      amountFen: 5_000,
      currency: "CNY",
      status: "PAID",
    });
  });

  it("rejects an unsafe batch size", async () => {
    const reconciliation = new ReconcilePendingPayments(
      { listPendingPayments: vi.fn(), apply: vi.fn() },
      { query: vi.fn() },
    );
    await expect(reconciliation.execute({ limit: 0 })).rejects.toThrow(
      "positive integer",
    );
  });
});
