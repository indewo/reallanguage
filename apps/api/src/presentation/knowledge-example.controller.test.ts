import { Module } from "@nestjs/common";
import type { NestFastifyApplication } from "@nestjs/platform-fastify";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createHttpApp } from "../create-app.js";
import { AUTHENTICATE_ACCESS_TOKEN } from "./initial-settings.controller.js";
import {
  GENERATE_KNOWLEDGE_EXAMPLES_APPLICATION,
  KnowledgeExampleController,
} from "./knowledge-example.controller.js";

const authenticate = vi.fn();
const execute = vi.fn();
@Module({
  controllers: [KnowledgeExampleController],
  providers: [
    { provide: AUTHENTICATE_ACCESS_TOKEN, useValue: { authenticate } },
    { provide: GENERATE_KNOWLEDGE_EXAMPLES_APPLICATION, useValue: { execute } },
  ],
})
class TestModule {}
let app: NestFastifyApplication | undefined;
beforeEach(() => {
  authenticate.mockReset().mockResolvedValue("account");
  execute.mockReset().mockResolvedValue({
    itemId: "item",
    phonetic: "/rʌn/",
    pronunciationText: "run",
    representativeScene: "经营咖啡店",
    naturalRevalidation: null,
    examples: [0, 1, 2].map((index) => ({
      textEn: `Example ${index}`,
      textZh: `例句 ${index}`,
      pronunciationText: `Example ${index}`,
    })),
  });
});
afterEach(async () => {
  await app?.close();
  app = undefined;
});

describe("knowledge example HTTP adapter", () => {
  it("authenticates and forwards the fixed local source scene", async () => {
    app = await createHttpApp(TestModule);
    const payload = {
      itemId: "item",
      targetType: "VOCABULARY",
      display: "run",
      meaningZh: "经营",
      sourceScene: [{ textEn: "She runs a cafe.", textZh: "她经营咖啡店。" }],
      commandId: "command",
    };
    const response = await app.inject({
      method: "POST",
      url: "/api/v1/knowledge/examples",
      headers: { authorization: "Bearer token" },
      payload,
    });
    expect(response.statusCode).toBe(200);
    expect(response.json().examples).toHaveLength(3);
    expect(execute).toHaveBeenCalledWith({ accountId: "account", ...payload });
  });

  it("rejects unauthenticated and malformed requests before generation", async () => {
    app = await createHttpApp(TestModule);
    const response = await app.inject({
      method: "POST",
      url: "/api/v1/knowledge/examples",
      payload: {},
    });
    expect(response.statusCode).toBe(401);
    expect(execute).not.toHaveBeenCalled();
  });
});
