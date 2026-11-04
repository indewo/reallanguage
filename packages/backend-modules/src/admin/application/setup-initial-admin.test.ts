import { describe, expect, it, vi } from "vitest";

import { SetupInitialAdmin } from "./setup-initial-admin.js";

describe("SetupInitialAdmin", () => {
  it("creates only the configured administrator with a hashed password and MFA secret", async () => {
    const provision = vi.fn().mockResolvedValue("CREATED");
    const application = new SetupInitialAdmin(
      {
        configurationStatus: vi.fn().mockResolvedValue("UNCONFIGURED"),
        loadPendingTotpSecret: vi.fn(),
        provision,
      },
      {
        canonicalEmail: "indewo@outlook.com",
        passwords: {
          assess: vi.fn().mockResolvedValue(true),
          hash: vi.fn().mockResolvedValue("argon2id-hash"),
        },
        totp: {
          createSetup: vi.fn().mockReturnValue({
            secretBase32: "BASE32SECRET",
            provisioningUri: "otpauth://totp/RealLanguage%20Admin:test",
          }),
          describeSetup: vi.fn(),
        },
        clock: { now: () => new Date("2026-09-04T12:00:00.000Z") },
      },
    );

    await expect(
      application.execute({ password: "a strong passphrase" }),
    ).resolves.toEqual({
      status: "CREATED",
      email: "indewo@outlook.com",
      totpSecret: "BASE32SECRET",
      provisioningUri: "otpauth://totp/RealLanguage%20Admin:test",
    });
    expect(provision).toHaveBeenCalledWith({
      canonicalEmail: "indewo@outlook.com",
      passwordHash: "argon2id-hash",
      totpSecret: "BASE32SECRET",
      now: new Date("2026-09-04T12:00:00.000Z"),
    });
  });

  it("does not reveal or replace an existing administrator", async () => {
    const provision = vi.fn();
    const application = new SetupInitialAdmin(
      {
        configurationStatus: vi.fn().mockResolvedValue("CONFIGURED"),
        loadPendingTotpSecret: vi.fn(),
        provision,
      },
      dependencies(),
    );

    await expect(
      application.execute({ password: "a strong passphrase" }),
    ).resolves.toEqual({
      status: "ALREADY_CONFIGURED",
    });
    expect(provision).not.toHaveBeenCalled();
  });

  it("does not return an unused MFA secret when another setup wins the race", async () => {
    const application = new SetupInitialAdmin(
      {
        configurationStatus: vi.fn().mockResolvedValue("UNCONFIGURED"),
        loadPendingTotpSecret: vi.fn(),
        provision: vi.fn().mockResolvedValue("ALREADY_CONFIGURED"),
      },
      dependencies(),
    );

    await expect(
      application.execute({ password: "a strong passphrase" }),
    ).resolves.toEqual({
      status: "ALREADY_CONFIGURED",
    });
  });

  it("rejects a password before generating or storing an MFA secret", async () => {
    const provision = vi.fn();
    const createSetup = vi.fn();
    const application = new SetupInitialAdmin(
      {
        configurationStatus: vi.fn().mockResolvedValue("UNCONFIGURED"),
        loadPendingTotpSecret: vi.fn(),
        provision,
      },
      {
        ...dependencies(),
        passwords: {
          assess: vi.fn().mockResolvedValue(false),
          hash: vi.fn(),
        },
        totp: { createSetup, describeSetup: vi.fn() },
      },
    );

    await expect(application.execute({ password: "weak" })).resolves.toEqual({
      status: "PASSWORD_REJECTED",
    });
    expect(createSetup).not.toHaveBeenCalled();
    expect(provision).not.toHaveBeenCalled();
  });

  it("recovers an unfinished enrollment after refresh without creating a new secret", async () => {
    const application = new SetupInitialAdmin(
      {
        configurationStatus: vi.fn().mockResolvedValue("ENROLLMENT_PENDING"),
        loadPendingTotpSecret: vi.fn().mockResolvedValue("BASE32SECRET"),
        provision: vi.fn(),
      },
      dependencies(),
    );

    await expect(application.status()).resolves.toEqual("ENROLLMENT_PENDING");
    await expect(application.resumeEnrollment()).resolves.toEqual({
      status: "CREATED",
      email: "indewo@outlook.com",
      totpSecret: "BASE32SECRET",
      provisioningUri: "otpauth://totp/RealLanguage%20Admin:test",
    });
  });
});

const dependencies = () => ({
  canonicalEmail: "indewo@outlook.com",
  passwords: {
    assess: vi.fn().mockResolvedValue(true),
    hash: vi.fn().mockResolvedValue("argon2id-hash"),
  },
  totp: {
    createSetup: vi.fn().mockReturnValue({
      secretBase32: "BASE32SECRET",
      provisioningUri: "otpauth://totp/RealLanguage%20Admin:test",
    }),
    describeSetup: vi.fn().mockReturnValue({
      secretBase32: "BASE32SECRET",
      provisioningUri: "otpauth://totp/RealLanguage%20Admin:test",
    }),
  },
  clock: { now: () => new Date("2026-09-04T12:00:00.000Z") },
});
