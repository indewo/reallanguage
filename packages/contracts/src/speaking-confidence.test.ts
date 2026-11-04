import { describe, expect, it } from "vitest";
import {
  speakingTranscriptionResultSchema,
  recordingTranscriptionResultSchema,
} from "./index.js";

describe("speaking transcription confidence", () => {
  it("preserves unavailable recording segment confidence", () => {
    const result = recordingTranscriptionResultSchema.parse({
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
          confidence: null,
          alternatives: [],
          mostLikelyText: null,
          mostLikelyIsInference: false,
        },
      ],
      finalBoundary: { acceptedEndMs: 1000, droppedIncompleteTail: false },
    });
    expect(result.segments[0]?.confidence).toBeNull();
  });
  it("accepts explicitly unavailable evidence, not missing or invented values", () => {
    const base = { transcript: "I can explain it.", lowConfidenceSegments: [] };
    expect(
      speakingTranscriptionResultSchema.parse({ ...base, confidence: null })
        .confidence,
    ).toBeNull();
    expect(speakingTranscriptionResultSchema.safeParse(base).success).toBe(
      false,
    );
    expect(
      speakingTranscriptionResultSchema.safeParse({
        ...base,
        confidence: Number.NaN,
      }).success,
    ).toBe(false);
  });
});
