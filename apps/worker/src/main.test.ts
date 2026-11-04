import { describe, expect, it, vi } from "vitest";
import {
  parseRefundWorkerConfig,
  runBillingWorkerOnce,
  runRefundWorkerOnce,
  workerHealth,
} from "./main.js";
it("boots the worker composition root", () =>
  expect(workerHealth()).toEqual({ status: "ok", service: "worker" }));

describe("refund worker", () => {
  it("runs a bounded reconciliation batch", async () => {
    const execute = vi.fn().mockResolvedValue({
      inspected: 2,
      completed: 2,
      failed: 0,
      failedRequestIds: [],
    });
    await expect(runRefundWorkerOnce({ execute }, 20)).resolves.toMatchObject({
      completed: 2,
      failed: 0,
    });
    expect(execute).toHaveBeenCalledWith({ limit: 20 });
  });

  it("reconciles refunds and delayed payment callbacks in one bounded run", async () => {
    const refunds = {
      execute: vi.fn().mockResolvedValue({
        inspected: 1,
        completed: 1,
        failed: 0,
        failedRequestIds: [],
      }),
    };
    const payments = {
      execute: vi.fn().mockResolvedValue({
        inspected: 1,
        settled: 1,
        pending: 0,
        failed: 0,
        failedOrderIds: [],
      }),
    };
    await expect(runBillingWorkerOnce(refunds, payments, 20)).resolves.toEqual({
      refunds: expect.objectContaining({ completed: 1 }),
      payments: expect.objectContaining({ settled: 1 }),
    });
    expect(refunds.execute).toHaveBeenCalledWith({ limit: 20 });
    expect(payments.execute).toHaveBeenCalledWith({ limit: 20 });
  });

  it("accepts only explicit HTTPS production configuration", () => {
    expect(
      parseRefundWorkerConfig({
        DATABASE_URL: "postgres://db/reallanguage",
        DATABASE_SSL: "false",
        PAYMENT_REFUND_PROVIDER_URL: "https://payments.example/refunds",
        PAYMENT_STATUS_PROVIDER_URL: "https://payments.example/status",
        PAYMENT_PROVIDER_API_KEY: "secret",
        REFUND_BATCH_SIZE: "50",
      }),
    ).toMatchObject({
      databaseSsl: false,
      batchSize: 50,
      refundProviderUrl: "https://payments.example/refunds",
      paymentStatusProviderUrl: "https://payments.example/status",
    });
    expect(() =>
      parseRefundWorkerConfig({
        DATABASE_URL: "postgres://db/reallanguage",
        PAYMENT_REFUND_PROVIDER_URL: "http://payments.example/refunds",
        PAYMENT_STATUS_PROVIDER_URL: "https://payments.example/status",
        PAYMENT_PROVIDER_API_KEY: "secret",
      }),
    ).toThrow("must use HTTPS");
  });
});
