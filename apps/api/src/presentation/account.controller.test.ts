import { Module } from "@nestjs/common";
import type { NestFastifyApplication } from "@nestjs/platform-fastify";
import { afterEach, describe, expect, it, vi } from "vitest";
import { createHttpApp } from "../create-app.js";
import { AUTHENTICATE_ACCESS_TOKEN } from "./initial-settings.controller.js";
import {
  ACCOUNT_DELETION_APPLICATION,
  ACCOUNT_PROFILE_APPLICATION,
  AccountController,
} from "./account.controller.js";
const authenticate = vi.fn();
const execute = vi.fn();
const deleteAccount = vi.fn();
@Module({
  controllers: [AccountController],
  providers: [
    { provide: AUTHENTICATE_ACCESS_TOKEN, useValue: { authenticate } },
    { provide: ACCOUNT_PROFILE_APPLICATION, useValue: { execute } },
    {
      provide: ACCOUNT_DELETION_APPLICATION,
      useValue: { execute: deleteAccount },
    },
  ],
})
class TestModule {}
let app: NestFastifyApplication | undefined;
afterEach(async () => {
  await app?.close();
  app = undefined;
  authenticate.mockReset();
  execute.mockReset();
  deleteAccount.mockReset();
});
describe("account HTTP adapter", () => {
  it("returns only the authenticated account profile", async () => {
    authenticate.mockResolvedValue("account-1");
    execute.mockResolvedValue({ email: "learner@example.com" });
    app = await createHttpApp(TestModule);
    const response = await app.inject({
      method: "GET",
      url: "/api/v1/account/current",
      headers: { authorization: "Bearer token" },
    });
    expect(response.statusCode).toBe(200);
    expect(response.json()).toEqual({ email: "learner@example.com" });
    expect(execute).toHaveBeenCalledWith({ accountId: "account-1" });
  });
  it("does not disclose a missing or unauthenticated account", async () => {
    authenticate.mockResolvedValue(null);
    app = await createHttpApp(TestModule);
    expect(
      (
        await app.inject({
          method: "GET",
          url: "/api/v1/account/current",
          headers: { authorization: "Bearer expired" },
        })
      ).statusCode,
    ).toBe(401);
    expect(execute).not.toHaveBeenCalled();
  });
  it("deletes only after an authenticated explicit command", async () => {
    authenticate.mockResolvedValue("account-1");
    deleteAccount.mockResolvedValue({ status: "DELETED" });
    app = await createHttpApp(TestModule);
    const response = await app.inject({
      method: "DELETE",
      url: "/api/v1/account/current",
      headers: { authorization: "Bearer token" },
      payload: { commandId: "delete-1" },
    });
    expect(response.statusCode).toBe(200);
    expect(response.json()).toEqual({ status: "DELETED" });
    expect(deleteAccount).toHaveBeenCalledWith({
      accountId: "account-1",
      commandId: "delete-1",
    });
  });
});
