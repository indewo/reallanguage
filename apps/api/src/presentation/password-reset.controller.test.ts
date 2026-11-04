import { BadRequestException } from "@nestjs/common";
import { describe, expect, it, vi } from "vitest";

import { PasswordResetController } from "./password-reset.controller.js";

const challengeId = "10000000-0000-4000-8000-000000000001";
const bearer = Buffer.alloc(32, 4).toString("base64url");

describe("PasswordResetController", () => {
  it("parses and dispatches each versioned password reset step", async () => {
    const request = vi.fn().mockResolvedValue({ status: "SENT", challengeId });
    const verify = vi
      .fn()
      .mockResolvedValue({ status: "VERIFIED", resetToken: bearer });
    const complete = vi.fn().mockResolvedValue({
      status: "COMPLETED",
      sessionEstablished: false,
      initialSettingsComplete: true,
    });
    const controller = new PasswordResetController(
      { execute: request },
      { execute: verify },
      { execute: complete },
    );

    await expect(
      controller.request(
        { email: "learner@example.com", commandId: "request-command" },
        "request-id",
      ),
    ).resolves.toEqual({ status: "SENT", challengeId });
    await expect(
      controller.verify(challengeId, { bearer }, "request-id"),
    ).resolves.toEqual({ status: "VERIFIED", resetToken: bearer });
    await expect(
      controller.complete(
        {
          resetToken: bearer,
          newPassword: "a sufficiently long password",
          commandId: "reset-command",
        },
        "request-id",
      ),
    ).resolves.toMatchObject({ status: "COMPLETED" });

    expect(verify).toHaveBeenCalledWith({ challengeId, bearer });
  });

  it("rejects malformed bodies and challenge identifiers before dispatch", async () => {
    const request = vi.fn();
    const verify = vi.fn();
    const complete = vi.fn();
    const controller = new PasswordResetController(
      { execute: request },
      { execute: verify },
      { execute: complete },
    );

    await expect(
      controller.request({ email: "invalid" }, "request-id"),
    ).rejects.toBeInstanceOf(BadRequestException);
    await expect(
      controller.verify("not-a-uuid", { bearer }, "request-id"),
    ).rejects.toBeInstanceOf(BadRequestException);
    await expect(
      controller.complete({ resetToken: "short" }, "request-id"),
    ).rejects.toBeInstanceOf(BadRequestException);
    expect(request).not.toHaveBeenCalled();
    expect(verify).not.toHaveBeenCalled();
    expect(complete).not.toHaveBeenCalled();
  });
});
