import { describe, expect, it } from "vitest";
import {
  sceneResponseEvaluationRequestSchema,
  sceneResponseGenerationRequestSchema,
  sceneResponseGenerationResultSchema,
  sceneResponseTrainingFeedbackRequestSchema,
} from "./index.js";

const audio = {
  audioMimeType: "audio/mp4" as const,
  audioBase64: Buffer.from("audio").toString("base64"),
};
const chapterSentences = [
  {
    sentenceId: "s1",
    textEn: "I wrote a diary entry.",
    textZh: "我写了一篇日记。",
    isFocus: true,
  },
];

describe("scene response contracts", () => {
  it.each(["chapterId", "focusSentenceId", "commandId"] as const)(
    "rejects blank %s without rewriting nonblank opaque identifiers",
    (field) => {
      const request = {
        chapterId: "c1",
        focusSentenceId: "s1",
        chapterSentences,
        commandId: "generate-1",
      };
      expect(
        sceneResponseGenerationRequestSchema.safeParse({
          ...request,
          [field]: " ",
        }).success,
      ).toBe(false);
      const nonblank = { ...request, [field]: "  scoped:id  " };
      expect(sceneResponseGenerationRequestSchema.parse(nonblank)[field]).toBe(
        nonblank[field],
      );
    },
  );

  it("fixes one bounded response task from the complete local chapter context", () => {
    expect(
      sceneResponseGenerationRequestSchema.parse({
        chapterId: "c1",
        focusSentenceId: "s1",
        chapterSentences,
        commandId: "generate-1",
      }),
    ).toBeTruthy();
    expect(
      sceneResponseGenerationResultSchema.parse({
        taskId: "task-1",
        sentenceId: "s1",
        backgroundEn: null,
        contextLines: [{ speaker: "AI", textEn: "What did you do?" }],
        targetSentenceEn: "I wrote a diary entry.",
      }),
    ).toBeTruthy();
  });

  it("separates the two training rounds from the hidden third-round evaluation", () => {
    const evidence = {
      chapterId: "c1",
      taskId: "task-1",
      sentenceId: "s1",
      targetTextEn: "I wrote a diary entry.",
      contextLines: [{ speaker: "AI", textEn: "What did you do?" }],
      confirmedTranscript: "I wrote a diary entry.",
      userTranscriptConfirmed: true as const,
      ...audio,
      commandId: "evaluate-1",
    };
    expect(
      sceneResponseTrainingFeedbackRequestSchema.parse({
        ...evidence,
        roundIndex: 1,
      }),
    ).toBeTruthy();
    expect(() =>
      sceneResponseTrainingFeedbackRequestSchema.parse({
        ...evidence,
        roundIndex: 2,
      }),
    ).toThrow();
    expect(
      sceneResponseEvaluationRequestSchema.parse({
        ...evidence,
        roundIndex: 2,
        targetHidden: true,
      }),
    ).toBeTruthy();
  });
});
