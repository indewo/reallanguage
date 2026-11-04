import { describe, expect, it, vi } from "vitest";

import { CheckContentSafety } from "./check-content-safety.js";

const command = {
  accountId: "30000000-0000-4000-8000-000000000001",
  text: "今天发生了一件值得记录的事。",
  sourceType: "DIARY" as const,
  contentShape: "ARTICLE" as const,
  commandId: "safety-command-1",
};

describe("CheckContentSafety", () => {
  it("allows the confirmed source only after the gateway passes it", async () => {
    const check = vi.fn().mockResolvedValue({ decision: "PASS" });
    const application = new CheckContentSafety({ check });

    await expect(application.execute(command)).resolves.toEqual({
      status: "PASSED",
    });
    expect(check).toHaveBeenCalledWith(command);
  });

  it("returns only the persisted safety case identity when blocked", async () => {
    const application = new CheckContentSafety({
      check: vi.fn().mockResolvedValue({
        decision: "BLOCK",
        safetyCaseId: "10000000-0000-4000-8000-000000000001",
      }),
    });

    await expect(application.execute(command)).resolves.toEqual({
      status: "BLOCKED",
      safetyCaseId: "10000000-0000-4000-8000-000000000001",
    });
  });
});
