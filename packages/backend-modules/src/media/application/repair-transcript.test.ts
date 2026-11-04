import { describe, expect, it, vi } from "vitest";
import { RepairTranscript } from "./repair-transcript.js";

describe("RepairTranscript", () => {
  const evidence = {
    durationSeconds: 120,
    endReason: "MAX_DURATION" as const,
    acceptedEndMs: 4000,
    segments: [
      {
        segmentId: "segment-1",
        startMs: 0,
        endMs: 4000,
        text: "今天我去公园。",
      },
    ],
  };
  const input = {
    accountId: "account-1",
    transcriptId: "transcript-1",
    currentText: "今天我公园。",
    manuallyEdited: false,
    audioFormat: "audio/mp4" as const,
    audioBase64: Buffer.from("isolated-audio").toString("base64"),
    commandId: "command-1",
    evidence,
  };

  it("rejects missing original timing evidence before invoking a paid repair", async () => {
    const repair = vi.fn().mockResolvedValue({ decision: "NO_CHANGE" });
    await expect(
      new RepairTranscript({ repair }).execute({
        ...input,
        evidence: undefined,
      }),
    ).rejects.toThrow();
    expect(repair).not.toHaveBeenCalled();
  });
  it("rejects a claimed boundary extending beyond the final evidence segment", async () => {
    const repair = vi.fn().mockResolvedValue({ decision: "NO_CHANGE" });
    await expect(
      new RepairTranscript({ repair }).execute({
        ...input,
        evidence: { ...evidence, acceptedEndMs: 5000 },
      }),
    ).rejects.toThrow();
    expect(repair).not.toHaveBeenCalled();
  });
  it("accepts only the explained evidence-bound replacement without rewriting surrounding text", async () => {
    const result = {
      decision: "PROPOSE_CHANGES",
      repairedText: "今天我去公园。",
      changes: [
        {
          originalText: "我公园",
          proposedText: "我去公园",
          evidenceSegmentIds: ["segment-1"],
          reason: "CONFIRMED_OMISSION",
        },
      ],
    };
    const repair = vi.fn().mockResolvedValue(result);
    await expect(
      new RepairTranscript({ repair }).execute(input),
    ).resolves.toEqual(result);
  });

  it.each([
    { repairedText: "今天我去公园。", ids: ["invented-segment"] },
    { repairedText: "今天我去公园。最后未录完的事实。", ids: ["segment-1"] },
    { repairedText: "今天我公园。", ids: ["segment-1"] },
  ])(
    "rejects unsupported references or extra/unchanged output: $repairedText",
    async ({ repairedText, ids }) => {
      const repair = vi.fn().mockResolvedValue({
        decision: "PROPOSE_CHANGES",
        repairedText,
        changes: [
          {
            originalText: "我公园",
            proposedText: "我去公园",
            evidenceSegmentIds: ids,
            reason: "CONFIRMED_OMISSION",
          },
        ],
      });
      await expect(
        new RepairTranscript({ repair }).execute(input),
      ).rejects.toThrow();
    },
  );

  it("passes through a no-change decision without inventing a problem", async () => {
    const repair = vi.fn().mockResolvedValue({ decision: "NO_CHANGE" });
    const result = await new RepairTranscript({ repair }).execute({
      accountId: "account-1",
      transcriptId: "transcript-1",
      currentText: "今天我去公园。",
      manuallyEdited: false,
      audioFormat: "audio/mp4",
      audioBase64: Buffer.from("audio").toString("base64"),
      commandId: "command-1",
      evidence,
    });
    expect(result).toEqual({ decision: "NO_CHANGE" });
  });

  it("rejects unsupported repair reasons and empty proposed text", async () => {
    const repair = vi.fn().mockResolvedValue({
      decision: "PROPOSE_CHANGES",
      repairedText: "",
      changes: [
        {
          originalText: "嗯",
          proposedText: "很好",
          evidenceSegmentIds: [],
          reason: "STYLE_IMPROVEMENT",
        },
      ],
    });
    await expect(
      new RepairTranscript({ repair }).execute({
        accountId: "account-1",
        transcriptId: "transcript-1",
        currentText: "嗯",
        manuallyEdited: true,
        audioFormat: "audio/mp4",
        audioBase64: Buffer.from("audio").toString("base64"),
        commandId: "command-1",
        evidence,
      }),
    ).rejects.toThrow("Invalid transcript repair output");
  });
});
