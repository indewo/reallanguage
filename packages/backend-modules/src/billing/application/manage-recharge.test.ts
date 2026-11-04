import { describe, expect, it, vi } from "vitest";
import { CreateRechargeOrder } from "./manage-recharge.js";

describe("CreateRechargeOrder", () => {
  it("creates one CNY provider order and returns the launch payload", async () => {
    const prepare = vi.fn().mockResolvedValue({
      orderId: "order-1",
      amountFen: 5_000,
      paymentMethod: "WECHAT_PAY",
      status: "CREATED",
      paymentLaunch: null,
      existing: false,
    });
    const markPending = vi.fn().mockResolvedValue({
      orderId: "order-1",
      status: "PENDING_PAYMENT",
      paymentLaunch: "launch",
    });
    const create = vi.fn().mockResolvedValue({
      providerOrderId: "provider-1",
      paymentLaunch: "launch",
    });
    const result = await new CreateRechargeOrder(
      { read: vi.fn(), prepare, markPending, markFailed: vi.fn() },
      { create },
    ).execute({
      accountId: "account",
      commandId: "command",
      amountFen: 5_000,
      paymentMethod: "WECHAT_PAY",
    });
    expect(result).toEqual({
      orderId: "order-1",
      status: "PENDING_PAYMENT",
      paymentLaunch: "launch",
    });
    expect(create).toHaveBeenCalledWith({
      merchantOrderId: "order-1",
      amountFen: 5_000,
      currency: "CNY",
      paymentMethod: "WECHAT_PAY",
    });
  });
  it("replays the stored order without another provider call and rejects out-of-range amounts", async () => {
    const prepare = vi.fn().mockResolvedValue({
      orderId: "order-1",
      amountFen: 5_000,
      paymentMethod: "ALIPAY",
      status: "PENDING_PAYMENT",
      paymentLaunch: "launch",
      existing: true,
    });
    const create = vi.fn();
    const application = new CreateRechargeOrder(
      { read: vi.fn(), prepare, markPending: vi.fn(), markFailed: vi.fn() },
      { create },
    );
    await expect(
      application.execute({
        accountId: "account",
        commandId: "same",
        amountFen: 5_000,
        paymentMethod: "ALIPAY",
      }),
    ).resolves.toMatchObject({ orderId: "order-1" });
    await expect(
      application.execute({
        accountId: "account",
        commandId: "bad",
        amountFen: 0,
        paymentMethod: "ALIPAY",
      }),
    ).resolves.toBeNull();
    expect(create).not.toHaveBeenCalled();
  });
});
