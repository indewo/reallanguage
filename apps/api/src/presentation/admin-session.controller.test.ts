import { Module } from "@nestjs/common";
import type { NestFastifyApplication } from "@nestjs/platform-fastify";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { createHttpApp } from "../create-app.js";
import {
  ADMIN_BOOTSTRAP_ACCESS,
  ADMIN_ACCOUNT_EMAIL,
  ADMIN_SESSION_AUTHENTICATOR,
  CREATE_ADMIN_SESSION_APPLICATION,
  REVOKE_ADMIN_SESSION_APPLICATION,
  SETUP_INITIAL_ADMIN_APPLICATION,
  AdminSessionController,
} from "./admin-session.controller.js";

const setup = {
  status: vi.fn(),
  resumeEnrollment: vi.fn(),
  execute: vi.fn(),
};
const createSession = { execute: vi.fn() };
const authenticate = { execute: vi.fn() };
const revoke = { execute: vi.fn() };
const bootstrapAccess = { allows: vi.fn() };

@Module({
  controllers: [AdminSessionController],
  providers: [
    { provide: SETUP_INITIAL_ADMIN_APPLICATION, useValue: setup },
    { provide: CREATE_ADMIN_SESSION_APPLICATION, useValue: createSession },
    { provide: ADMIN_SESSION_AUTHENTICATOR, useValue: authenticate },
    { provide: REVOKE_ADMIN_SESSION_APPLICATION, useValue: revoke },
    { provide: ADMIN_BOOTSTRAP_ACCESS, useValue: bootstrapAccess },
    { provide: ADMIN_ACCOUNT_EMAIL, useValue: "indewo@outlook.com" },
  ],
})
class TestAdminModule {}

let app: NestFastifyApplication | undefined;

beforeEach(() => {
  for (const mock of [
    setup.status,
    setup.resumeEnrollment,
    setup.execute,
    createSession.execute,
    authenticate.execute,
    revoke.execute,
    bootstrapAccess.allows,
  ])
    mock.mockReset();
  bootstrapAccess.allows.mockReturnValue(true);
});

afterEach(async () => {
  await app?.close();
  app = undefined;
});

describe("admin authentication HTTP adapter", () => {
  it("reports setup state without exposing a credential", async () => {
    setup.status.mockResolvedValue("UNCONFIGURED");
    app = await createHttpApp(TestAdminModule);

    const response = await app.inject({
      method: "GET",
      url: "/api/v1/admin/bootstrap/status",
    });

    expect(response.statusCode).toBe(200);
    expect(response.json()).toEqual({
      configured: false,
      enrollmentPending: false,
      email: "indewo@outlook.com",
    });
    expect(response.headers["cache-control"]).toBe("no-store");
  });

  it("recovers a pending MFA enrollment only from an allowed local address", async () => {
    setup.resumeEnrollment.mockResolvedValue({
      status: "CREATED",
      email: "indewo@outlook.com",
      totpSecret: "BASE32SECRET",
      provisioningUri: "otpauth://totp/example",
    });
    app = await createHttpApp(TestAdminModule);

    const response = await app.inject({
      method: "GET",
      url: "/api/v1/admin/bootstrap/enrollment",
    });

    expect(response.statusCode).toBe(200);
    expect(response.json()).toMatchObject({
      status: "CREATED",
      totpSecret: "BASE32SECRET",
    });
    expect(response.headers["cache-control"]).toBe("no-store");
    expect(bootstrapAccess.allows).toHaveBeenCalledWith("127.0.0.1");
  });

  it("initializes MFA only from an allowed local address", async () => {
    setup.execute.mockResolvedValue({
      status: "CREATED",
      email: "indewo@outlook.com",
      totpSecret: "BASE32SECRET",
      provisioningUri: "otpauth://totp/example",
    });
    app = await createHttpApp(TestAdminModule);

    const response = await app.inject({
      method: "POST",
      url: "/api/v1/admin/bootstrap",
      payload: { password: "a strong passphrase" },
    });

    expect(response.statusCode).toBe(201);
    expect(response.json()).toMatchObject({
      status: "CREATED",
      email: "indewo@outlook.com",
      totpSecret: "BASE32SECRET",
    });
    expect(setup.execute).toHaveBeenCalledWith({
      password: "a strong passphrase",
    });
    expect(bootstrapAccess.allows).toHaveBeenCalledWith("127.0.0.1");
  });

  it("rejects invalid setup input before calling the application", async () => {
    app = await createHttpApp(TestAdminModule);
    const response = await app.inject({
      method: "POST",
      url: "/api/v1/admin/bootstrap",
      headers: { "x-request-id": "admin-setup-invalid" },
      payload: { password: "" },
    });

    expect(response.statusCode).toBe(400);
    expect(response.json()).toMatchObject({
      error: { code: "VALIDATION_FAILED", requestId: "admin-setup-invalid" },
    });
    expect(setup.execute).not.toHaveBeenCalled();
  });

  it("creates, authenticates, and revokes an isolated administrator session", async () => {
    createSession.execute.mockResolvedValue({
      status: "AUTHENTICATED",
      accessToken: "admin-secret",
      expiresAt: new Date("2026-09-04T12:15:00.000Z"),
    });
    authenticate.execute.mockResolvedValue({
      status: "AUTHENTICATED",
      adminId: "admin-1",
    });
    revoke.execute.mockResolvedValue({ status: "REVOKED" });
    app = await createHttpApp(TestAdminModule);

    const login = await app.inject({
      method: "POST",
      url: "/api/v1/admin/sessions",
      payload: {
        email: "indewo@outlook.com",
        password: "a strong passphrase",
        totpCode: "123456",
      },
    });
    const current = await app.inject({
      method: "GET",
      url: "/api/v1/admin/sessions/current",
      headers: { authorization: "Bearer admin-secret" },
    });
    const logout = await app.inject({
      method: "DELETE",
      url: "/api/v1/admin/sessions/current",
      headers: { authorization: "Bearer admin-secret" },
    });

    expect(login.statusCode).toBe(200);
    expect(login.json()).toMatchObject({ status: "AUTHENTICATED" });
    expect(current.json()).toEqual({ status: "AUTHENTICATED" });
    expect(logout.json()).toEqual({ status: "REVOKED" });
    expect(authenticate.execute).toHaveBeenCalledWith("admin-secret");
    expect(revoke.execute).toHaveBeenCalledWith("admin-secret");
  });

  it("does not accept a missing or invalid bearer", async () => {
    authenticate.execute.mockResolvedValue({ status: "INVALID_OR_EXPIRED" });
    app = await createHttpApp(TestAdminModule);

    const missing = await app.inject({
      method: "GET",
      url: "/api/v1/admin/sessions/current",
    });
    const invalid = await app.inject({
      method: "GET",
      url: "/api/v1/admin/sessions/current",
      headers: { authorization: "Bearer bad" },
    });

    expect(missing.statusCode).toBe(401);
    expect(invalid.statusCode).toBe(401);
  });
});
