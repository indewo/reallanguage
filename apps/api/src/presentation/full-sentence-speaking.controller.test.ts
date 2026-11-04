import { Module } from "@nestjs/common";
import type { NestFastifyApplication } from "@nestjs/platform-fastify";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createHttpApp } from "../create-app.js";
import { AUTHENTICATE_ACCESS_TOKEN } from "./initial-settings.controller.js";
import {
  EVALUATE_FULL_SENTENCE_SPEAKING_APPLICATION,
  FullSentenceSpeakingController,
  GENERATE_SPEAKING_TRANSCRIPTION_APPLICATION,
} from "./full-sentence-speaking.controller.js";

const authenticate = vi.fn();
const transcribe = vi.fn();
const evaluate = vi.fn();
@Module({
  controllers: [FullSentenceSpeakingController],
  providers: [
    { provide: AUTHENTICATE_ACCESS_TOKEN, useValue: { authenticate } },
    {
      provide: GENERATE_SPEAKING_TRANSCRIPTION_APPLICATION,
      useValue: { execute: transcribe },
    },
    {
      provide: EVALUATE_FULL_SENTENCE_SPEAKING_APPLICATION,
      useValue: { execute: evaluate },
    },
  ],
})
class TestModule {}

let app: NestFastifyApplication | undefined;
beforeEach(() => {
  authenticate.mockReset().mockResolvedValue("account");
  transcribe.mockReset().mockResolvedValue({
    transcript: "I felt calm today.",
    lowConfidenceSegments: [],
    confidence: 0.9,
  });
  evaluate.mockReset().mockResolvedValue({
    decision: "PASS",
    completenessScore: 90,
    coreMeaningRecognizable: true,
    pronunciationIntelligible: true,
    feedbackZh: "内容完整。",
    pronunciationConfidence: 0.9,
  });
});
afterEach(async () => {
  await app?.close();
  app = undefined;
});

describe("full sentence speaking HTTP adapter", () => {
  it("authenticates and forwards transcription audio", async () => {
    app = await createHttpApp(TestModule);
    const payload = audioPayload;
    const response = await app.inject({
      method: "POST",
      url: "/api/v1/learning/speaking-transcriptions",
      headers: { authorization: "Bearer token" },
      payload,
    });
    expect(response.statusCode).toBe(200);
    expect(transcribe).toHaveBeenCalledWith({
      accountId: "account",
      ...payload,
    });
  });

  it("authenticates diary dictation without requiring a learning chapter", async () => {
    app = await createHttpApp(TestModule);
    const payload = {
      audioMimeType: "audio/mp4",
      audioBase64: "YWJj",
      commandId: "dictation-command",
    };
    const response = await app.inject({
      method: "POST",
      url: "/api/v1/learning/diary-dictations",
      headers: { authorization: "Bearer token" },
      payload,
    });
    expect(response.statusCode).toBe(200);
    expect(transcribe).toHaveBeenCalledWith({
      accountId: "account",
      chapterId: "diary-input",
      sentenceId: "dictation-command",
      ...payload,
    });
  });

  it("forwards locally recorded eligibility and confirmed transcript", async () => {
    app = await createHttpApp(TestModule);
    const payload = {
      ...audioPayload,
      targetTextEn: "I felt calm today.",
      confirmedTranscript: "I felt calm today.",
      targetHidden: true,
      answerAudioPlayed: true,
      userTranscriptConfirmed: true,
    };
    const response = await app.inject({
      method: "POST",
      url: "/api/v1/learning/full-sentence-speaking-evaluations",
      headers: { authorization: "Bearer token" },
      payload,
    });
    expect(response.statusCode).toBe(200);
    expect(evaluate).toHaveBeenCalledWith({ accountId: "account", ...payload });
  });
});

const audioPayload = {
  chapterId: "c1",
  sentenceId: "s1",
  audioMimeType: "audio/mp4",
  audioBase64: "YWJj",
  commandId: "cmd",
};
