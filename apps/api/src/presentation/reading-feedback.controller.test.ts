import { Module } from "@nestjs/common";
import type { NestFastifyApplication } from "@nestjs/platform-fastify";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createHttpApp } from "../create-app.js";
import { AUTHENTICATE_ACCESS_TOKEN } from "./initial-settings.controller.js";
import {
  GENERATE_READING_FEEDBACK_APPLICATION,
  ReadingFeedbackController,
} from "./reading-feedback.controller.js";

const authenticate = vi.fn();
const execute = vi.fn();
@Module({
  controllers: [ReadingFeedbackController],
  providers: [
    { provide: AUTHENTICATE_ACCESS_TOKEN, useValue: { authenticate } },
    { provide: GENERATE_READING_FEEDBACK_APPLICATION, useValue: { execute } },
  ],
})
class TestModule {}

let app: NestFastifyApplication | undefined;
beforeEach(() => {
  authenticate.mockReset().mockResolvedValue("account");
  execute.mockReset().mockResolvedValue({
    feedbackZh: "内容完整。",
    feedbackItems: [
      {
        aspect: "WORD_OR_PHRASE",
        targetText: null,
        observationZh: "未发现可可靠定位的单词问题。",
        suggestionZh: "保持清晰。",
        evidence: "AUDIO",
      },
      {
        aspect: "RHYTHM",
        targetText: null,
        observationZh: "节奏证据正常。",
        suggestionZh: "保持自然停顿。",
        evidence: "AUDIO",
      },
      {
        aspect: "COMPLETENESS",
        targetText: null,
        observationZh: "识别文本覆盖整句。",
        suggestionZh: "保持完整表达。",
        evidence: "TRANSCRIPT",
      },
    ],
    transcript: "I felt calm today.",
    contentComplete: true,
    feedbackConfidence: 0.9,
    action: "CONTINUE",
  });
});
afterEach(async () => {
  await app?.close();
  app = undefined;
});

describe("reading feedback HTTP adapter", () => {
  it("authenticates and forwards one bounded audio attempt", async () => {
    app = await createHttpApp(TestModule);
    const payload = {
      chapterId: "c1",
      sentenceId: "s1",
      targetTextEn: "I felt calm today.",
      completionSignal: "TOUCH_REACHED_END",
      audioMimeType: "audio/mp4",
      audioBase64: "YWJj",
      commandId: "cmd",
    };
    const response = await app.inject({
      method: "POST",
      url: "/api/v1/learning/reading-feedback",
      headers: { authorization: "Bearer token" },
      payload,
    });
    expect(response.statusCode).toBe(200);
    expect(execute).toHaveBeenCalledWith({ accountId: "account", ...payload });
  });
});
