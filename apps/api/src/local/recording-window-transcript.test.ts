import { describe, expect, it } from "vitest";
import {
  validateTimedRecording,
  assembleRecordingTranscript,
} from "./recording-time-evidence.js";
import {
  joinRecordingWindowEvidence,
  type RecordingWindowEvidence,
} from "./recording-window-transcript.js";

function part(
  start: number,
  end: number,
  coreStart: number | undefined,
  coreEnd: number | undefined,
  words: [string, number, number][],
): RecordingWindowEvidence {
  return {
    window: {
      startSeconds: start,
      durationSeconds: end - start,
      ...(coreStart === undefined
        ? {}
        : { coreStartSeconds: coreStart, coreEndSeconds: coreEnd! }),
    },
    evidence: validateTimedRecording(
      {
        text: words.map(([word]) => word).join(" "),
        language: "en",
        durationSeconds: end - start,
        words: words.map(([word, s, e]) => ({
          word,
          start: s - start,
          end: e - start,
        })),
      },
      end - start,
    ),
  };
}

describe("original-time context window reconciliation", () => {
  it("keeps full core words when padded edges contain truncated zero-duration tokens or a different partial-word guess", () => {
    const result = joinRecordingWindowEvidence([
      part(0, 20.38, 0, 19.38, [
        ["staff", 18.38, 18.66],
        ["borrow", 19.02, 19.34],
        ["money", 19.34, 19.56],
        ["for", 19.56, 19.98],
        ["staff", 20.16, 20.36],
        ["benefits", 20.36, 20.36],
      ]),
      part(18.38, 29.54, 19.38, 29.54, [
        ["company", 18.38, 18.56],
        ["borrow", 18.96, 19.36],
        ["money", 19.36, 19.58],
        ["for", 19.58, 20.0],
        ["staff", 20.16, 20.42],
        ["benefits", 20.42, 20.62],
      ]),
    ]);
    expect(result.words.map((word) => word.word)).toEqual([
      "staff",
      "borrow",
      "money",
      "for",
      "staff",
      "benefits",
    ]);
    expect(result.words.at(-1)).toEqual({
      word: "benefits",
      start: 20.42,
      end: 20.62,
    });
  });
  it("uses a shared audible word boundary when adjacent windows estimate slightly overlapping timestamps", () => {
    const result = joinRecordingWindowEvidence([
      part(0, 10.48, 0, 9.48, [
        ["staff", 9.14, 9.4],
        ["owe", 9.4, 9.7],
        ["debt", 9.7, 10.0],
      ]),
      part(8.48, 20, 9.48, 20, [
        ["staff", 9.14, 9.28],
        ["owe", 9.28, 9.76],
        ["debt", 9.76, 10.1],
      ]),
    ]);
    expect(result.text).toBe("staff owe debt");
    expect(result.words).toEqual([
      { word: "staff", start: 9.14, end: 9.4 },
      { word: "owe", start: 9.4, end: 9.7 },
      { word: "debt", start: 9.76, end: 10.1 },
    ]);
  });

  it("preserves a zero-duration provider word between two matching timed anchors without manufacturing its duration", () => {
    const result = joinRecordingWindowEvidence([
      part(0, 10.48, 0, 9.48, [
        ["staff", 9.14, 9.4],
        ["owe", 9.4, 9.74],
        ["debt", 9.74, 9.74],
        ["thousands", 9.74, 10.14],
      ]),
      part(8.48, 20, 9.48, 20, [
        ["staff", 9.14, 9.28],
        ["owe", 9.28, 9.76],
        ["debt", 9.76, 9.76],
        ["thousands", 9.76, 10.14],
      ]),
    ]);
    expect(result.words.map((word) => word.word)).toEqual([
      "staff",
      "owe",
      "debt",
      "thousands",
    ]);
    const debt = result.words.find((word) => word.word === "debt")!;
    expect(debt.start).toBe(debt.end);
    expect([9.74, 9.76]).toContain(debt.start);
    const transcript = assembleRecordingTranscript(
      validateTimedRecording(result, 20),
      {
        units: [
          {
            startWordIndex: 0,
            endWordIndex: 4,
            completion: "COMPLETE",
            needsConfirmation: false,
            alternatives: [],
            mostLikelyText: null,
            mostLikelyIsInference: false,
          },
        ],
      },
      "USER_STOPPED",
    );
    expect(transcript.segments[0]?.needsConfirmation).toBe(true);
  });
  it("keeps the existing non-overlapping zero-gap evidence unchanged", () => {
    expect(
      joinRecordingWindowEvidence([
        part(0, 5, undefined, undefined, [["Hello.", 0.2, 0.8]]),
        part(118, 120, undefined, undefined, [["because", 118.1, 118.7]]),
      ]),
    ).toEqual({
      text: "Hello. because",
      language: "en",
      durationSeconds: 120,
      words: [
        { word: "Hello.", start: 0.2, end: 0.8 },
        { word: "because", start: 118.1, end: 118.7 },
      ],
    });
  });

  it("keeps one timed occurrence heard in both padded windows, not both context copies", () => {
    const result = joinRecordingWindowEvidence([
      part(0, 11, 0, 10, [
        ["we", 9, 9.2],
        ["meet", 9.7, 10.15],
        ["today", 10.5, 10.9],
      ]),
      part(9, 20, 10, 20, [
        ["meet", 9.7, 10.15],
        ["today", 10.5, 10.9],
      ]),
    ]);
    expect(result.words.map((w) => w.word)).toEqual(["we", "meet", "today"]);
    expect(result.text).toBe("we meet today");
  });

  it("never removes genuine repetitions at different original times", () => {
    const result = joinRecordingWindowEvidence([
      part(0, 11, 0, 10, [
        ["go", 9.1, 9.3],
        ["go", 10.3, 10.5],
      ]),
      part(9, 20, 10, 20, [
        ["go", 9.1, 9.3],
        ["go", 10.3, 10.5],
      ]),
    ]);
    expect(result.words).toEqual([
      { word: "go", start: 9.1, end: 9.3 },
      { word: "go", start: 10.3, end: 10.5 },
    ]);
  });

  it("rejects a seam word shifted out of both core ranges instead of silently omitting it", () => {
    expect(() =>
      joinRecordingWindowEvidence([
        part(0, 11, 0, 10, [["turn", 9.9, 10.3]]),
        part(9, 20, 10, 20, [["turn", 9.7, 10.1]]),
      ]),
    ).toThrow(/boundary|seam|window/i);
  });

  it("rejects contradictory cross-boundary words rather than choosing an invented winner", () => {
    expect(() =>
      joinRecordingWindowEvidence([
        part(0, 11, 0, 10, [["turn", 9.6, 10.2]]),
        part(9, 20, 10, 20, [["burn", 9.7, 10.1]]),
      ]),
    ).toThrow(/boundary|seam|window/i);
  });

  it("does not lose a repeated context word when both timestamps move entirely across the seam", () => {
    expect(() =>
      joinRecordingWindowEvidence([
        part(0, 11, 0, 10, [
          ["we", 8.1, 8.3],
          ["turn", 10.02, 10.3],
        ]),
        part(9, 20, 10, 20, [
          ["turn", 9.7, 9.98],
          ["now", 11.2, 11.5],
        ]),
      ]),
    ).toThrow(/boundary|seam|window/i);
  });

  it("exposes padded seams to confirmation even if the semantic model claims certainty", () => {
    const timed = validateTimedRecording(
      joinRecordingWindowEvidence([
        part(0, 11, 0, 10, [
          ["go", 9.1, 9.3],
          ["go", 10.3, 10.5],
        ]),
        part(9, 20, 10, 20, [
          ["go", 9.1, 9.3],
          ["go", 10.3, 10.5],
        ]),
      ]),
      20,
    );
    const result = assembleRecordingTranscript(
      timed,
      {
        units: [
          {
            startWordIndex: 0,
            endWordIndex: 2,
            completion: "COMPLETE",
            needsConfirmation: false,
            alternatives: [],
            mostLikelyText: null,
            mostLikelyIsInference: false,
          },
        ],
      },
      "USER_STOPPED",
    );
    expect(result.segments[0]?.needsConfirmation).toBe(true);
  });
});
