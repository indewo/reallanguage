import { afterEach, describe, expect, it, vi } from "vitest";
import { schedulerHealth, startRefundSchedule } from "./main.js";
it("boots the scheduler composition root", () =>
  expect(schedulerHealth()).toEqual({ status: "ok", service: "scheduler" }));

describe("refund reconciliation schedule", () => {
  afterEach(() => vi.useRealTimers());

  it("runs immediately and periodically without overlapping work", async () => {
    vi.useFakeTimers();
    let finish: (() => void) | undefined;
    const run = vi.fn(
      () =>
        new Promise<{
          inspected: number;
          completed: number;
          failed: number;
          failedRequestIds: string[];
        }>((resolve) => {
          finish = () =>
            resolve({
              inspected: 0,
              completed: 0,
              failed: 0,
              failedRequestIds: [],
            });
        }),
    );
    const schedule = startRefundSchedule({
      intervalMilliseconds: 1_000,
      run,
    });
    expect(run).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(3_000);
    expect(run).toHaveBeenCalledTimes(1);
    finish?.();
    await Promise.resolve();
    await vi.advanceTimersByTimeAsync(1_000);
    expect(run).toHaveBeenCalledTimes(2);
    finish?.();
    await schedule.stop();
  });

  it("requires a safe minimum interval", () => {
    expect(() =>
      startRefundSchedule({ intervalMilliseconds: 999, run: vi.fn() }),
    ).toThrow("at least one second");
  });
});
