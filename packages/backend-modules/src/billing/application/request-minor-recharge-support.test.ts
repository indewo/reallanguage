import { expect, it, vi } from "vitest";
import { RequestMinorRechargeSupport } from "./request-minor-recharge-support.js";

it("submits a stable account-owned minor recharge support case", async () => {
  const requestMinorRechargeSupport = vi.fn().mockResolvedValue({
    supportCaseId: "case-1",
    status: "REQUESTED",
  });
  const application = new RequestMinorRechargeSupport({
    requestMinorRechargeSupport,
  });
  await expect(
    application.execute({ accountId: "account-1", commandId: "command-1" }),
  ).resolves.toEqual({ supportCaseId: "case-1", status: "REQUESTED" });
  expect(requestMinorRechargeSupport).toHaveBeenCalledWith({
    accountId: "account-1",
    commandId: "command-1",
  });
});
