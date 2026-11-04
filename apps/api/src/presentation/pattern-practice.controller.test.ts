import { Module } from "@nestjs/common";
import type { NestFastifyApplication } from "@nestjs/platform-fastify";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createHttpApp } from "../create-app.js";
import { AUTHENTICATE_ACCESS_TOKEN } from "./initial-settings.controller.js";
import {
  GENERATE_PATTERN_PRACTICE_FEEDBACK_APPLICATION,
  GENERATE_PATTERN_PRACTICE_APPLICATION,
  PatternPracticeController,
} from "./pattern-practice.controller.js";

const authenticate = vi.fn();
const execute = vi.fn();
const feedback = vi.fn();
@Module({
  controllers: [PatternPracticeController],
  providers: [
    { provide: AUTHENTICATE_ACCESS_TOKEN, useValue: { authenticate } },
    { provide: GENERATE_PATTERN_PRACTICE_APPLICATION, useValue: { execute } },
    {
      provide: GENERATE_PATTERN_PRACTICE_FEEDBACK_APPLICATION,
      useValue: { execute: feedback },
    },
  ],
})
class TestModule {}
let app: NestFastifyApplication | undefined;
beforeEach(() => {
  authenticate.mockReset().mockResolvedValue("account");
  execute.mockReset().mockResolvedValue({ status: "NOT_NATURALLY_VERIFIABLE" });
  feedback.mockReset().mockResolvedValue({ status: "INCONCLUSIVE" });
});
afterEach(async () => {
  await app?.close();
  app = undefined;
});

describe("pattern practice HTTP adapter", () => {
  it("authenticates and forwards one locked pattern", async () => {
    app = await createHttpApp(TestModule);
    const payload = {
      chapterId: "c1",
      sourceSentenceId: "s1",
      sourceTextEn: "I used to write by hand.",
      sourceTextZh: "我过去常常手写。",
      patternId: "p1",
      structure: "used to + verb",
      explanationZh: "表示过去的习惯。",
      commandId: "cmd",
    };
    const response = await app.inject({
      method: "POST",
      url: "/api/v1/learning/pattern-practice-sets",
      headers: { authorization: "Bearer token" },
      payload,
    });
    expect(response.statusCode).toBe(200);
    expect(execute).toHaveBeenCalledWith({ accountId: "account", ...payload });
  });
  it("forwards raw audio without granting pass-fail authority", async () => {
    app = await createHttpApp(TestModule);
    const payload = {
      chapterId: "c1",
      patternId: "p1",
      exampleId: "e1",
      mode: "RESPONSE",
      targetTextEn: "I used to walk.",
      targetStructure: "used to + verb",
      targetVisible: true,
      audioMimeType: "audio/mp4",
      audioBase64: "YWJj",
      commandId: "cmd",
    };
    const response = await app.inject({
      method: "POST",
      url: "/api/v1/learning/pattern-practice-feedback",
      headers: { authorization: "Bearer token" },
      payload,
    });
    expect(response.statusCode).toBe(200);
    expect(feedback).toHaveBeenCalledWith({ accountId: "account", ...payload });
  });
});
