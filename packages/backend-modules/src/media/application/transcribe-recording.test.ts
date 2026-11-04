import { describe, expect, it, vi } from "vitest";
import { TranscribeRecording } from "./transcribe-recording.js";

const command = {
  accountId: "account-1",
  recordingId: "recording-1",
  durationSeconds: 42,
  audioFormat: "audio/mp4" as const,
  languageHint: "zh-CN" as const,
  endReason: "USER_STOPPED" as const,
  audioBase64: Buffer.from("audio").toString("base64"),
  commandId: "command-1",
};

describe("TranscribeRecording", () => {
  const valid = {
    transcriptId: "t1",
    language: "zh",
    fullText: "原音内容。",
    segments: [
      {
        segmentId: "s1",
        startMs: 20,
        endMs: 1000,
        text: "原音内容。",
        speakerLabel: "UNKNOWN",
        confidence: null,
        alternatives: [],
        mostLikelyText: null,
        mostLikelyIsInference: false,
      },
    ],
    finalBoundary: { acceptedEndMs: 1000, droppedIncompleteTail: false },
  };
  it.each([
    { needsConfirmation: "true" },
    { needsConfirmation: null },
    { needsConfirmation: false, alternatives: ["另一种听法"] },
    {
      needsConfirmation: false,
      mostLikelyText: "推测",
      mostLikelyIsInference: true,
    },
    {
      needsConfirmation: true,
      mostLikelyText: "推测",
      mostLikelyIsInference: false,
    },
    { needsConfirmation: true, alternatives: [" "] },
    { needsConfirmation: true, alternatives: ["重复", "重复"] },
  ])(
    "rejects contradictory qualitative confirmation evidence %j",
    async (fields) => {
      await expect(
        new TranscribeRecording({
          transcribe: vi.fn().mockResolvedValue({
            ...valid,
            segments: [{ ...valid.segments[0], ...fields }],
          }),
        }).execute(command),
      ).rejects.toThrow("Invalid transcription output");
    },
  );

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
  ])(
    "preserves confirmation state and original transcript without another request %j",
    async (fields) => {
      const output = {
        ...valid,
        segments: [{ ...valid.segments[0], ...fields }],
      };
      const transcribe = vi.fn().mockResolvedValue(output);
      const result = await new TranscribeRecording({ transcribe }).execute(
        command,
      );
      expect(result).toEqual(output);
      expect(transcribe).toHaveBeenCalledTimes(1);
    },
  );
  it.each([
    { ...valid, segments: [] },
    { ...valid, fullText: "没有原音证据的新内容。" },
    {
      ...valid,
      segments: [
        valid.segments[0],
        { ...valid.segments[0], startMs: 1000, endMs: 2000 },
      ],
      finalBoundary: { acceptedEndMs: 2000, droppedIncompleteTail: false },
    },
    {
      ...valid,
      finalBoundary: {
        acceptedEndMs: Number.NaN,
        droppedIncompleteTail: false,
      },
    },
    {
      ...valid,
      finalBoundary: { acceptedEndMs: 1000, droppedIncompleteTail: true },
    },
  ])("rejects missing or contradictory segment evidence %j", async (output) => {
    await expect(
      new TranscribeRecording({
        transcribe: vi.fn().mockResolvedValue(output),
      }).execute(command),
    ).rejects.toThrow("Invalid transcription output");
  });
  it.each([null, undefined, Number.NaN, Infinity, -0.1, 1.1])(
    "validates unavailable confidence explicitly: %s",
    async (confidence) => {
      const transcribe = vi.fn().mockResolvedValue({
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
        finalBoundary: { acceptedEndMs: 1000, droppedIncompleteTail: false },
      });
      const result = new TranscribeRecording({ transcribe }).execute(command);
      if (confidence === null)
        await expect(result).resolves.toMatchObject({
          segments: [{ confidence: null }],
        });
      else await expect(result).rejects.toThrow("Invalid transcription output");
    },
  );
  it("returns a validated semantic-boundary transcript", async () => {
    const transcribe = vi.fn().mockResolvedValue({
      transcriptId: "transcript-1",
      language: "zh-CN",
      fullText: "今天我去公园。",
      segments: [
        {
          segmentId: "segment-1",
          startMs: 0,
          endMs: 4_000,
          text: "今天我去公园。",
          speakerLabel: "USER",
          confidence: 0.94,
          alternatives: [],
          mostLikelyText: null,
          mostLikelyIsInference: false,
        },
      ],
      finalBoundary: { acceptedEndMs: 4_000, droppedIncompleteTail: false },
    });
    const result = await new TranscribeRecording({ transcribe }).execute(
      command,
    );

    expect(result.fullText).toBe("今天我去公园。");
    expect(transcribe).toHaveBeenCalledWith(command);
  });

  it("rejects recordings longer than two minutes and inconsistent provider output", async () => {
    const transcribe = vi.fn().mockResolvedValue({
      transcriptId: "transcript-1",
      language: "zh-CN",
      fullText: "内容",
      segments: [
        {
          segmentId: "segment-1",
          startMs: 2_000,
          endMs: 1_000,
          text: "内容",
          speakerLabel: "USER",
          confidence: 1,
          alternatives: [],
          mostLikelyText: null,
          mostLikelyIsInference: false,
        },
      ],
      finalBoundary: { acceptedEndMs: 1_000, droppedIncompleteTail: false },
    });
    const useCase = new TranscribeRecording({ transcribe });

    await expect(
      useCase.execute({ ...command, durationSeconds: 121 }),
    ).rejects.toThrow();
    await expect(useCase.execute(command)).rejects.toThrow(
      "Invalid transcription output",
    );
  });
});
