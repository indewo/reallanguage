import { describe, expect, it, vi } from "vitest";

import {
  GetRegistrationVerificationStatus,
  VerifyRegistration,
  type PendingRegistrationChallenge,
  type RegistrationChallengeReader,
} from "./verify-registration.js";

const now = new Date("2026-09-01T00:10:00.000Z");
const challenge: PendingRegistrationChallenge = {
  challengeId: "challenge-1",
  accountId: null,
  status: "ACTIVE",
  expiresAt: new Date("2026-09-01T00:30:00.000Z"),
  subjectIndex: Uint8Array.of(5),
  subjectIndexKeyVersion: 6,
  secretDigest: Uint8Array.of(1),
  secretDigestKeyVersion: 2,
  protectedPayload: {
    ciphertext: Uint8Array.of(3),
    algorithm: "AES-256-GCM/v1",
    keyVersion: 4,
  },
};

describe("VerifyRegistration", () => {
  it.each([
    ["missing", null],
    [
      "expired",
      { ...challenge, expiresAt: new Date("2026-09-01T00:10:00.000Z") },
    ],
    ["superseded", { ...challenge, status: "SUPERSEDED" as const }],
  ])(
    "does not verify or create an account for a %s challenge",
    async (_, found) => {
      const verify = vi.fn();
      const create = vi.fn();
      const useCase = new VerifyRegistration(
        { now: () => now },
        challengeReader(found),
        { verify },
        { create },
      );

      await expect(
        useCase.execute({ registrationId: "challenge-1", bearer: "bearer" }),
      ).resolves.toEqual({ status: "INVALID_OR_EXPIRED" });
      expect(verify).not.toHaveBeenCalled();
      expect(create).not.toHaveBeenCalled();
    },
  );

  it("returns the original success for a repeated valid submission", async () => {
    const verify = vi.fn().mockReturnValue(true);
    const create = vi.fn();
    const consumed = {
      ...challenge,
      status: "CONSUMED" as const,
      accountId: "account-1",
    };
    const useCase = new VerifyRegistration(
      { now: () => now },
      challengeReader(consumed),
      { verify },
      { create },
    );

    await expect(
      useCase.execute({ registrationId: "challenge-1", bearer: "bearer" }),
    ).resolves.toEqual({ status: "ACCOUNT_CREATED", accountId: "account-1" });
    expect(verify).toHaveBeenCalledOnce();
    expect(create).not.toHaveBeenCalled();
  });

  it("rejects an invalid bearer even after the challenge was consumed", async () => {
    const create = vi.fn();
    const useCase = new VerifyRegistration(
      { now: () => now },
      challengeReader({
        ...challenge,
        status: "CONSUMED",
        accountId: "account-1",
      }),
      { verify: vi.fn().mockReturnValue(false) },
      { create },
    );

    await expect(
      useCase.execute({ registrationId: "challenge-1", bearer: "wrong" }),
    ).resolves.toEqual({ status: "INVALID_OR_EXPIRED" });
    expect(create).not.toHaveBeenCalled();
  });

  it("does not create an account when the bearer is invalid", async () => {
    const create = vi.fn();
    const useCase = new VerifyRegistration(
      { now: () => now },
      challengeReader(challenge),
      { verify: vi.fn().mockReturnValue(false) },
      { create },
    );

    await expect(
      useCase.execute({ registrationId: "challenge-1", bearer: "wrong" }),
    ).resolves.toEqual({ status: "INVALID_OR_EXPIRED" });
    expect(create).not.toHaveBeenCalled();
  });

  it("creates the account from the protected payload after verification", async () => {
    const create = vi.fn().mockResolvedValue({ accountId: "account-1" });
    const useCase = new VerifyRegistration(
      { now: () => now },
      challengeReader(challenge),
      { verify: vi.fn().mockReturnValue(true) },
      { create },
    );

    await expect(
      useCase.execute({ registrationId: "challenge-1", bearer: "bearer" }),
    ).resolves.toEqual({ status: "ACCOUNT_CREATED", accountId: "account-1" });
    expect(create).toHaveBeenCalledWith({ challenge, verifiedAt: now });
  });

  it("returns the original success when a concurrent request consumes first", async () => {
    const consumed = {
      ...challenge,
      status: "CONSUMED" as const,
      accountId: "account-1",
    };
    const findById = vi
      .fn()
      .mockResolvedValueOnce(challenge)
      .mockResolvedValueOnce(consumed);
    const useCase = new VerifyRegistration(
      { now: () => now },
      { findById },
      { verify: vi.fn().mockReturnValue(true) },
      { create: vi.fn().mockResolvedValue(null) },
    );

    await expect(
      useCase.execute({ registrationId: "challenge-1", bearer: "bearer" }),
    ).resolves.toEqual({ status: "ACCOUNT_CREATED", accountId: "account-1" });
  });
});

describe("GetRegistrationVerificationStatus", () => {
  it.each([
    [challenge, "WAITING"],
    [
      { ...challenge, status: "CONSUMED" as const, accountId: "account-1" },
      "VERIFIED",
    ],
    [{ ...challenge, expiresAt: now }, "NOT_AVAILABLE"],
    [{ ...challenge, status: "SUPERSEDED" as const }, "NOT_AVAILABLE"],
    [null, "NOT_AVAILABLE"],
  ])("maps the stored challenge to %s", async (found, status) => {
    const useCase = new GetRegistrationVerificationStatus(
      { now: () => now },
      challengeReader(found),
    );

    await expect(
      useCase.execute({ registrationId: "challenge-1" }),
    ).resolves.toEqual({ status });
  });
});

const challengeReader = (
  found: PendingRegistrationChallenge | null,
): RegistrationChallengeReader => ({
  findById: vi.fn().mockResolvedValue(found),
});
