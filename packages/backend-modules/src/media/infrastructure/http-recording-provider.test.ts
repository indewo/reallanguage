import { describe, expect, it, vi } from "vitest";
import { HttpRecordingProvider } from "./http-recording-provider.js";

describe("recording provider confidence decoding", () => {
  it.each([true, false, undefined, "true", null])(
    "preserves valid confirmation state and rejects malformed flags: %s",
    async (needsConfirmation) => {
      const fields =
        needsConfirmation === undefined ? {} : { needsConfirmation };
      const segment = {
        segmentId: "s1",
        startMs: 0,
        endMs: 1000,
        text: "原音内容。",
        speakerLabel: "UNKNOWN",
        confidence: null,
        alternatives: [],
        mostLikelyText: null,
        mostLikelyIsInference: false,
        ...fields,
      };
      const fetcher = vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            transcriptId: "t1",
            language: "zh-CN",
            fullText: segment.text,
            segments: [segment],
            finalBoundary: {
              acceptedEndMs: 1000,
              droppedIncompleteTail: false,
            },
          }),
        ),
      );
      const provider = new HttpRecordingProvider(
        { endpoint: "https://provider.invalid", apiKey: "test-key" },
        fetcher,
      );
      const result = provider.transcribe({
        accountId: "a1",
        recordingId: "r1",
        durationSeconds: 1,
        audioFormat: "audio/mp4",
        languageHint: "zh-CN",
        endReason: "USER_STOPPED",
        audioBase64: "YWJj",
        commandId: "c1",
      });
      if (needsConfirmation === null || typeof needsConfirmation === "string") {
        await expect(result).rejects.toThrow(
          "Invalid recording provider response",
        );
      } else {
        expect((await result).segments).toEqual([segment]);
      }
    },
  );
  it("does not turn malformed repair output into a no-change success", async () => {
    const provider = new HttpRecordingProvider(
      { endpoint: "https://provider.invalid", apiKey: "test-key" },
      vi.fn().mockResolvedValue(new Response("null")),
    );
    await expect(
      provider.repair({
        accountId: "a1",
        transcriptId: "t1",
        currentText: "原文",
        manuallyEdited: false,
        audioFormat: "audio/mp4",
        audioBase64: "YWJj",
        commandId: "c1",
        evidence: {
          durationSeconds: 1,
          endReason: "USER_STOPPED",
          acceptedEndMs: 1000,
          segments: [
            { segmentId: "s1", startMs: 0, endMs: 1000, text: "原文" },
          ],
        },
      }),
    ).rejects.toThrow("Invalid recording provider response");
  });
  it.each([null, 0.7])(
    "preserves supplied confidence %s",
    async (confidence) => {
      const fetcher = vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            transcriptId: "t1",
            language: "zh-CN",
            fullText: "内容",
            segments: [
              {
                segmentId: "s1",
                startMs: 0,
                endMs: 1000,
                text: "内容",
                speakerLabel: "UNKNOWN",
                confidence,
                alternatives: [],
                mostLikelyText: null,
                mostLikelyIsInference: false,
              },
            ],
            finalBoundary: {
              acceptedEndMs: 1000,
              droppedIncompleteTail: false,
            },
          }),
        ),
      );
      const provider = new HttpRecordingProvider(
        { endpoint: "https://provider.invalid", apiKey: "test-key" },
        fetcher,
      );
      const result = await provider.transcribe({
        accountId: "a1",
        recordingId: "r1",
        durationSeconds: 1,
        audioFormat: "audio/mp4",
        languageHint: "zh-CN",
        endReason: "USER_STOPPED",
        audioBase64: "YWJj",
        commandId: "c1",
      });
      expect(result.segments[0]?.confidence).toBe(confidence);
    },
  );
});
