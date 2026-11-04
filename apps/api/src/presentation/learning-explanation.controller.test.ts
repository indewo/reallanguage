import { Module } from "@nestjs/common";
import type { NestFastifyApplication } from "@nestjs/platform-fastify";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createHttpApp } from "../create-app.js";
import { GenerateLearningExplanation } from "@reallanguage/backend-modules/learning";
import { AUTHENTICATE_ACCESS_TOKEN } from "./initial-settings.controller.js";
import {
  GENERATE_LEARNING_EXPLANATION_APPLICATION,
  LearningExplanationController,
} from "./learning-explanation.controller.js";

const authenticate = vi.fn();
const execute = vi.fn();
@Module({
  controllers: [LearningExplanationController],
  providers: [
    { provide: AUTHENTICATE_ACCESS_TOKEN, useValue: { authenticate } },
    {
      provide: GENERATE_LEARNING_EXPLANATION_APPLICATION,
      useValue: { execute },
    },
  ],
})
class TestModule {}
let app: NestFastifyApplication | undefined;
beforeEach(() => {
  authenticate.mockReset().mockResolvedValue("account");
  execute.mockReset().mockResolvedValue({
    kind: "SENTENCE",
    explanationZh: "这句表达过去的感受。",
  });
});
afterEach(async () => {
  await app?.close();
  app = undefined;
});
describe("learning explanation HTTP adapter", () => {
  it("delivers a legacy unknown pattern with no fabricated structure through the application", async () => {
    const generate = vi
      .fn()
      .mockResolvedValue({ kind: "SENTENCE", explanationZh: "原有句式讲解。" });
    const application = new GenerateLearningExplanation(
      {
        read: async () => ({
          formalityLevel: 3,
          slangEnabled: false,
          basicVocabulary: "CET_4",
          purposeVocabularies: [],
          styleReference: "NATURAL_CONCISE",
        }),
      },
      { generate },
    );
    execute.mockImplementation((command) => application.execute(command));
    app = await createHttpApp(TestModule);
    const legacy = {
      targetId: "legacy",
      type: "PATTERN",
      display: "drink X",
      explanationZh: "饮用",
      structureParts: [],
      sourceEvidence: null,
      relatedVocabulary: null,
    };
    const response = await app.inject({
      method: "POST",
      url: "/api/v1/learning/explanations",
      headers: { authorization: "Bearer token" },
      payload: {
        chapter: {
          chapterId: "c1",
          sentences: [
            {
              sentenceId: "s1",
              textEn: "I drink tea.",
              textZh: "我喝茶。",
              isFocus: true,
            },
          ],
        },
        focusSentenceId: "s1",
        round: 1,
        commandId: "legacy-pattern-http",
        lockedTargets: [legacy],
      },
    });
    expect(response.statusCode).toBe(200);
    expect(generate).toHaveBeenCalledWith(
      expect.objectContaining({ lockedTargets: [legacy] }),
    );
  });
  it("rejects contradictory source evidence as a bad request before the provider is called", async () => {
    const generate = vi
      .fn()
      .mockResolvedValue({ kind: "SENTENCE", explanationZh: "不能交付" });
    const application = new GenerateLearningExplanation(
      {
        read: async () => ({
          formalityLevel: 3,
          slangEnabled: false,
          basicVocabulary: "CET_4",
          purposeVocabularies: [],
          styleReference: "NATURAL_CONCISE",
        }),
      },
      { generate },
    );
    execute.mockImplementation((command) => application.execute(command));
    app = await createHttpApp(TestModule);
    const response = await app.inject({
      method: "POST",
      url: "/api/v1/learning/explanations",
      headers: { authorization: "Bearer token" },
      payload: {
        chapter: {
          chapterId: "c1",
          sentences: [
            {
              sentenceId: "s1",
              textEn: "I drink tea.",
              textZh: "我喝茶。",
              isFocus: true,
            },
          ],
        },
        focusSentenceId: "s1",
        round: 1,
        commandId: "invalid-lock-http",
        lockedTargets: [
          {
            targetId: "tea",
            type: "WORD",
            display: "coffee",
            explanationZh: "咖啡",
            structureParts: [],
            sourceEvidence: {
              version: 1,
              startUtf16: 8,
              endUtf16: 11,
              surfaceForm: "tea",
              normalizedForm: "tea",
              allowedForms: ["tea"],
            },
            relatedVocabulary: [],
          },
        ],
      },
    });
    expect(response.statusCode).toBe(400);
    expect(generate).not.toHaveBeenCalled();
    expect(response.json()).not.toHaveProperty("explanationZh");
  });
  it("forwards current locked targets and the complete positional group without trimming their meaning", async () => {
    app = await createHttpApp(TestModule);
    const payload = {
      chapter: {
        chapterId: "c1",
        sentences: [
          {
            sentenceId: "s1",
            textEn: "I drink tea.",
            textZh: "我喝茶。",
            isFocus: true,
          },
        ],
      },
      focusSentenceId: "s1",
      round: 1,
      commandId: "locked-http",
      lockedTargets: [
        {
          targetId: "tea",
          type: "WORD",
          display: "tea",
          explanationZh: " 茶 ",
          structureParts: [],
          sourceEvidence: {
            version: 1,
            startUtf16: 8,
            endUtf16: 11,
            surfaceForm: "tea",
            normalizedForm: "tea",
            allowedForms: ["tea"],
          },
          relatedVocabulary: [],
        },
      ],
      requestedExpression: {
        expressionId: "word-2",
        display: "tea",
        startUtf16: 8,
        endUtf16: 11,
      },
    };
    execute.mockResolvedValue({
      kind: "EXPRESSION",
      expressionId: "word-2",
      explanationZh: "这里指饮品。",
      phonetic: null,
      contextualMeaningZh: " 茶 ",
      usageZh: "作为 drink 的宾语。",
      expressionHintZh: null,
    });
    const response = await app.inject({
      method: "POST",
      url: "/api/v1/learning/explanations",
      headers: { authorization: "Bearer token" },
      payload,
    });
    expect(response.statusCode).toBe(200);
    expect(execute).toHaveBeenCalledWith({ accountId: "account", ...payload });
    expect(response.json().contextualMeaningZh).toBe(" 茶 ");
  });
  it("authenticates and forwards only fixed chapter content", async () => {
    app = await createHttpApp(TestModule);
    const payload = {
      chapter: {
        chapterId: "c1",
        sentences: [
          {
            sentenceId: "u1",
            textEn: "I was happy.",
            textZh: "我很高兴。",
            isFocus: true,
          },
        ],
      },
      focusSentenceId: "u1",
      round: 1,
      commandId: "cmd",
    };
    const response = await app.inject({
      method: "POST",
      url: "/api/v1/learning/explanations",
      headers: { authorization: "Bearer token" },
      payload,
    });
    expect(response.statusCode).toBe(200);
    expect(execute).toHaveBeenCalledWith({ accountId: "account", ...payload });
  });
});
