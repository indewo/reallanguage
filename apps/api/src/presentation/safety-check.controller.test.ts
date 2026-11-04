import { Module } from "@nestjs/common";
import type { NestFastifyApplication } from "@nestjs/platform-fastify";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { createHttpApp } from "../create-app.js";
import { AUTHENTICATE_ACCESS_TOKEN } from "./initial-settings.controller.js";
import {
  CHECK_CONTENT_SAFETY_APPLICATION,
  SafetyCheckController,
} from "./safety-check.controller.js";

const authenticate = vi.fn();
const execute = vi.fn();

@Module({
  controllers: [SafetyCheckController],
  providers: [
    { provide: AUTHENTICATE_ACCESS_TOKEN, useValue: { authenticate } },
    { provide: CHECK_CONTENT_SAFETY_APPLICATION, useValue: { execute } },
  ],
})
class TestSafetyModule {}

let app: NestFastifyApplication | undefined;

beforeEach(() => {
  authenticate.mockReset();
  execute.mockReset();
});
afterEach(async () => {
  await app?.close();
  app = undefined;
});

describe("safety check HTTP adapter", () => {
  it("authenticates and forwards only the confirmed source contract", async () => {
    authenticate.mockResolvedValue("30000000-0000-4000-8000-000000000001");
    execute.mockResolvedValue({ status: "PASSED" });
    app = await createHttpApp(TestSafetyModule);

    const response = await app.inject({
      method: "POST",
      url: "/api/v1/safety/checks",
      headers: { authorization: "Bearer access-token" },
      payload: validRequest,
    });

    expect(response.statusCode).toBe(200);
    expect(response.json()).toEqual({ status: "PASSED" });
    expect(execute).toHaveBeenCalledWith({
      accountId: "30000000-0000-4000-8000-000000000001",
      ...validRequest,
    });
  });

  it("rejects missing authentication and malformed content before dispatch", async () => {
    app = await createHttpApp(TestSafetyModule);
    expect(
      (
        await app.inject({
          method: "POST",
          url: "/api/v1/safety/checks",
          payload: validRequest,
        })
      ).statusCode,
    ).toBe(401);

    authenticate.mockResolvedValue("30000000-0000-4000-8000-000000000001");
    expect(
      (
        await app.inject({
          method: "POST",
          url: "/api/v1/safety/checks",
          headers: { authorization: "Bearer access-token" },
          payload: { ...validRequest, text: "" },
        })
      ).statusCode,
    ).toBe(400);
    expect(execute).not.toHaveBeenCalled();
  });
});

const validRequest = {
  text: "今天发生了一件值得记录的事。",
  sourceType: "DIARY",
  contentShape: "ARTICLE",
  commandId: "safety-command-1",
};
