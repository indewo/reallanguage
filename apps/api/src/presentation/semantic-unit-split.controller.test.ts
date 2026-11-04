import { Module } from "@nestjs/common";
import type { NestFastifyApplication } from "@nestjs/platform-fastify";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createHttpApp } from "../create-app.js";
import { AUTHENTICATE_ACCESS_TOKEN } from "./initial-settings.controller.js";
import {
  SemanticUnitSplitController,
  SPLIT_SEMANTIC_UNITS_APPLICATION,
} from "./semantic-unit-split.controller.js";

const authenticate = vi.fn();
const execute = vi.fn();
@Module({
  controllers: [SemanticUnitSplitController],
  providers: [
    { provide: AUTHENTICATE_ACCESS_TOKEN, useValue: { authenticate } },
    { provide: SPLIT_SEMANTIC_UNITS_APPLICATION, useValue: { execute } },
  ],
})
class TestModule {}
let app: NestFastifyApplication | undefined;
beforeEach(() => {
  authenticate.mockReset();
  execute.mockReset();
});
afterEach(async () => {
  await app?.close();
  app = undefined;
});

describe("semantic split HTTP adapter", () => {
  it("authenticates and returns exact source units", async () => {
    authenticate.mockResolvedValue("30000000-0000-4000-8000-000000000001");
    execute.mockResolvedValue({
      units: [
        {
          unitId: "u1",
          order: 0,
          sourceSpans: [{ startCodePoint: 0, endCodePoint: 2 }],
          textZh: "你好",
          speakerRole: null,
          semanticPurposeZh: "问候",
          recommendationReasonZh: null,
        },
      ],
      coverage: { complete: true, missingSpans: [], duplicatedSpans: [] },
    });
    app = await createHttpApp(TestModule);
    const response = await app.inject({
      method: "POST",
      url: "/api/v1/content-formation/semantic-units/split",
      headers: { authorization: "Bearer token" },
      payload: { text: "你好", contentShape: "ARTICLE", commandId: "cmd" },
    });
    expect(response.statusCode).toBe(200);
    expect(execute).toHaveBeenCalledWith({
      accountId: "30000000-0000-4000-8000-000000000001",
      text: "你好",
      contentShape: "ARTICLE",
      commandId: "cmd",
    });
  });

  it("rejects unauthenticated or empty source", async () => {
    app = await createHttpApp(TestModule);
    expect(
      (
        await app.inject({
          method: "POST",
          url: "/api/v1/content-formation/semantic-units/split",
          payload: { text: "你好", contentShape: "ARTICLE", commandId: "cmd" },
        })
      ).statusCode,
    ).toBe(401);
    authenticate.mockResolvedValue("account");
    expect(
      (
        await app.inject({
          method: "POST",
          url: "/api/v1/content-formation/semantic-units/split",
          headers: { authorization: "Bearer token" },
          payload: { text: "", contentShape: "ARTICLE", commandId: "cmd" },
        })
      ).statusCode,
    ).toBe(400);
  });
});
