import { describe, expect, it } from "vitest";
import { ListAppMessages, type AppMessageReader } from "./list-app-messages.js";

describe("ListAppMessages", () => {
  it("returns only the account messages newest first without interpreting targets", async () => {
    const reader: AppMessageReader = {
      list: async (accountId) => {
        expect(accountId).toBe("account-1");
        return [older, newer];
      },
    };
    await expect(
      new ListAppMessages(reader).execute({ accountId: "account-1" }),
    ).resolves.toEqual([newer, older]);
  });
});

const older = {
  messageId: "message-1",
  kind: "SYSTEM" as const,
  summaryZh: "系统消息",
  relatedContentLabelZh: null,
  createdAt: new Date("2026-09-01T00:00:00Z"),
  read: false,
  target: null,
};
const newer = {
  messageId: "message-2",
  kind: "MANUAL_REVIEW_RESULT" as const,
  summaryZh: "复核完成",
  relatedContentLabelZh: "昨晚的日记",
  createdAt: new Date("2026-09-02T00:00:00Z"),
  read: true,
  target: { route: "MANUAL_REVIEW_RESULT" as const, resourceId: "review-1" },
};
