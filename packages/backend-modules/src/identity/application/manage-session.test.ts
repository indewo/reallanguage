import { describe, expect, it, vi } from "vitest";

import {
  RefreshSession,
  RevokeSession,
  type SessionLifecycleRepository,
} from "./manage-session.js";

describe("RefreshSession", () => {
  it("returns one neutral outcome when the stored session is unavailable", async () => {
    const sessions: SessionLifecycleRepository = {
      rotate: vi.fn().mockResolvedValue(null),
      revoke: vi.fn(),
    };
    const useCase = new RefreshSession(sessions);

    await expect(
      useCase.execute({ refreshToken: "refresh", deviceId: "device" }),
    ).resolves.toEqual({ status: "INVALID_OR_EXPIRED" });
  });

  it("returns the atomically rotated session", async () => {
    const rotated = {
      accountId: "account-1",
      settingsCompleted: true,
      accessToken: "next-access",
      refreshToken: "next-refresh",
      accessTokenExpiresInSeconds: 900,
    };
    const rotate = vi.fn().mockResolvedValue(rotated);
    const useCase = new RefreshSession({ rotate, revoke: vi.fn() });

    await expect(
      useCase.execute({ refreshToken: "refresh", deviceId: "device" }),
    ).resolves.toEqual({ status: "AUTHENTICATED", ...rotated });
  });
});

describe("RevokeSession", () => {
  it("is idempotent and never reveals whether the bearer existed", async () => {
    const revoke = vi.fn().mockResolvedValue(undefined);
    const useCase = new RevokeSession({ rotate: vi.fn(), revoke });

    await expect(useCase.execute({ refreshToken: "refresh" })).resolves.toEqual(
      {
        status: "REVOKED",
      },
    );
    expect(revoke).toHaveBeenCalledWith("refresh");
  });
});
