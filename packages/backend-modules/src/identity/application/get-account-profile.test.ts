import { describe, expect, it } from "vitest";
import { GetAccountProfile } from "./get-account-profile.js";

describe("GetAccountProfile", () => {
  it("reads only the authenticated account identity", async () => {
    const reader = {
      read: async (accountId: string) =>
        accountId === "account-1" ? { email: "learner@example.com" } : null,
    };
    await expect(
      new GetAccountProfile(reader).execute({ accountId: "account-1" }),
    ).resolves.toEqual({ email: "learner@example.com" });
  });
});
