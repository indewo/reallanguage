import { describe, expect, it, vi } from "vitest";

import { PasswordPolicy } from "./password-policy.js";

describe("PasswordPolicy", () => {
  it("accepts 4 through 64 Unicode code points without composition rules", async () => {
    const policy = new PasswordPolicy({
      isBlocked: vi.fn().mockResolvedValue(false),
    });

    await expect(policy.assess("测试ab")).resolves.toEqual({
      accepted: true,
      normalizedPassword: "测试ab",
    });
    await expect(policy.assess("🔐".repeat(64))).resolves.toMatchObject({
      accepted: true,
    });
  });

  it("rejects passwords outside the code-point length range", async () => {
    const policy = new PasswordPolicy({
      isBlocked: vi.fn().mockResolvedValue(false),
    });

    await expect(policy.assess("🔐".repeat(3))).resolves.toEqual({
      accepted: false,
      reason: "TOO_SHORT",
    });
    await expect(policy.assess("🔐".repeat(65))).resolves.toEqual({
      accepted: false,
      reason: "TOO_LONG",
    });
  });

  it("normalizes with NFC before checking the private blocklist", async () => {
    const isBlocked = vi.fn().mockResolvedValue(true);
    const policy = new PasswordPolicy({ isBlocked });
    const decomposed = `${"e\u0301".repeat(8)} password`;

    await expect(policy.assess(decomposed)).resolves.toEqual({
      accepted: false,
      reason: "BLOCKED",
    });
    expect(isBlocked).toHaveBeenCalledWith(decomposed.normalize("NFC"));
  });

  it("does not send invalid-length passwords to the blocklist", async () => {
    const isBlocked = vi.fn().mockResolvedValue(false);
    const policy = new PasswordPolicy({ isBlocked });

    await policy.assess("abc");

    expect(isBlocked).not.toHaveBeenCalled();
  });
});
