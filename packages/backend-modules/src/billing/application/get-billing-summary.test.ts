import { describe, expect, it, vi } from "vitest";
import { GetBillingSummary } from "./get-billing-summary.js";

describe("GetBillingSummary", () => {
  it("reads only the authenticated account billing subject", async () => {
    const read = vi
      .fn()
      .mockResolvedValue({ balanceAmountFen: 400, charges: [] });
    await expect(
      new GetBillingSummary({ read }).execute({ accountId: "account-1" }),
    ).resolves.toEqual({
      balanceAmountFen: 400,
      charges: [],
    });
    expect(read).toHaveBeenCalledWith("account-1");
  });
});
