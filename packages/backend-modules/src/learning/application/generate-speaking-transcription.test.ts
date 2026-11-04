import { describe, expect, it } from "vitest";
import { GenerateSpeakingTranscription } from "./generate-speaking-transcription.js";

describe("GenerateSpeakingTranscription", () => {
  it("preserves unavailable confidence without inventing evidence", async () => {
    const application = new GenerateSpeakingTranscription({
      generate: async () => ({
        transcript: "I can explain it.",
        lowConfidenceSegments: [],
        confidence: null,
      }),
    });
    await expect(application.execute(command)).resolves.toMatchObject({
      confidence: null,
    });
  });
  it("returns an editable transcript with confidence evidence", async () => {
    const application = new GenerateSpeakingTranscription({
      generate: async () => ({
        transcript: "I felt calm today.",
        lowConfidenceSegments: ["calm"],
        confidence: 0.82,
      }),
    });
    await expect(application.execute(command)).resolves.toEqual({
      transcript: "I felt calm today.",
      lowConfidenceSegments: ["calm"],
      confidence: 0.82,
    });
  });

  it("rejects an invalid provider confidence", async () => {
    const application = new GenerateSpeakingTranscription({
      generate: async () => ({
        transcript: "text",
        lowConfidenceSegments: [],
        confidence: 2,
      }),
    });
    await expect(application.execute(command)).rejects.toThrow(
      "Invalid speaking transcription output",
    );
  });
});

const command = {
  accountId: "a1",
  chapterId: "c1",
  sentenceId: "s1",
  audioMimeType: "audio/mp4" as const,
  audioBase64: "YWJj",
  commandId: "cmd",
};
