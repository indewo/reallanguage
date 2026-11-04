import { afterEach, expect, it, vi } from "vitest";
import { AiResultCleanup } from "./ai-result-cleanup.js";
afterEach(() => vi.useRealTimers());

it("cleans on startup and while idle, stops and awaits work before database shutdown", async () => {
  vi.useFakeTimers();
  const purge = vi.fn().mockResolvedValue(0);
  const cleanup = new AiResultCleanup(purge, vi.fn());
  await cleanup.onModuleInit();
  expect(purge).toHaveBeenCalledTimes(1);
  await vi.advanceTimersByTimeAsync(60_000);
  expect(purge).toHaveBeenCalledTimes(2);
  await cleanup.onModuleDestroy();
  await vi.advanceTimersByTimeAsync(120_000);
  expect(purge).toHaveBeenCalledTimes(2);
});

it("reports cleanup failure without overlapping runs and retries on the next interval", async () => {
  vi.useFakeTimers();
  const failure = vi.fn();
  const purge = vi
    .fn()
    .mockResolvedValueOnce(0)
    .mockRejectedValueOnce(new Error("private internal value"))
    .mockResolvedValue(1);
  const cleanup = new AiResultCleanup(purge, failure);
  await cleanup.onModuleInit();
  await vi.advanceTimersByTimeAsync(120_000);
  expect(failure).toHaveBeenCalledExactlyOnceWith();
  expect(purge).toHaveBeenCalledTimes(3);
  await cleanup.onModuleDestroy();
});
