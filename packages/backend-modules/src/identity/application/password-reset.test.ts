import { describe, expect, it, vi } from "vitest";

import {
  CompletePasswordReset,
  RequestPasswordReset,
  VerifyPasswordReset,
} from "./password-reset.js";

describe("password reset applications", () => {
  it("returns the same neutral sent shape after dispatching a stable command", async () => {
    const request = vi.fn().mockResolvedValue({
      challengeId: "10000000-0000-4000-8000-000000000001",
    });
    const application = new RequestPasswordReset({ request });

    await expect(
      application.execute({
        email: "learner@example.com",
        commandId: "request-command",
      }),
    ).resolves.toEqual({
      status: "SENT",
      challengeId: "10000000-0000-4000-8000-000000000001",
    });
    expect(request).toHaveBeenCalledWith({
      email: "learner@example.com",
      commandId: "request-command",
    });
  });

  it("returns a reset token only for a verified active link", async () => {
    const verify = vi
      .fn()
      .mockResolvedValueOnce({ resetToken: "secure-reset-token" })
      .mockResolvedValueOnce(null);
    const application = new VerifyPasswordReset({ verify });

    await expect(
      application.execute({ challengeId: "challenge", bearer: "bearer" }),
    ).resolves.toEqual({
      status: "VERIFIED",
      resetToken: "secure-reset-token",
    });
    await expect(
      application.execute({ challengeId: "challenge", bearer: "expired" }),
    ).resolves.toEqual({ status: "INVALID_OR_EXPIRED" });
  });

  it("rejects the new password before any reset storage write", async () => {
    const complete = vi.fn();
    const application = new CompletePasswordReset(
      {
        assess: vi
          .fn()
          .mockResolvedValue({ accepted: false, reason: "BLOCKED" }),
      },
      { complete },
    );

    await expect(
      application.execute({
        resetToken: "token",
        newPassword: "blocked-password",
        commandId: "reset-command",
      }),
    ).resolves.toEqual({ status: "PASSWORD_REJECTED", reason: "BLOCKED" });
    expect(complete).not.toHaveBeenCalled();
  });

  it("normalizes through policy and reports the persisted settings state", async () => {
    const complete = vi
      .fn()
      .mockResolvedValue({ initialSettingsComplete: true });
    const application = new CompletePasswordReset(
      {
        assess: vi.fn().mockResolvedValue({
          accepted: true,
          normalizedPassword: "normalized-password",
        }),
      },
      { complete },
    );

    await expect(
      application.execute({
        resetToken: "token",
        newPassword: "raw-password",
        commandId: "reset-command",
      }),
    ).resolves.toEqual({
      status: "COMPLETED",
      sessionEstablished: false,
      initialSettingsComplete: true,
    });
    expect(complete).toHaveBeenCalledWith({
      resetToken: "token",
      normalizedPassword: "normalized-password",
      commandId: "reset-command",
    });
  });

  it("keeps an expired or consumed reset token neutral", async () => {
    const application = new CompletePasswordReset(
      {
        assess: vi.fn().mockResolvedValue({
          accepted: true,
          normalizedPassword: "normalized-password",
        }),
      },
      { complete: vi.fn().mockResolvedValue(null) },
    );

    await expect(
      application.execute({
        resetToken: "expired",
        newPassword: "raw-password",
        commandId: "reset-command",
      }),
    ).resolves.toEqual({ status: "INVALID_OR_EXPIRED" });
  });
});
