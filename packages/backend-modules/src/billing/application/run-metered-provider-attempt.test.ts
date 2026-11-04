import { describe, expect, it, vi } from "vitest";
import type {
  AiDeliveryClaim,
  AiResultDeliveryStore,
} from "../../ai/public.js";

const deliveryStore = (): AiResultDeliveryStore => {
  let state: AiDeliveryClaim | null = null;
  return {
    claim: async () => {
      if (state) return state;
      state = { status: "PROCESSING" };
      return { status: "CLAIMED" };
    },
    complete: async (_identity, result) => {
      state = { status: "COMPLETED", result };
      return true;
    },
    fail: async () => {
      state = { status: "FAILED" };
    },
  };
};

import {
  AiBalanceInsufficientError,
  AiChargeReconciliationRequiredError,
  RunMeteredProviderAttempt,
} from "./run-metered-provider-attempt.js";

const command = {
  accountId: "account-1",
  operationId: "operation-1",
  operationLabelZh: "生成英语表达",
  commandId: "command-1",
  task: "ENGLISH_CANDIDATE_GENERATION_V1",
  callType: "INITIAL_CALL" as const,
  input: { text: "你好" },
};

describe("RunMeteredProviderAttempt", () => {
  it("does not retry historical settled attempts which have no deliverable result", async () => {
    const execute = vi.fn();
    const application = new RunMeteredProviderAttempt(
      {
        reserve: vi
          .fn()
          .mockResolvedValue({ status: "SETTLED", reservedFen: 80 }),
        settle: vi.fn(),
        release: vi.fn(),
        requireReconciliation: vi.fn(),
      },
      {
        quote: vi.fn().mockResolvedValue({
          providerAttemptId: "attempt-1",
          maximumChargeFen: 80,
        }),
        execute,
      },
      deliveryStore(),
    );
    await expect(application.execute(command)).rejects.toBeInstanceOf(
      AiChargeReconciliationRequiredError,
    );
    expect(execute).not.toHaveBeenCalled();
  });

  it("does not execute a duplicate while the first call is pending", async () => {
    let finish!: (result: unknown) => void;
    const execute = vi.fn().mockImplementation(
      () =>
        new Promise((resolve) => {
          finish = resolve;
        }),
    );
    const application = new RunMeteredProviderAttempt(
      {
        reserve: vi
          .fn()
          .mockResolvedValue({ status: "RESERVED", reservedFen: 80 }),
        settle: vi.fn().mockResolvedValue({ status: "SETTLED", chargedFen: 1 }),
        release: vi.fn(),
        requireReconciliation: vi.fn(),
      },
      {
        quote: vi.fn().mockResolvedValue({
          providerAttemptId: "attempt-1",
          maximumChargeFen: 80,
        }),
        execute,
      },
      deliveryStore(),
    );
    const first = application.execute(command);
    await vi.waitFor(() => expect(execute).toHaveBeenCalledTimes(1));
    await expect(application.execute(command)).rejects.toMatchObject({
      code: "AI_OPERATION_PROCESSING",
    });
    finish({
      output: "first",
      receipt: {
        providerAttemptId: "attempt-1",
        modelSource: "provider/model",
        providerCostMicroCny: 1,
      },
    });
    await expect(first).resolves.toBe("first");
    expect(execute).toHaveBeenCalledTimes(1);
  });

  it("recovers a saved result after settlement response loss without regenerating", async () => {
    const execute = vi.fn().mockResolvedValue({
      output: "saved",
      receipt: {
        providerAttemptId: "attempt-1",
        modelSource: "provider/model",
        providerCostMicroCny: 1,
      },
    });
    const charges = {
      reserve: vi
        .fn()
        .mockResolvedValue({ status: "RESERVED", reservedFen: 80 }),
      settle: vi
        .fn()
        .mockRejectedValueOnce(new Error("settlement response lost"))
        .mockResolvedValue({ status: "SETTLED", chargedFen: 1 }),
      release: vi.fn(),
      requireReconciliation: vi.fn(),
    };
    const gateway = {
      quote: vi.fn().mockResolvedValue({
        providerAttemptId: "attempt-1",
        maximumChargeFen: 80,
      }),
      execute,
    };
    const deliveries = deliveryStore();
    await expect(
      new RunMeteredProviderAttempt(charges, gateway, deliveries).execute(
        command,
      ),
    ).rejects.toThrow("settlement response lost");
    await expect(
      new RunMeteredProviderAttempt(charges, gateway, deliveries).execute(
        command,
      ),
    ).resolves.toBe("saved");
    expect(execute).toHaveBeenCalledTimes(1);
  });

  it("replays a settled result without sending another supplier request", async () => {
    const execute = vi.fn().mockResolvedValue({
      output: { text: "first result" },
      receipt: {
        providerAttemptId: "attempt-1",
        modelSource: "provider/model",
        providerCostMicroCny: 1,
      },
    });
    const reserve = vi
      .fn()
      .mockResolvedValueOnce({ status: "RESERVED", reservedFen: 80 })
      .mockResolvedValue({ status: "SETTLED", reservedFen: 80 });
    const application = new RunMeteredProviderAttempt(
      {
        reserve,
        settle: vi.fn().mockResolvedValue({ status: "SETTLED", chargedFen: 1 }),
        release: vi.fn(),
        requireReconciliation: vi.fn(),
      },
      {
        quote: vi.fn().mockResolvedValue({
          providerAttemptId: "attempt-1",
          maximumChargeFen: 80,
        }),
        execute,
      },
      deliveryStore(),
    );
    await expect(application.execute(command)).resolves.toEqual({
      text: "first result",
    });
    await expect(application.execute(command)).resolves.toEqual({
      text: "first result",
    });
    expect(execute).toHaveBeenCalledTimes(1);
  });

  it("quotes, reserves, executes, and settles before exposing output", async () => {
    const quote = vi.fn().mockResolvedValue({
      providerAttemptId: "attempt-1",
      maximumChargeFen: 80,
    });
    const execute = vi.fn().mockResolvedValue({
      output: { candidates: [] },
      receipt: {
        providerAttemptId: "attempt-1",
        modelSource: "provider/model",
        providerCostMicroCny: 112_000,
      },
    });
    const reserve = vi.fn().mockResolvedValue({
      status: "RESERVED",
      reservedFen: 80,
    });
    const settle = vi.fn().mockResolvedValue({
      status: "SETTLED",
      chargedFen: 23,
    });
    const application = new RunMeteredProviderAttempt(
      { reserve, settle, release: vi.fn(), requireReconciliation: vi.fn() },
      { quote, execute },
      deliveryStore(),
    );

    await expect(application.execute(command)).resolves.toEqual({
      candidates: [],
    });
    expect(reserve).toHaveBeenCalledBefore(execute);
    expect(settle).toHaveBeenCalledAfter(execute);
  });

  it("does not invoke the supplier when available balance cannot cover the quote", async () => {
    const execute = vi.fn();
    const application = new RunMeteredProviderAttempt(
      {
        reserve: vi.fn().mockResolvedValue(null),
        settle: vi.fn(),
        release: vi.fn(),
        requireReconciliation: vi.fn(),
      },
      {
        quote: vi.fn().mockResolvedValue({
          providerAttemptId: "attempt-1",
          maximumChargeFen: 80,
        }),
        execute,
      },
      deliveryStore(),
    );
    await expect(application.execute(command)).rejects.toBeInstanceOf(
      AiBalanceInsufficientError,
    );
    expect(execute).not.toHaveBeenCalled();
  });

  it("withholds output and requires reconciliation for mismatched receipts", async () => {
    const requireReconciliation = vi.fn().mockResolvedValue(true);
    const application = new RunMeteredProviderAttempt(
      {
        reserve: vi.fn().mockResolvedValue({
          status: "RESERVED",
          reservedFen: 80,
        }),
        settle: vi.fn(),
        release: vi.fn(),
        requireReconciliation,
      },
      {
        quote: vi.fn().mockResolvedValue({
          providerAttemptId: "attempt-1",
          maximumChargeFen: 80,
        }),
        execute: vi.fn().mockResolvedValue({
          output: { candidates: [] },
          receipt: {
            providerAttemptId: "different-attempt",
            modelSource: "provider/model",
            providerCostMicroCny: 112_000,
          },
        }),
      },
      deliveryStore(),
    );
    await expect(application.execute(command)).rejects.toBeInstanceOf(
      AiChargeReconciliationRequiredError,
    );
    expect(requireReconciliation).toHaveBeenCalledWith("attempt-1");
  });

  it("keeps the reservation for reconciliation when execution outcome is unknown", async () => {
    const requireReconciliation = vi.fn().mockResolvedValue(true);
    const application = new RunMeteredProviderAttempt(
      {
        reserve: vi.fn().mockResolvedValue({
          status: "RESERVED",
          reservedFen: 80,
        }),
        settle: vi.fn(),
        release: vi.fn(),
        requireReconciliation,
      },
      {
        quote: vi.fn().mockResolvedValue({
          providerAttemptId: "attempt-1",
          maximumChargeFen: 80,
        }),
        execute: vi.fn().mockRejectedValue(new Error("connection reset")),
      },
      deliveryStore(),
    );
    await expect(application.execute(command)).rejects.toThrow(
      "connection reset",
    );
    expect(requireReconciliation).toHaveBeenCalledWith("attempt-1");
  });
});
