import { describe, expect, it, vi } from "vitest";
import { GetRefundSnapshot, RequestRefund } from "./manage-refund.js";

describe("refund applications", () => {
  it("reads the server-calculated refundable balance", async () => {
    const read = vi.fn().mockResolvedValue({
      refundableAmountFen: 300,
      refundMethodLabelZh: "原路退回",
      requestId: null,
      requestStatus: null,
    });
    await expect(
      new GetRefundSnapshot({ read, request: vi.fn() }).execute({
        accountId: "account",
      }),
    ).resolves.toMatchObject({ refundableAmountFen: 300 });
  });
  it("requests exactly the full currently refundable amount and keeps idempotency in the store", async () => {
    const read = vi.fn().mockResolvedValue({
      refundableAmountFen: 300,
      refundMethodLabelZh: "原路退回",
      requestId: null,
      requestStatus: null,
    });
    const request = vi
      .fn()
      .mockResolvedValue({ requestId: "refund-1", status: "REQUESTED" });
    const application = new RequestRefund({ read, request });
    await expect(
      application.execute({
        accountId: "account",
        amountFen: 300,
        commandId: "command",
      }),
    ).resolves.toEqual({ requestId: "refund-1", status: "REQUESTED" });
    await expect(
      application.execute({
        accountId: "account",
        amountFen: 299,
        commandId: "other",
      }),
    ).resolves.toBeNull();
    expect(request).toHaveBeenCalledTimes(1);
  });
});
