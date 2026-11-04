import { describe, expect, it, vi } from "vitest";
import { MarkAllAppMessagesRead } from "./mark-all-app-messages-read.js";

describe("MarkAllAppMessagesRead", () => {
  it("marks only the authenticated account messages at the current time", async () => {
    const markAllRead = vi.fn().mockResolvedValue(3);
    const now = new Date("2026-09-06T03:00:00.000Z");

    await expect(
      new MarkAllAppMessagesRead({ markAllRead }, { now: () => now }).execute({
        accountId: "account-1",
      }),
    ).resolves.toEqual({ updatedCount: 3 });
    expect(markAllRead).toHaveBeenCalledWith("account-1", now);
  });
});
