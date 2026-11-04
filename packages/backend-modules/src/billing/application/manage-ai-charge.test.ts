import { describe, expect, it, vi } from "vitest";

import {
  ReleaseUnsentAiAttempt,
  ReserveAiAttempt,
  SettleAiAttempt,
} from "./manage-ai-charge.js";

describe("AI charge applications", () => {
  it("reserves a supplier attempt before any external call", async () => {
    const reserve = vi.fn().mockResolvedValue({
      status: "RESERVED",
      reservedFen: 80,
    });
    const application = new ReserveAiAttempt({ reserve });
    await expect(
      application.execute({
        accountId: "account-1",
        providerAttemptId: "attempt-1",
        operationId: "operation-1",
        operationLabelZh: "生成英语表达",
        callType: "INITIAL_CALL",
        maximumChargeFen: 80,
      }),
    ).resolves.toMatchObject({ status: "RESERVED" });
    expect(reserve).toHaveBeenCalledOnce();
  });

  it("rejects invalid maximum charges without touching the wallet", async () => {
    const reserve = vi.fn();
    const application = new ReserveAiAttempt({ reserve });
    await expect(
      application.execute({
        accountId: "account-1",
        providerAttemptId: "attempt-1",
        operationId: "operation-1",
        operationLabelZh: "生成英语表达",
        callType: "INITIAL_CALL",
        maximumChargeFen: 0,
      }),
    ).resolves.toBeNull();
    expect(reserve).not.toHaveBeenCalled();
  });

  it("settles actual supplier cost and can release only an unsent attempt", async () => {
    const settle = vi.fn().mockResolvedValue({
      status: "SETTLED",
      chargedFen: 23,
    });
    const release = vi.fn().mockResolvedValue(true);
    await expect(
      new SettleAiAttempt({ settle }).execute({
        providerAttemptId: "attempt-1",
        modelSource: "provider/model",
        providerCostMicroCny: 112_000,
      }),
    ).resolves.toMatchObject({ status: "SETTLED", chargedFen: 23 });
    await expect(
      new ReleaseUnsentAiAttempt({ release }).execute({
        providerAttemptId: "attempt-2",
      }),
    ).resolves.toBe(true);
  });
});
