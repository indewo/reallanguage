import { describe, expect, it, vi } from "vitest";
import { EvaluateSceneResponse } from "./evaluate-scene-response.js";
import { GenerateSceneResponseFeedback } from "./generate-scene-response-feedback.js";
import {
  GenerateSceneResponse,
  type SceneResponseGenerationCommand,
} from "./generate-scene-response.js";

const chapterSentences = [
  {
    sentenceId: "s1",
    textEn: "I wrote a diary entry.",
    textZh: "我写了一篇日记。",
    isFocus: true,
  },
];
const generationCommand = {
  accountId: "account-1",
  chapterId: "c1",
  focusSentenceId: "s1",
  chapterSentences,
  commandId: "generate-1",
};
const evidence = {
  accountId: "account-1",
  chapterId: "c1",
  taskId: "task-1",
  sentenceId: "s1",
  targetTextEn: "I wrote a diary entry.",
  contextLines: [{ speaker: "AI", textEn: "What did you do?" }],
  confirmedTranscript: "I wrote a diary entry.",
  userTranscriptConfirmed: true as const,
  audioMimeType: "audio/mp4" as const,
  audioBase64: "YXVkaW8=",
  commandId: "feedback-1",
};

describe("scene response applications", () => {
  it("accepts one fixed task without leaking the target into AI context", async () => {
    const generate = vi.fn().mockResolvedValue({
      taskId: "task-1",
      sentenceId: "s1",
      backgroundEn: "You are describing your evening.",
      contextLines: [{ speaker: "AI", textEn: "What did you do?" }],
      targetSentenceEn: "I wrote a diary entry.",
    });
    await expect(
      new GenerateSceneResponse({ generate }).execute(generationCommand),
    ).resolves.toMatchObject({
      taskId: expect.stringMatching(/^scene-[a-f0-9]{64}$/),
      sentenceId: "s1",
    });
  });

  it("separates the same model task ID across accounts, chapters and focus sentences", async () => {
    const generate = vi.fn(async (command: SceneResponseGenerationCommand) =>
      fixedTask(command),
    );
    const service = new GenerateSceneResponse({ generate });
    const commands = [
      generationCommand,
      { ...generationCommand, accountId: "account-2" },
      { ...generationCommand, chapterId: "c2" },
      {
        ...generationCommand,
        focusSentenceId: "s2",
        chapterSentences: [
          ...chapterSentences,
          {
            sentenceId: "s2",
            textEn: "I felt more confident.",
            textZh: "我更有信心了。",
            isFocus: true,
          },
        ],
      },
    ];
    const results = await Promise.all(
      commands.map((value) => service.execute(value)),
    );
    expect(new Set(results.map((value) => value.taskId)).size).toBe(
      commands.length,
    );
    expect(results.every((value) => value.taskId !== "task-1")).toBe(true);
    expect(generate).toHaveBeenCalledTimes(commands.length);
    expect(results[3]).toMatchObject({
      sentenceId: "s2",
      targetSentenceEn: "I felt more confident.",
    });
  });

  it("keeps fixed identity across retries and cached model outputs without relying on the model ID", async () => {
    const cached = fixedTask(generationCommand);
    const generate = vi
      .fn()
      .mockResolvedValueOnce(cached)
      .mockResolvedValueOnce(cached)
      .mockResolvedValueOnce({ ...cached, taskId: "different-provider-id" });
    const service = new GenerateSceneResponse({ generate });
    const first = await service.execute(generationCommand);
    const replay = await service.execute(generationCommand);
    const newProviderLabel = await service.execute({
      ...generationCommand,
      commandId: "retry-2",
    });
    expect(first.taskId).toMatch(/^scene-[a-f0-9]{64}$/);
    expect(replay).toEqual(first);
    expect(newProviderLabel).toEqual(first);
    // These are three application invocations with a fake port, not three real AI calls.
    expect(generate).toHaveBeenCalledTimes(3);
  });

  it("does not collapse separator-containing identity components", async () => {
    const service = new GenerateSceneResponse({
      generate: async (command) => fixedTask(command),
    });
    const left = await service.execute({
      ...generationCommand,
      chapterId: "c:s",
      focusSentenceId: "1",
      chapterSentences: [{ ...chapterSentences[0]!, sentenceId: "1" }],
    });
    const right = await service.execute({
      ...generationCommand,
      chapterId: "c",
      focusSentenceId: "s:1",
      chapterSentences: [{ ...chapterSentences[0]!, sentenceId: "s:1" }],
    });
    expect(left.taskId).not.toBe(right.taskId);
  });

  it.each(["accountId", "chapterId", "focusSentenceId", "commandId"] as const)(
    "rejects blank %s before asking the provider for a fixed identity",
    async (field) => {
      const generate = vi.fn(async (command: SceneResponseGenerationCommand) =>
        fixedTask(command),
      );
      await expect(
        new GenerateSceneResponse({ generate }).execute({
          ...generationCommand,
          [field]: " ",
        }),
      ).rejects.toThrow("Invalid scene response generation input");
      expect(generate).not.toHaveBeenCalled();
    },
  );

  it.each([
    { sentenceId: "other-sentence" },
    { targetSentenceEn: "I wrote in my journal." },
  ])(
    "rejects wrong source content before assigning an application identity: %o",
    async (changed) => {
      const generate = vi
        .fn()
        .mockResolvedValue({ ...fixedTask(generationCommand), ...changed });
      await expect(
        new GenerateSceneResponse({ generate }).execute(generationCommand),
      ).rejects.toThrow("Invalid scene response generation output");
    },
  );

  it("rejects a rewritten target or a target leaked as an AI line", async () => {
    const rewritten = new GenerateSceneResponse({
      generate: vi.fn().mockResolvedValue({
        taskId: "task-1",
        sentenceId: "s1",
        backgroundEn: "Background.",
        contextLines: [{ speaker: "AI", textEn: "I wrote a diary entry." }],
        targetSentenceEn: "I wrote in my journal.",
      }),
    });
    await expect(rewritten.execute(generationCommand)).rejects.toThrow(
      "Invalid scene response generation output",
    );
  });

  it("turns low-confidence training and evaluation evidence inconclusive", async () => {
    const feedback = new GenerateSceneResponseFeedback({
      generate: vi.fn().mockResolvedValue({
        status: "READY",
        feedbackZh: "需要重试。",
        pronunciationConfidence: 0.4,
      }),
    });
    await expect(
      feedback.execute({ ...evidence, roundIndex: 0 }),
    ).resolves.toEqual({ status: "INCONCLUSIVE" });

    const evaluation = new EvaluateSceneResponse({
      generate: vi.fn().mockResolvedValue({
        decision: "FAIL",
        correctTurn: true,
        targetSentenceComplete: false,
        pronunciationIntelligible: false,
        feedbackZh: "音频置信不足。",
        pronunciationConfidence: 0.4,
      }),
    });
    await expect(
      evaluation.execute({ ...evidence, roundIndex: 2, targetHidden: true }),
    ).resolves.toMatchObject({ decision: "INCONCLUSIVE" });
  });
});

function fixedTask(command: SceneResponseGenerationCommand) {
  const focus = command.chapterSentences.find(
    (sentence) => sentence.sentenceId === command.focusSentenceId,
  )!;
  return {
    taskId: "task-1",
    sentenceId: command.focusSentenceId,
    backgroundEn: "You are describing your evening.",
    contextLines: [{ speaker: "AI", textEn: "What happened?" }],
    targetSentenceEn: focus.textEn,
  };
}
