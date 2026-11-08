import { expect, it, vi } from "vitest";
import { BilingualJobs } from "./bilingual-jobs.js";

const command = {
  accountId: "A",
  commandId: "1",
  text: "我明天出发。",
  sourceType: "DIARY" as const,
  contentShape: "ARTICLE" as const,
};
const paragraphs = [
  {
    textZh: command.text,
    textEn: "I leave tomorrow.",
    sentences: [{ textEn: "I leave tomorrow.", textZh: command.text }],
  },
];
it("coalesces polls, isolates accounts, and rejects an in-flight ID reused for a different input", async () => {
  const generate = vi.fn(async () => ({ paragraphs }));
  const jobs = new BilingualJobs(generate);
  try {
    const first = jobs.start(command);
    expect(jobs.start(command)).toBe(first);
    expect(jobs.poll("A", "1")).toBe(first);
    expect(() => jobs.poll("B", "1")).toThrow();
    expect(() => jobs.start({ ...command, text: "不同。" })).toThrow();
    await first.promise;
    expect(jobs.poll("A", "1").done).toBe(true);
    expect(jobs.start(command)).toBe(first);
    expect(generate).toHaveBeenCalledTimes(1);
  } finally {
    jobs.close();
  }
});
it("cancels future stages, then permits the same account to resume its original request", async () => {
  const jobs = new BilingualJobs(async (_cmd, check) => {
    check?.();
    return { paragraphs };
  });
  try {
    const original = jobs.start(command);
    jobs.cancel("A", "1");
    expect(() => jobs.poll("A", "1")).toThrow();
    const resumed = jobs.start(command);
    await expect(original.promise).rejects.toThrow();
    await expect(resumed.promise).resolves.toEqual({ paragraphs });
  } finally {
    jobs.close();
  }
});
it("drops idle completed work after the TTL; a new request can rebuild it", async () => {
  vi.useFakeTimers();
  const jobs = new BilingualJobs(async () => ({ paragraphs }));
  try {
    await jobs.start(command).promise;
    vi.advanceTimersByTime(21 * 60_000);
    expect(() => jobs.poll("A", "1")).toThrow();
    await expect(jobs.start(command).promise).resolves.toEqual({ paragraphs });
  } finally {
    jobs.close();
    vi.useRealTimers();
  }
});
