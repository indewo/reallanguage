import { Module } from "@nestjs/common";
import type { NestFastifyApplication } from "@nestjs/platform-fastify";
import { afterEach, describe, expect, it, vi } from "vitest";
import { createHttpApp } from "../create-app.js";
import { AUTHENTICATE_ACCESS_TOKEN } from "./initial-settings.controller.js";
import {
  APP_MESSAGES_APPLICATION,
  AppMessagesController,
  MARK_ALL_APP_MESSAGES_READ_APPLICATION,
} from "./app-messages.controller.js";

const execute = vi.fn();
const markAllRead = vi.fn();
const authenticate = vi.fn();
@Module({
  controllers: [AppMessagesController],
  providers: [
    { provide: APP_MESSAGES_APPLICATION, useValue: { execute } },
    {
      provide: MARK_ALL_APP_MESSAGES_READ_APPLICATION,
      useValue: { execute: markAllRead },
    },
    { provide: AUTHENTICATE_ACCESS_TOKEN, useValue: { authenticate } },
  ],
})
class TestModule {}
let app: NestFastifyApplication | undefined;
afterEach(async () => {
  await app?.close();
  app = undefined;
  execute.mockReset();
  markAllRead.mockReset();
  authenticate.mockReset();
});

describe("app messages HTTP adapter", () => {
  it("authenticates ownership and serializes the authoritative list", async () => {
    authenticate.mockResolvedValue("account-1");
    execute.mockResolvedValue([
      {
        messageId: "m1",
        kind: "SYSTEM",
        summaryZh: "通知",
        relatedContentLabelZh: null,
        createdAt: new Date("2026-09-02T00:00:00Z"),
        read: false,
        target: null,
      },
    ]);
    app = await createHttpApp(TestModule);
    const response = await app.inject({
      method: "GET",
      url: "/api/v1/messages",
      headers: { authorization: "Bearer token" },
    });
    expect(response.statusCode).toBe(200);
    expect(response.json()).toMatchObject({
      messages: [{ messageId: "m1", createdAt: "2026-09-02T00:00:00.000Z" }],
    });
    expect(execute).toHaveBeenCalledWith({ accountId: "account-1" });
  });
  it("rejects an expired session before reading messages", async () => {
    authenticate.mockResolvedValue(null);
    app = await createHttpApp(TestModule);
    const response = await app.inject({
      method: "GET",
      url: "/api/v1/messages",
      headers: { authorization: "Bearer expired" },
    });
    expect(response.statusCode).toBe(401);
    expect(execute).not.toHaveBeenCalled();
  });
  it("marks all messages read for the authenticated account", async () => {
    authenticate.mockResolvedValue("account-1");
    markAllRead.mockResolvedValue({ updatedCount: 3 });
    app = await createHttpApp(TestModule);
    const response = await app.inject({
      method: "POST",
      url: "/api/v1/messages/read-all",
      headers: { authorization: "Bearer token" },
    });
    expect(response.statusCode).toBe(200);
    expect(response.json()).toEqual({ updatedCount: 3 });
    expect(markAllRead).toHaveBeenCalledWith({ accountId: "account-1" });
  });
});
