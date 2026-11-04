import { BadRequestException, UnauthorizedException } from "@nestjs/common";
import { Test } from "@nestjs/testing";
import { describe, expect, it, vi } from "vitest";
import { AUTHENTICATE_ACCESS_TOKEN } from "./initial-settings.controller.js";
import {
  EVALUATE_SCENE_RESPONSE_APPLICATION,
  GENERATE_SCENE_RESPONSE_APPLICATION,
  GENERATE_SCENE_RESPONSE_FEEDBACK_APPLICATION,
  SceneResponseController,
} from "./scene-response.controller.js";

const execute = vi.fn();
const moduleRef = await Test.createTestingModule({
  controllers: [SceneResponseController],
  providers: [
    {
      provide: AUTHENTICATE_ACCESS_TOKEN,
      useValue: { authenticate: vi.fn().mockResolvedValue("account-1") },
    },
    { provide: GENERATE_SCENE_RESPONSE_APPLICATION, useValue: { execute } },
    {
      provide: GENERATE_SCENE_RESPONSE_FEEDBACK_APPLICATION,
      useValue: { execute },
    },
    { provide: EVALUATE_SCENE_RESPONSE_APPLICATION, useValue: { execute } },
  ],
}).compile();
const controller = moduleRef.get(SceneResponseController);

describe("SceneResponseController", () => {
  it("authenticates and accepts one bounded fixed task request", async () => {
    execute.mockResolvedValueOnce({
      taskId: "task-1",
      sentenceId: "s1",
      backgroundEn: "Background.",
      contextLines: [{ speaker: "AI", textEn: "What did you do?" }],
      targetSentenceEn: "I wrote a diary entry.",
    });
    await expect(
      controller.generate(
        {
          chapterId: "c1",
          focusSentenceId: "s1",
          chapterSentences: [
            {
              sentenceId: "s1",
              textEn: "I wrote a diary entry.",
              textZh: "我写了一篇日记。",
              isFocus: true,
            },
          ],
          commandId: "generate-1",
        },
        "Bearer token",
        "request-1",
      ),
    ).resolves.toMatchObject({ taskId: "task-1" });
  });

  it("rejects missing authentication and invalid third-round evidence", async () => {
    await expect(
      controller.generate({}, undefined, "request-1"),
    ).rejects.toBeInstanceOf(UnauthorizedException);
    await expect(
      controller.evaluate(
        { chapterId: "c1", roundIndex: 1 },
        "Bearer token",
        "request-1",
      ),
    ).rejects.toBeInstanceOf(BadRequestException);
  });
});
