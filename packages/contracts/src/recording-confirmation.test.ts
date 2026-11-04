import { describe, expect, it } from "vitest";
import { recordingTranscriptionResultSchema } from "./index.js";

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
};
const result = (fields: Record<string, unknown>) => ({
  transcriptId: "t1",
  language: "zh-CN",
  fullText: segment.text,
  segments: [{ ...segment, ...fields }],
  finalBoundary: { acceptedEndMs: 1000, droppedIncompleteTail: false },
});

describe("recording qualitative confirmation contract", () => {
  it.each([
    {},
    { needsConfirmation: false },
    { needsConfirmation: true },
    {
      needsConfirmation: true,
      alternatives: ["另一种听法"],
      mostLikelyText: "另一种听法",
      mostLikelyIsInference: true,
    },
  ])("preserves explicit and legacy unknown state %j", (fields) => {
    expect(recordingTranscriptionResultSchema.parse(result(fields))).toEqual(
      result(fields),
    );
  });
  it.each([
    { needsConfirmation: "true" },
    { needsConfirmation: null },
    { needsConfirmation: false, alternatives: ["候选"] },
    {
      needsConfirmation: false,
      mostLikelyText: "候选",
      mostLikelyIsInference: true,
    },
    {
      needsConfirmation: true,
      mostLikelyText: "候选",
      mostLikelyIsInference: false,
    },
    {
      needsConfirmation: true,
      mostLikelyText: null,
      mostLikelyIsInference: true,
    },
    { needsConfirmation: true, alternatives: [" "] },
    { needsConfirmation: true, alternatives: ["重复", "重复"] },
  ])("rejects contradiction instead of hiding the evidence %j", (fields) => {
    expect(
      recordingTranscriptionResultSchema.safeParse(result(fields)).success,
    ).toBe(false);
  });
});
