import { Module } from "@nestjs/common";
import {
  GenerateSceneResponse,
  type SceneResponseGenerationCommand,
} from "@reallanguage/backend-modules/learning";
import { sceneResponseGenerationResultSchema } from "@reallanguage/contracts";
import { describe, expect, it, vi } from "vitest";
import { createHttpApp } from "../create-app.js";
import { AUTHENTICATE_ACCESS_TOKEN } from "./initial-settings.controller.js";
import {
  EVALUATE_SCENE_RESPONSE_APPLICATION,
  GENERATE_SCENE_RESPONSE_APPLICATION,
  GENERATE_SCENE_RESPONSE_FEEDBACK_APPLICATION,
  SceneResponseController,
} from "./scene-response.controller.js";

const generate = vi.fn(async (command: SceneResponseGenerationCommand) => {
  const target = command.chapterSentences.find(
    (sentence) => sentence.sentenceId === command.focusSentenceId,
  )!;
  return {
    taskId: "task-1",
    sentenceId: target.sentenceId,
    backgroundEn: "You are describing your evening.",
    contextLines: [{ speaker: "AI", textEn: "What happened?" }],
    targetSentenceEn: target.textEn,
  };
});

@Module({
  controllers: [SceneResponseController],
  providers: [
    {
      provide: AUTHENTICATE_ACCESS_TOKEN,
      useValue: { authenticate: async (token: string) => `isolated-${token}` },
    },
    {
      provide: GENERATE_SCENE_RESPONSE_APPLICATION,
      useValue: new GenerateSceneResponse({ generate }),
    },
    { provide: GENERATE_SCENE_RESPONSE_FEEDBACK_APPLICATION, useValue: {} },
    { provide: EVALUATE_SCENE_RESPONSE_APPLICATION, useValue: {} },
  ],
})
class TestModule {}

describe("scene response fixed task HTTP identity", () => {
  it.each(["chapterId", "focusSentenceId", "commandId"] as const)(
    "rejects blank %s as input failure before the provider",
    async (field) => {
      generate.mockClear();
      const app = await createHttpApp(TestModule);
      try {
        const response = await app.inject({
          method: "POST",
          url: "/api/v1/learning/scene-response-tasks",
          headers: { authorization: "Bearer account-1" },
          payload: {
            chapterId: "c1",
            focusSentenceId: "s1",
            commandId: "generate-1",
            chapterSentences: [
              {
                sentenceId: "s1",
                textEn: "Test.",
                textZh: "测试。",
                isFocus: true,
              },
            ],
            [field]: " ",
          },
        });
        expect(response.statusCode).toBe(400);
        expect(response.json().error).toMatchObject({
          code: "VALIDATION_FAILED",
          retryable: false,
        });
        expect(generate).not.toHaveBeenCalled();
      } finally {
        await app.close();
      }
    },
  );

  it("delivers distinct bounded identities for each account/chapter/focus while preserving retry identity", async () => {
    generate.mockClear();
    const app = await createHttpApp(TestModule);
    try {
      const chapterSentences = [
        {
          sentenceId: "s1",
          textEn: "I wrote a diary entry.",
          textZh: "我写了一篇日记。",
          isFocus: true,
        },
        {
          sentenceId: "s2",
          textEn: "I felt more confident.",
          textZh: "我更有信心了。",
          isFocus: true,
        },
      ];
      const request = async (
        chapterId = "c1",
        focusSentenceId = "s1",
        account = "account-1",
      ) => {
        const response = await app.inject({
          method: "POST",
          url: "/api/v1/learning/scene-response-tasks",
          headers: { authorization: `Bearer ${account}` },
          payload: {
            chapterId,
            focusSentenceId,
            chapterSentences,
            commandId: `generate-${chapterId}-${focusSentenceId}`,
          },
        });
        expect(response.statusCode).toBe(200);
        return sceneResponseGenerationResultSchema.parse(response.json());
      };
      const first = await request();
      const retry = await request();
      const secondSentence = await request("c1", "s2");
      const secondChapter = await request("c2");
      const otherAccount = await request("c1", "s1", "account-2");
      expect(retry).toEqual(first);
      expect(
        new Set(
          [first, secondSentence, secondChapter, otherAccount].map(
            (task) => task.taskId,
          ),
        ).size,
      ).toBe(4);
      expect(first.taskId).toMatch(/^scene-[a-f0-9]{64}$/);
      expect(secondSentence).toMatchObject({
        sentenceId: "s2",
        targetSentenceEn: chapterSentences[1]!.textEn,
      });
      expect(generate).toHaveBeenCalledTimes(5);
    } finally {
      await app.close();
    }
  });
});
