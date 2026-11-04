import { Module } from "@nestjs/common";
import type { NestFastifyApplication } from "@nestjs/platform-fastify";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { createHttpApp } from "../create-app.js";
import {
  CREATE_SESSION_APPLICATION,
  REFRESH_SESSION_APPLICATION,
  REVOKE_SESSION_APPLICATION,
  SessionController,
} from "./session.controller.js";

const execute = vi.fn();
const refreshExecute = vi.fn();
const revokeExecute = vi.fn();

@Module({
  controllers: [SessionController],
  providers: [
    { provide: CREATE_SESSION_APPLICATION, useValue: { execute } },
    {
      provide: REFRESH_SESSION_APPLICATION,
      useValue: { execute: refreshExecute },
    },
    {
      provide: REVOKE_SESSION_APPLICATION,
      useValue: { execute: revokeExecute },
    },
  ],
})
class TestSessionModule {}

let app: NestFastifyApplication | undefined;

beforeEach(() => {
  execute.mockReset();
  refreshExecute.mockReset();
  revokeExecute.mockReset();
});
afterEach(async () => {
  await app?.close();
  app = undefined;
});

describe("session HTTP adapter", () => {
  it("maps valid credentials and installation identity into the use case", async () => {
    const bearer = Buffer.alloc(32, 7).toString("base64url");
    execute.mockResolvedValue({
      status: "AUTHENTICATED",
      accountId: "30000000-0000-4000-8000-000000000001",
      accessToken: bearer,
      refreshToken: bearer,
      accessTokenExpiresInSeconds: 900,
      settingsCompleted: true,
    });
    app = await createHttpApp(TestSessionModule);

    const response = await app.inject({
      method: "POST",
      url: "/api/v1/auth/sessions",
      payload: {
        email: "learner@example.com",
        password: "not-a-real-password",
        deviceId: "android-installation-1",
      },
    });

    expect(response.statusCode).toBe(200);
    expect(response.json()).toMatchObject({
      status: "AUTHENTICATED",
      settingsCompleted: true,
    });
    expect(execute).toHaveBeenCalledWith({
      email: "learner@example.com",
      password: "not-a-real-password",
      deviceId: "android-installation-1",
    });
  });

  it("returns the stable validation envelope without invoking authentication", async () => {
    app = await createHttpApp(TestSessionModule);
    const response = await app.inject({
      method: "POST",
      url: "/api/v1/auth/sessions",
      headers: { "x-request-id": "request-login" },
      payload: { email: "bad", password: "", deviceId: "" },
    });

    expect(response.statusCode).toBe(400);
    expect(response.json()).toMatchObject({
      error: {
        code: "VALIDATION_FAILED",
        retryable: false,
        requestId: "request-login",
      },
    });
    expect(execute).not.toHaveBeenCalled();
  });

  it("maps refresh rotation and idempotent revocation to their use cases", async () => {
    const bearer = Buffer.alloc(32, 9).toString("base64url");
    refreshExecute.mockResolvedValue({ status: "INVALID_OR_EXPIRED" });
    revokeExecute.mockResolvedValue({ status: "REVOKED" });
    app = await createHttpApp(TestSessionModule);

    const refresh = await app.inject({
      method: "POST",
      url: "/api/v1/auth/sessions/refresh",
      payload: { refreshToken: bearer, deviceId: "android-installation-1" },
    });
    const revoke = await app.inject({
      method: "DELETE",
      url: "/api/v1/auth/sessions/current",
      payload: { refreshToken: bearer },
    });

    expect(refresh.json()).toEqual({ status: "INVALID_OR_EXPIRED" });
    expect(revoke.json()).toEqual({ status: "REVOKED" });
    expect(refreshExecute).toHaveBeenCalledWith({
      refreshToken: bearer,
      deviceId: "android-installation-1",
    });
    expect(revokeExecute).toHaveBeenCalledWith({ refreshToken: bearer });
  });
});
