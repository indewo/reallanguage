import { describe, expect, it, vi } from "vitest";
import { GenerateReadingFeedback } from "./generate-reading-feedback.js";

const command = {
  accountId: "account",
  chapterId: "c1",
  sentenceId: "s1",
  targetTextEn: "I felt calm today.",
  completionSignal: "RECOGNITION_COMPLETE" as const,
  audioMimeType: "audio/mp4" as const,
  audioBase64: "YWJj",
  commandId: "cmd",
};

const feedbackItems = [
  {
    aspect: "WORD_OR_PHRASE",
    targetText: "calm",
    observationZh: "尾音清楚。",
    suggestionZh: "保持当前口型。",
    evidence: "AUDIO",
  },
  {
    aspect: "RHYTHM",
    targetText: null,
    observationZh: "句末停顿可辨。",
    suggestionZh: "重读 today。",
    evidence: "AUDIO",
  },
  {
    aspect: "COMPLETENESS",
    targetText: null,
    observationZh: "识别文本覆盖整句。",
    suggestionZh: "保持完整表达。",
    evidence: "TRANSCRIPT",
  },
] as const;

describe("GenerateReadingFeedback", () => {
  it("derives content completeness deterministically from the transcript", async () => {
    const generate = vi.fn().mockResolvedValue({
      feedbackZh: "内容完整，注意连读节奏。",
      feedbackItems,
      transcript: "I felt calm today",
      feedbackConfidence: 0.91,
      action: "CONTINUE",
    });
    const result = await new GenerateReadingFeedback({ generate }).execute(
      command,
    );
    expect(result.contentComplete).toBe(true);
    expect(generate).toHaveBeenCalledWith(command);
  });

  it("does not allow provider wording to grant completion", async () => {
    const result = await new GenerateReadingFeedback({
      generate: vi.fn().mockResolvedValue({
        feedbackZh: "请把整句读完整。",
        feedbackItems,
        transcript: "I felt calm",
        feedbackConfidence: 0.84,
        action: "REPEAT_FOR_CLARITY",
      }),
    }).execute(command);
    expect(result.contentComplete).toBe(false);
  });

  it("rejects missing audio and malformed provider output", async () => {
    const application = new GenerateReadingFeedback({
      generate: vi.fn().mockResolvedValue({ action: "PASS" }),
    });
    await expect(
      application.execute({ ...command, audioBase64: "" }),
    ).rejects.toThrow();
    await expect(application.execute(command)).rejects.toThrow();
  });
});
