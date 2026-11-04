import { describe, expect, it, vi } from "vitest";

import {
  AuthenticateAdminSession,
  RevokeAdminSession,
} from "./manage-admin-session.js";

describe("admin session lifecycle", () => {
  it("authenticates a bearer only through its keyed digest", async () => {
    const authenticateDigest = vi.fn().mockResolvedValue("admin-1");
    const application = new AuthenticateAdminSession(
      { authenticateDigest, revokeDigest: vi.fn() },
      {
        sessions: {
          digestSession: vi.fn().mockReturnValue({
            digest: Uint8Array.of(4, 5, 6),
            keyVersion: 2,
          }),
        },
        clock: { now: () => new Date("2026-09-04T12:00:00.000Z") },
      },
    );

    await expect(application.execute("bearer-secret")).resolves.toEqual({
      status: "AUTHENTICATED",
      adminId: "admin-1",
    });
    expect(authenticateDigest).toHaveBeenCalledWith(
      Uint8Array.of(4, 5, 6),
      2,
      new Date("2026-09-04T12:00:00.000Z"),
    );
  });

  it("rejects empty and expired bearers without exposing account state", async () => {
    const authenticateDigest = vi.fn().mockResolvedValue(null);
    const application = new AuthenticateAdminSession(
      { authenticateDigest, revokeDigest: vi.fn() },
      dependencies(),
    );

    await expect(application.execute("")).resolves.toEqual({
      status: "INVALID_OR_EXPIRED",
    });
    await expect(application.execute("expired")).resolves.toEqual({
      status: "INVALID_OR_EXPIRED",
    });
    expect(authenticateDigest).toHaveBeenCalledTimes(1);
  });

  it("derives the administrator from the bearer before revoking the session", async () => {
    const store = {
      authenticateDigest: vi.fn().mockResolvedValue("admin-1"),
      revokeDigest: vi.fn().mockResolvedValue(true),
    };
    const application = new RevokeAdminSession(store, dependencies());

    await expect(application.execute("bearer-secret")).resolves.toEqual({
      status: "REVOKED",
    });
    expect(store.revokeDigest).toHaveBeenCalledWith(
      "admin-1",
      Uint8Array.of(7),
      3,
      new Date("2026-09-04T12:00:00.000Z"),
    );
  });
});

const dependencies = () => ({
  sessions: {
    digestSession: vi.fn().mockReturnValue({
      digest: Uint8Array.of(7),
      keyVersion: 3,
    }),
  },
  clock: { now: () => new Date("2026-09-04T12:00:00.000Z") },
});
