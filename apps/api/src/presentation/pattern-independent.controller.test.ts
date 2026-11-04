import { Module } from "@nestjs/common";
import type { NestFastifyApplication } from "@nestjs/platform-fastify";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createHttpApp } from "../create-app.js";
import { AUTHENTICATE_ACCESS_TOKEN } from "./initial-settings.controller.js";
import {
  EVALUATE_PATTERN_INDEPENDENT_APPLICATION,
  PatternIndependentController,
} from "./pattern-independent.controller.js";

const authenticate = vi.fn();
const execute = vi.fn();
@Module({
  controllers: [PatternIndependentController],
  providers: [
    { provide: AUTHENTICATE_ACCESS_TOKEN, useValue: { authenticate } },
    {
      provide: EVALUATE_PATTERN_INDEPENDENT_APPLICATION,
      useValue: { execute },
    },
  ],
})
class TestModule {}
let app: NestFastifyApplication | undefined;

beforeEach(() => {
  authenticate.mockReset().mockResolvedValue("account");
  execute.mockReset().mockResolvedValue({
    decision: "PASS",
    structurePreserved: true,
    contentSuitable: true,
    pronunciationIntelligible: true,
    feedbackZh: "通过。",
    confidence: 0.9,
  });
});
afterEach(async () => {
  await app?.close();
  app = undefined;
});

describe("pattern independent HTTP adapter", () => {
  it("requires explicit no-help hidden-target evidence", async () => {
    app = await createHttpApp(TestModule);
    const payload = {
      chapterId: "c1",
      patternId: "p1",
      exampleId: "e1",
      targetResponseEn: "I used to walk.",
      targetStructure: "used to + verb",
      targetHidden: true,
      answerAudioPlayed: false,
      helpUsed: false,
      audioMimeType: "audio/mp4",
      audioBase64: "YWJj",
      commandId: "cmd",
    };
    const response = await app.inject({
      method: "POST",
      url: "/api/v1/learning/pattern-independent-evaluation",
      headers: { authorization: "Bearer token" },
      payload,
    });
    expect(response.statusCode).toBe(200);
    expect(execute).toHaveBeenCalledWith({ accountId: "account", ...payload });
  });

  it("rejects visible targets", async () => {
    app = await createHttpApp(TestModule);
    const response = await app.inject({
      method: "POST",
      url: "/api/v1/learning/pattern-independent-evaluation",
      headers: { authorization: "Bearer token" },
      payload: { targetHidden: false },
    });
    expect(response.statusCode).toBe(400);
  });
});
