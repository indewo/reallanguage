import { describe, expect, it, vi } from "vitest";

import {
  ResendRegistrationVerification,
  type RegistrationVerificationEmailResender,
} from "./resend-registration-verification.js";

const now = new Date("2026-09-01T01:00:00.000Z");

describe("ResendRegistrationVerification", () => {
  it("passes the configured cooldown and request identity to the atomic port", async () => {
    const resend = vi.fn().mockResolvedValue({
      status: "RESENT",
      retryAfterSeconds: 90,
    });
    const useCase = new ResendRegistrationVerification(
      { now: () => now },
      { resend },
      90,
    );

    await expect(
      useCase.execute({
        registrationId: "registration-1",
        requestId: "request-1",
      }),
    ).resolves.toEqual({ status: "RESENT", retryAfterSeconds: 90 });
    expect(resend).toHaveBeenCalledWith({
      registrationId: "registration-1",
      requestId: "request-1",
      requestedAt: now,
      cooldownSeconds: 90,
    });
  });

  it.each([
    { status: "NOT_AVAILABLE" as const },
    { status: "COOLDOWN" as const, retryAfterSeconds: 12 },
  ])("preserves the $status result from the atomic port", async (result) => {
    const resender: RegistrationVerificationEmailResender = {
      resend: vi.fn().mockResolvedValue(result),
    };
    const useCase = new ResendRegistrationVerification(
      { now: () => now },
      resender,
      60,
    );

    await expect(
      useCase.execute({
        registrationId: "registration-1",
        requestId: "request-2",
      }),
    ).resolves.toEqual(result);
  });

  it("rejects a non-positive cooldown configuration", () => {
    expect(
      () =>
        new ResendRegistrationVerification(
          { now: () => now },
          { resend: vi.fn() },
          0,
        ),
    ).toThrow("cooldownSeconds");
  });
});
