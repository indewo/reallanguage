import { Controller, Get, HttpException, Module, Param } from "@nestjs/common";
import type { NestFastifyApplication } from "@nestjs/platform-fastify";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { createHttpApp } from "../create-app.js";
import {
  RegistrationController,
  GET_REGISTRATION_VERIFICATION_STATUS_APPLICATION,
  RESEND_REGISTRATION_VERIFICATION_APPLICATION,
  START_REGISTRATION_APPLICATION,
  VERIFY_REGISTRATION_APPLICATION,
} from "./registration.controller.js";

const startExecute = vi.fn();
const verifyExecute = vi.fn();
const statusExecute = vi.fn();
const resendExecute = vi.fn();

@Controller("test-errors")
class TestErrorsController {
  @Get(":status")
  public fail(@Param("status") status: string): never {
    if (status === "unknown") throw new Error("private failure detail");
    throw new HttpException("test", Number(status));
  }
}

@Module({
  controllers: [RegistrationController, TestErrorsController],
  providers: [
    {
      provide: START_REGISTRATION_APPLICATION,
      useValue: { execute: startExecute },
    },
    {
      provide: VERIFY_REGISTRATION_APPLICATION,
      useValue: { execute: verifyExecute },
    },
    {
      provide: GET_REGISTRATION_VERIFICATION_STATUS_APPLICATION,
      useValue: { execute: statusExecute },
    },
    {
      provide: RESEND_REGISTRATION_VERIFICATION_APPLICATION,
      useValue: { execute: resendExecute },
    },
  ],
})
class TestIdentityModule {}

let app: NestFastifyApplication | undefined;

beforeEach(() => {
  startExecute.mockReset();
  verifyExecute.mockReset();
  statusExecute.mockReset();
  resendExecute.mockReset();
});

afterEach(async () => {
  await app?.close();
  app = undefined;
});

describe("registration HTTP adapter", () => {
  it("maps the versioned request into the start-registration command", async () => {
    startExecute.mockResolvedValue({
      status: "EMAIL_VERIFICATION_REQUIRED",
      registrationId: "10000000-0000-4000-8000-000000000001",
    });
    app = await createHttpApp(TestIdentityModule);

    const response = await app.inject({
      method: "POST",
      url: "/api/v1/auth/registrations",
      headers: { "x-request-id": "request-1" },
      payload: {
        email: "learner@example.com",
        password: "a sufficiently long password",
        birthYearMonth: "2000-01",
        acceptedTerms: true,
        acceptedPrivacyPolicy: true,
      },
    });

    expect(response.statusCode).toBe(200);
    expect(response.json()).toEqual({
      status: "EMAIL_VERIFICATION_REQUIRED",
      registrationId: "10000000-0000-4000-8000-000000000001",
    });
    expect(startExecute).toHaveBeenCalledWith({
      requestId: "request-1",
      email: "learner@example.com",
      password: "a sufficiently long password",
      birthYearMonth: { year: 2000, month: 1 },
      acceptedTerms: true,
      acceptedPrivacyPolicy: true,
    });
  });

  it("returns the stable validation envelope without invoking the use case", async () => {
    app = await createHttpApp(TestIdentityModule);

    const response = await app.inject({
      method: "POST",
      url: "/api/v1/auth/registrations",
      headers: { "x-request-id": "request-2" },
      payload: {
        email: "not-an-email",
        password: "password",
        birthYearMonth: "2000-13",
        acceptedTerms: true,
        acceptedPrivacyPolicy: true,
      },
    });

    expect(response.statusCode).toBe(400);
    expect(response.json()).toEqual({
      error: {
        code: "VALIDATION_FAILED",
        message: "请求内容无效。",
        retryable: false,
        requestId: "request-2",
        details: {},
      },
    });
    expect(startExecute).not.toHaveBeenCalled();
  });

  it("normalizes malformed JSON into the same stable error envelope", async () => {
    app = await createHttpApp(TestIdentityModule);

    const response = await app.inject({
      method: "POST",
      url: "/api/v1/auth/registrations",
      headers: {
        "content-type": "application/json",
        "x-request-id": "request-malformed",
      },
      payload: '{"email":',
    });

    expect(response.statusCode).toBe(400);
    expect(response.json()).toEqual({
      error: {
        code: "VALIDATION_FAILED",
        message: "请求内容无效。",
        retryable: false,
        requestId: "request-malformed",
        details: {},
      },
    });
    expect(startExecute).not.toHaveBeenCalled();
  });

  it("maps a valid email bearer into the verification use case", async () => {
    verifyExecute.mockResolvedValue({
      status: "ACCOUNT_CREATED",
      accountId: "30000000-0000-4000-8000-000000000001",
    });
    app = await createHttpApp(TestIdentityModule);
    const bearer = Buffer.alloc(32, 7).toString("base64url");

    const response = await app.inject({
      method: "POST",
      url: "/api/v1/auth/registrations/10000000-0000-4000-8000-000000000001/verification",
      headers: { "x-request-id": "request-3" },
      payload: { bearer },
    });

    expect(response.statusCode).toBe(200);
    expect(response.json()).toEqual({
      status: "ACCOUNT_CREATED",
      accountId: "30000000-0000-4000-8000-000000000001",
    });
    expect(verifyExecute).toHaveBeenCalledWith({
      registrationId: "10000000-0000-4000-8000-000000000001",
      bearer,
    });
  });

  it("returns verification status for client synchronization", async () => {
    statusExecute.mockResolvedValue({ status: "VERIFIED" });
    app = await createHttpApp(TestIdentityModule);

    const response = await app.inject({
      method: "GET",
      url: "/api/v1/auth/registrations/10000000-0000-4000-8000-000000000001/verification-status",
      headers: { "x-request-id": "request-status" },
    });

    expect(response.statusCode).toBe(200);
    expect(response.json()).toEqual({ status: "VERIFIED" });
    expect(statusExecute).toHaveBeenCalledWith({
      registrationId: "10000000-0000-4000-8000-000000000001",
    });
  });

  it("serves a confirmation page without consuming the email bearer", async () => {
    app = await createHttpApp(TestIdentityModule);
    const bearer = Buffer.alloc(32, 7).toString("base64url");

    const response = await app.inject({
      method: "GET",
      url: `/api/v1/auth/registrations/10000000-0000-4000-8000-000000000001/verification/confirm?bearer=${bearer}`,
      headers: { "x-request-id": "request-link" },
    });

    expect(response.statusCode).toBe(200);
    expect(response.headers["content-type"]).toContain("text/html");
    expect(response.body).toContain("验证你的邮箱");
    expect(response.body).toContain("确认邮箱");
    expect(response.body).toContain("此链接已使用或已过期");
    expect(response.body).toContain("自动继续初始设置");
    expect(response.body).toContain("服务暂时出现问题");
    expect(response.body).toContain("无法连接 RealLanguage 服务");
    expect(verifyExecute).not.toHaveBeenCalled();
  });

  it("maps a resend request and returns the server-controlled cooldown", async () => {
    resendExecute.mockResolvedValue({
      status: "RESENT",
      retryAfterSeconds: 60,
    });
    app = await createHttpApp(TestIdentityModule);

    const response = await app.inject({
      method: "POST",
      url: "/api/v1/auth/registrations/10000000-0000-4000-8000-000000000001/verification-email",
      headers: { "x-request-id": "request-resend" },
    });

    expect(response.statusCode).toBe(200);
    expect(response.json()).toEqual({
      status: "RESENT",
      retryAfterSeconds: 60,
    });
    expect(resendExecute).toHaveBeenCalledWith({
      registrationId: "10000000-0000-4000-8000-000000000001",
      requestId: "request-resend",
    });
  });

  it.each([
    [401, "AUTHENTICATION_REQUIRED", false],
    [403, "FORBIDDEN", false],
    [404, "NOT_FOUND", false],
    [409, "CONFLICT", false],
    [429, "RATE_LIMITED", true],
    [502, "TEMPORARILY_UNAVAILABLE", true],
    [503, "TEMPORARILY_UNAVAILABLE", true],
    [504, "TEMPORARILY_UNAVAILABLE", true],
  ])(
    "normalizes HTTP %i without leaking framework responses",
    async (status, code, retryable) => {
      app = await createHttpApp(TestIdentityModule);

      const response = await app.inject({
        method: "GET",
        url: `/api/v1/test-errors/${status}`,
        headers: { "x-request-id": `request-${status}` },
      });

      expect(response.statusCode).toBe(status);
      expect(response.json()).toMatchObject({
        error: { code, retryable, requestId: `request-${status}`, details: {} },
      });
    },
  );

  it("does not leak unexpected exception details", async () => {
    app = await createHttpApp(TestIdentityModule);

    const response = await app.inject({
      method: "GET",
      url: "/api/v1/test-errors/unknown",
      headers: { "x-request-id": "request-500" },
    });

    expect(response.statusCode).toBe(500);
    expect(response.json()).toMatchObject({
      error: {
        code: "INTERNAL_ERROR",
        retryable: false,
        requestId: "request-500",
        details: {},
      },
    });
    expect(response.body).not.toContain("private failure detail");
  });
});
