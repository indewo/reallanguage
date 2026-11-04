import { describe, expect, it } from "vitest";
import { EvaluateFullSentenceSpeaking } from "./evaluate-full-sentence-speaking.js";

describe("EvaluateFullSentenceSpeaking", () => {
  it.each(["PASS", "FAIL"])(
    "does not accept a low-confidence %s",
    async (decision) => {
      const application = new EvaluateFullSentenceSpeaking({
        generate: async () => ({
          ...candidate,
          decision,
          pronunciationConfidence: 0.4,
        }),
      });
      await expect(application.execute(command)).resolves.toMatchObject({
        decision: "INCONCLUSIVE",
      });
    },
  );
  it("returns bounded content and raw-audio pronunciation evidence", async () => {
    const application = new EvaluateFullSentenceSpeaking({
      generate: async () => candidate,
    });
    await expect(application.execute(command)).resolves.toEqual(candidate);
  });

  it("rejects blank confirmed content before provider evaluation", async () => {
    const application = new EvaluateFullSentenceSpeaking({
      generate: async () => candidate,
    });
    await expect(
      application.execute({ ...command, confirmedTranscript: " " }),
    ).rejects.toThrow("Invalid full sentence speaking input");
  });
});

const candidate = {
  decision: "PASS",
  completenessScore: 90,
  coreMeaningRecognizable: true,
  pronunciationIntelligible: true,
  feedbackZh: "内容完整且发音清楚。",
  pronunciationConfidence: 0.9,
};
const command = {
  accountId: "a1",
  chapterId: "c1",
  sentenceId: "s1",
  targetTextEn: "I felt calm today.",
  confirmedTranscript: "I felt calm today.",
  targetHidden: true as const,
  answerAudioPlayed: false,
  userTranscriptConfirmed: true as const,
  audioMimeType: "audio/mp4" as const,
  audioBase64: "YWJj",
  commandId: "cmd",
};
