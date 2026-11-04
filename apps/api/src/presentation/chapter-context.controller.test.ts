import { Module } from "@nestjs/common";
import type { NestFastifyApplication } from "@nestjs/platform-fastify";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createHttpApp } from "../create-app.js";
import {
  ChapterContextController,
  SELECT_CHAPTER_CONTEXT_APPLICATION,
} from "./chapter-context.controller.js";
import { AUTHENTICATE_ACCESS_TOKEN } from "./initial-settings.controller.js";
const authenticate = vi.fn();
const execute = vi.fn();
@Module({
  controllers: [ChapterContextController],
  providers: [
    { provide: AUTHENTICATE_ACCESS_TOKEN, useValue: { authenticate } },
    { provide: SELECT_CHAPTER_CONTEXT_APPLICATION, useValue: { execute } },
  ],
})
class TestModule {}
let app: NestFastifyApplication | undefined;
beforeEach(() => {
  authenticate.mockReset().mockResolvedValue("account");
  execute
    .mockReset()
    .mockResolvedValue({ chapters: [{ chapterId: "c1", unitIds: ["u1"] }] });
});
afterEach(async () => {
  await app?.close();
  app = undefined;
});
describe("chapter context HTTP adapter", () => {
  it("authenticates and forwards the complete units and focus selection", async () => {
    app = await createHttpApp(TestModule);
    const payload = {
      units: [{ unitId: "u1", order: 0, textZh: "第一句" }],
      selectedUnitIds: ["u1"],
      commandId: "cmd",
    };
    const response = await app.inject({
      method: "POST",
      url: "/api/v1/content-formation/chapters/context-selection",
      headers: { authorization: "Bearer token" },
      payload,
    });
    expect(response.statusCode).toBe(200);
    expect(execute).toHaveBeenCalledWith({ accountId: "account", ...payload });
  });
  it("rejects unauthenticated or invalid focus selections", async () => {
    app = await createHttpApp(TestModule);
    const payload = {
      units: [{ unitId: "u1", order: 0, textZh: "第一句" }],
      selectedUnitIds: [],
      commandId: "cmd",
    };
    expect(
      (
        await app.inject({
          method: "POST",
          url: "/api/v1/content-formation/chapters/context-selection",
          payload,
        })
      ).statusCode,
    ).toBe(401);
    expect(
      (
        await app.inject({
          method: "POST",
          url: "/api/v1/content-formation/chapters/context-selection",
          headers: { authorization: "Bearer token" },
          payload,
        })
      ).statusCode,
    ).toBe(400);
  });
});
