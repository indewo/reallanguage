import { Module } from "@nestjs/common";
import type { NestFastifyApplication } from "@nestjs/platform-fastify";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createHttpApp } from "../create-app.js";
import { AUTHENTICATE_ACCESS_TOKEN } from "./initial-settings.controller.js";
import {
  GENERATE_SCENE_DRAFT_APPLICATION,
  GENERATE_SCENE_RECOMMENDATIONS_APPLICATION,
  GET_NEXT_SCENE_QUESTION_APPLICATION,
  REVIEW_SCENE_EDIT_APPLICATION,
  SceneFormationController,
} from "./scene-formation.controller.js";

const authenticate = vi.fn();
const recommendations = vi.fn();
const questions = vi.fn();
const drafts = vi.fn();
const reviews = vi.fn();
@Module({
  controllers: [SceneFormationController],
  providers: [
    { provide: AUTHENTICATE_ACCESS_TOKEN, useValue: { authenticate } },
    {
      provide: GENERATE_SCENE_RECOMMENDATIONS_APPLICATION,
      useValue: { execute: recommendations },
    },
    {
      provide: GET_NEXT_SCENE_QUESTION_APPLICATION,
      useValue: { execute: questions },
    },
    {
      provide: GENERATE_SCENE_DRAFT_APPLICATION,
      useValue: { execute: drafts },
    },
    { provide: REVIEW_SCENE_EDIT_APPLICATION, useValue: { execute: reviews } },
  ],
})
class TestModule {}
let app: NestFastifyApplication | undefined;
beforeEach(() => {
  authenticate.mockReset().mockResolvedValue("account");
  recommendations.mockReset().mockResolvedValue({ recommendations: [] });
  questions.mockReset().mockResolvedValue({
    action: "ASK",
    questionId: "q1",
    questionZh: "谁会参加？",
    options: [],
  });
  drafts.mockReset();
  reviews.mockReset();
});
afterEach(async () => {
  await app?.close();
  app = undefined;
});
describe("scene formation HTTP adapter", () => {
  it("authenticates recommendation and next-question operations", async () => {
    app = await createHttpApp(TestModule);
    expect(
      (
        await app.inject({
          method: "POST",
          url: "/api/v1/sources/scenes/recommendations",
          payload: {},
        })
      ).statusCode,
    ).toBe(401);
    const payload = {
      operationId: "operation",
      start: { kind: "DIRECT_DESCRIPTION", description: "练习工作沟通" },
      contentType: "DIALOGUE",
      confirmedAnswers: [],
      remainingQuestions: 10,
      remainingContentBudget: 10,
      stepIndex: 0,
      commandId: "command",
    };
    const response = await app.inject({
      method: "POST",
      url: "/api/v1/sources/scenes/next-question",
      headers: { authorization: "Bearer token" },
      payload,
    });
    expect(response.statusCode).toBe(200);
    expect(questions).toHaveBeenCalledWith({
      accountId: "account",
      ...payload,
    });
  });
});
