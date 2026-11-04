import { Module } from "@nestjs/common";
import type { NestFastifyApplication } from "@nestjs/platform-fastify";
import {
  HttpRecordingProvider,
  TranscribeRecording,
} from "@reallanguage/backend-modules/media";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createHttpApp } from "../create-app.js";
import { AUTHENTICATE_ACCESS_TOKEN } from "./initial-settings.controller.js";
import {
  RECORDING_TRANSCRIPTION_APPLICATION,
  TRANSCRIPT_REPAIR_APPLICATION,
  RecordingTranscriptionController,
} from "./recording-transcription.controller.js";

const authenticate = vi.fn();
const transcribe = vi.fn();
const repair = vi.fn();
@Module({
  controllers: [RecordingTranscriptionController],
  providers: [
    { provide: AUTHENTICATE_ACCESS_TOKEN, useValue: { authenticate } },
    {
      provide: RECORDING_TRANSCRIPTION_APPLICATION,
      useValue: { execute: transcribe },
    },
    { provide: TRANSCRIPT_REPAIR_APPLICATION, useValue: { execute: repair } },
  ],
})
class TestModule {}
let app: NestFastifyApplication | undefined;

beforeEach(() => {
  authenticate.mockReset().mockResolvedValue("account-1");
  transcribe.mockReset().mockResolvedValue({
    transcriptId: "transcript-1",
    language: "zh-CN",
    fullText: "今天我去公园。",
    segments: [],
    finalBoundary: { acceptedEndMs: 2_000, droppedIncompleteTail: false },
  });
  repair.mockReset().mockResolvedValue({ decision: "NO_CHANGE" });
});
afterEach(async () => {
  await app?.close();
  app = undefined;
});

describe("recording transcription HTTP adapter", () => {
  it("delivers qualitative confirmation and inference without replacing original text", async () => {
    const segment = {
      segmentId: "s1",
      startMs: 0,
      endMs: 2000,
      text: "今天我去公园。",
      speakerLabel: "UNKNOWN",
      confidence: null,
      needsConfirmation: true,
      alternatives: ["今天我去果园。"],
      mostLikelyText: "今天我去果园。",
      mostLikelyIsInference: true,
    };
    const output = {
      transcriptId: "t1",
      language: "zh-CN",
      fullText: segment.text,
      segments: [segment],
      finalBoundary: { acceptedEndMs: 2000, droppedIncompleteTail: false },
    };
    const fetcher = vi
      .fn()
      .mockResolvedValue(new Response(JSON.stringify(output)));
    const application = new TranscribeRecording(
      new HttpRecordingProvider(
        { endpoint: "https://provider.invalid", apiKey: "test-key" },
        fetcher,
      ),
    );
    transcribe.mockImplementation((command) => application.execute(command));
    app = await createHttpApp(TestModule);
    const response = await app.inject({
      method: "POST",
      url: "/api/v1/media/recording-transcriptions",
      headers: { authorization: "Bearer token" },
      payload: {
        recordingId: "r1",
        durationSeconds: 2,
        audioFormat: "audio/mp4",
        languageHint: "zh-CN",
        endReason: "USER_STOPPED",
        audioBase64: "YWJj",
        commandId: "c1",
      },
    });
    expect(response.statusCode).toBe(200);
    expect(response.json()).toMatchObject({
      fullText: segment.text,
      segments: [segment],
    });
    expect(transcribe).toHaveBeenCalledTimes(1);
    expect(fetcher).toHaveBeenCalledTimes(1);
    expect(fetcher.mock.calls[0]?.[1]?.headers).toMatchObject({
      "X-RealLanguage-Account-Id": "account-1",
      "Idempotency-Key": "c1",
    });
  });
  it("authenticates and forwards an explicitly submitted local recording", async () => {
    app = await createHttpApp(TestModule);
    const payload = {
      recordingId: "recording-1",
      durationSeconds: 2,
      audioFormat: "audio/mp4",
      languageHint: "zh-CN",
      endReason: "USER_STOPPED",
      audioBase64: Buffer.from("audio").toString("base64"),
      commandId: "command-1",
    };
    const response = await app.inject({
      method: "POST",
      url: "/api/v1/media/recording-transcriptions",
      headers: { authorization: "Bearer token" },
      payload,
    });
    expect(response.statusCode).toBe(200);
    expect(transcribe).toHaveBeenCalledWith({
      accountId: "account-1",
      ...payload,
    });
  });

  it("rejects unauthenticated transcription before processing audio", async () => {
    app = await createHttpApp(TestModule);
    const response = await app.inject({
      method: "POST",
      url: "/api/v1/media/recording-transcriptions",
      payload: {},
    });
    expect(response.statusCode).toBe(401);
    expect(transcribe).not.toHaveBeenCalled();
  });

  it("forwards an explicit repair request separately from transcription", async () => {
    app = await createHttpApp(TestModule);
    const payload = {
      transcriptId: "transcript-1",
      currentText: "今天我去公园。",
      manuallyEdited: true,
      audioFormat: "audio/mp4",
      audioBase64: Buffer.from("audio").toString("base64"),
      commandId: "command-2",
      evidence: {
        durationSeconds: 4,
        endReason: "USER_STOPPED",
        acceptedEndMs: 4000,
        segments: [
          {
            segmentId: "segment-1",
            startMs: 0,
            endMs: 4000,
            text: "今天我去公园。",
          },
        ],
      },
    };
    const response = await app.inject({
      method: "POST",
      url: "/api/v1/media/transcript-repairs",
      headers: { authorization: "Bearer token" },
      payload,
    });
    expect(response.statusCode).toBe(200);
    expect(repair).toHaveBeenCalledWith({ accountId: "account-1", ...payload });
    repair.mockClear();
    const legacy: Partial<typeof payload> = { ...payload };
    delete legacy.evidence;
    const rejected = await app.inject({
      method: "POST",
      url: "/api/v1/media/transcript-repairs",
      headers: { authorization: "Bearer token" },
      payload: legacy,
    });
    expect(rejected.statusCode).toBe(400);
    expect(repair).not.toHaveBeenCalled();
  });
});
