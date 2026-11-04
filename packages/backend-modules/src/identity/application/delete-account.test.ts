import { describe, expect, it, vi } from "vitest";
import { DeleteAccount } from "./delete-account.js";
describe("DeleteAccount", () => {
  it("requires one explicit stable command and delegates the authenticated account", async () => {
    const store = { delete: vi.fn().mockResolvedValue(true) };
    await expect(
      new DeleteAccount(store).execute({
        accountId: "account",
        commandId: "delete-1",
      }),
    ).resolves.toEqual({ status: "DELETED" });
    expect(store.delete).toHaveBeenCalledWith("account", "delete-1");
  });
});
