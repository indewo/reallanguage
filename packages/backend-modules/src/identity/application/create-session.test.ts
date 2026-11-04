import { describe, expect, it, vi } from "vitest";

import {
  CreateSession,
  type CredentialVerifier,
  type SessionIssuer,
} from "./create-session.js";

const command = {
  email: "Learner@Example.com",
  password: "not-a-real-password",
  deviceId: "android-installation-1",
} as const;

describe("CreateSession", () => {
  it("returns one neutral result and never issues a session for invalid credentials", async () => {
    const issue = vi.fn();
    const useCase = new CreateSession(
      { verify: vi.fn().mockResolvedValue(null) },
      { issue },
    );

    await expect(useCase.execute(command)).resolves.toEqual({
      status: "INVALID_CREDENTIALS",
    });
    expect(issue).not.toHaveBeenCalled();
  });

  it("issues a device-bound session for a verified account", async () => {
    const verifier: CredentialVerifier = {
      verify: vi.fn().mockResolvedValue({
        accountId: "account-1",
        settingsCompleted: false,
      }),
    };
    const issuer: SessionIssuer = {
      issue: vi.fn().mockResolvedValue({
        accessToken: "access-token",
        refreshToken: "refresh-token",
        accessTokenExpiresInSeconds: 900,
      }),
    };
    const useCase = new CreateSession(verifier, issuer);

    await expect(useCase.execute(command)).resolves.toEqual({
      status: "AUTHENTICATED",
      accountId: "account-1",
      accessToken: "access-token",
      refreshToken: "refresh-token",
      accessTokenExpiresInSeconds: 900,
      settingsCompleted: false,
    });
    expect(issuer.issue).toHaveBeenCalledWith({
      accountId: "account-1",
      deviceId: "android-installation-1",
    });
  });
});
