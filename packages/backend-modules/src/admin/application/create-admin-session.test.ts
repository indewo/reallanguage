import { describe, expect, it, vi } from "vitest";

import { CreateAdminSession } from "./create-admin-session.js";

const account = {
  adminId: "admin-1",
  canonicalEmail: "indewo@outlook.com",
  passwordHash: "encoded-hash",
  totpSecret: "BASE32SECRET",
  lastAcceptedTotpCounter: null,
  enabled: true,
};

const command = {
  email: " indewo@outlook.com ",
  password: "correct-password",
  totpCode: "123456",
};

describe("CreateAdminSession", () => {
  it("requires both a correct password and a fresh TOTP code", async () => {
    const store = {
      loadByCanonicalEmail: vi.fn().mockResolvedValue(account),
      issueSession: vi.fn().mockResolvedValue(true),
    };
    const application = new CreateAdminSession(store, {
      passwords: { verify: vi.fn().mockResolvedValue(true) },
      dummyPasswordHash: "dummy-hash",
      totp: { verify: vi.fn().mockReturnValue(42) },
      sessions: {
        create: vi.fn().mockReturnValue({
          bearer: "admin-bearer",
          digest: Uint8Array.of(1, 2, 3),
          keyVersion: 1,
        }),
      },
      clock: { now: () => new Date("2026-09-04T12:00:00.000Z") },
      sessionTtlMilliseconds: 15 * 60 * 1_000,
    });

    await expect(application.execute(command)).resolves.toEqual({
      status: "AUTHENTICATED",
      accessToken: "admin-bearer",
      expiresAt: new Date("2026-09-04T12:15:00.000Z"),
    });
    expect(store.issueSession).toHaveBeenCalledWith({
      adminId: "admin-1",
      acceptedTotpCounter: 42,
      previousTotpCounter: null,
      sessionDigest: Uint8Array.of(1, 2, 3),
      sessionDigestKeyVersion: 1,
      expiresAt: new Date("2026-09-04T12:15:00.000Z"),
      now: new Date("2026-09-04T12:00:00.000Z"),
    });
  });

  it("does not evaluate MFA or issue a session after a wrong password", async () => {
    const totp = { verify: vi.fn() };
    const store = {
      loadByCanonicalEmail: vi.fn().mockResolvedValue(account),
      issueSession: vi.fn(),
    };
    const application = new CreateAdminSession(store, {
      passwords: { verify: vi.fn().mockResolvedValue(false) },
      dummyPasswordHash: "dummy-hash",
      totp,
      sessions: { create: vi.fn() },
      clock: { now: () => new Date() },
      sessionTtlMilliseconds: 60_000,
    });

    await expect(application.execute(command)).resolves.toEqual({
      status: "INVALID_CREDENTIALS",
    });
    expect(totp.verify).not.toHaveBeenCalled();
    expect(store.issueSession).not.toHaveBeenCalled();
  });

  it("does not issue a session for an incorrect TOTP code", async () => {
    const store = {
      loadByCanonicalEmail: vi.fn().mockResolvedValue(account),
      issueSession: vi.fn(),
    };
    const application = new CreateAdminSession(store, {
      passwords: { verify: vi.fn().mockResolvedValue(true) },
      dummyPasswordHash: "dummy-hash",
      totp: { verify: vi.fn().mockReturnValue(null) },
      sessions: { create: vi.fn() },
      clock: { now: () => new Date() },
      sessionTtlMilliseconds: 60_000,
    });

    await expect(application.execute(command)).resolves.toEqual({
      status: "INVALID_CREDENTIALS",
    });
    expect(store.issueSession).not.toHaveBeenCalled();
  });

  it("rejects a replay when the store cannot atomically advance the TOTP counter", async () => {
    const store = {
      loadByCanonicalEmail: vi.fn().mockResolvedValue(account),
      issueSession: vi.fn().mockResolvedValue(false),
    };
    const application = new CreateAdminSession(store, {
      passwords: { verify: vi.fn().mockResolvedValue(true) },
      dummyPasswordHash: "dummy-hash",
      totp: { verify: vi.fn().mockReturnValue(42) },
      sessions: {
        create: vi.fn().mockReturnValue({
          bearer: "bearer",
          digest: Uint8Array.of(1),
          keyVersion: 1,
        }),
      },
      clock: { now: () => new Date() },
      sessionTtlMilliseconds: 60_000,
    });

    await expect(application.execute(command)).resolves.toEqual({
      status: "INVALID_CREDENTIALS",
    });
  });
});
