import { Module } from "@nestjs/common";
import type { NestFastifyApplication } from "@nestjs/platform-fastify";
import {
  MeteredProviderFetchAdapter,
  RunMeteredProviderAttempt,
} from "@reallanguage/backend-modules/billing";
import {
  GenerateLearningExplanation,
  HttpLearningExplanationProvider,
} from "@reallanguage/backend-modules/learning";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createHttpApp } from "../create-app.js";
import { AUTHENTICATE_ACCESS_TOKEN } from "./initial-settings.controller.js";
import {
  GENERATE_LEARNING_EXPLANATION_APPLICATION,
  LearningExplanationController,
} from "./learning-explanation.controller.js";

// Isolated delivered-result fixtures: real billing/cache/application/HTTP code, no AI or database.
const claim = vi.fn();
const complete = vi.fn();
const fail = vi.fn();
const generate = vi.fn();
const reserve = vi.fn();
const settle = vi.fn();
const settings = {
  formalityLevel: 3,
  slangEnabled: false,
  basicVocabulary: "DAILY_CORE",
  purposeVocabularies: [],
  styleReference: "NATURAL_CONCISE",
};
const metered = new MeteredProviderFetchAdapter(
  new RunMeteredProviderAttempt(
    { reserve, settle, release: vi.fn(), requireReconciliation: vi.fn() },
    {
      quote: async () => ({
        providerAttemptId: "saved-attempt",
        maximumChargeFen: 80,
      }),
      execute: generate,
    },
    { claim, complete, fail },
  ),
  { WORD_EXPLANATION_V1: "解释点选表达" },
);
@Module({
  controllers: [LearningExplanationController],
  providers: [
    {
      provide: AUTHENTICATE_ACCESS_TOKEN,
      useValue: { authenticate: async () => "isolated-account" },
    },
    {
      provide: GENERATE_LEARNING_EXPLANATION_APPLICATION,
      useValue: new GenerateLearningExplanation(
        { read: async () => settings },
        new HttpLearningExplanationProvider(
          { endpoint: "https://isolated.invalid", apiKey: "test-only" },
          metered.fetch,
        ),
      ),
    },
  ],
})
class TestModule {}

const legacy = {
  kind: "EXPRESSION",
  expressionId: "happy-1",
  explanationZh: "高兴的；说明感受。",
  phonetic: null,
};
const delivered = (output: unknown) => ({
  status: "COMPLETED",
  result: {
    output,
    receipt: {
      providerAttemptId: "saved-attempt",
      modelSource: "isolated-test/model",
      providerCostMicroCny: 1,
    },
  },
});
let app: NestFastifyApplication | undefined;
beforeEach(() => {
  vi.clearAllMocks();
  claim.mockReset().mockResolvedValue(delivered(legacy));
  reserve.mockResolvedValue({ status: "SETTLED", reservedFen: 80 });
  settle.mockResolvedValue({ status: "SETTLED", chargedFen: 1 });
});
afterEach(async () => {
  await app?.close();
  app = undefined;
});
const request = (expressionId = "happy-1", display = "happy") =>
  app!.inject({
    method: "POST",
    url: "/api/v1/learning/explanations",
    headers: { authorization: "Bearer isolated-token" },
    payload: {
      chapter: {
        chapterId: "c1",
        sentences: [
          {
            sentenceId: "s1",
            textEn: "I was happy.",
            textZh: "我很高兴。",
            isFocus: true,
          },
        ],
      },
      focusSentenceId: "s1",
      round: 1,
      requestedExpression: { expressionId, display },
      commandId: `saved-${expressionId}`,
    },
  });

describe("expression detail delivery through the metered cache and HTTP", () => {
  it("replays a legacy paid result without calling AI or rewriting the stored result", async () => {
    app = await createHttpApp(TestModule);
    for (let replay = 0; replay < 2; replay++) {
      const response = await request();
      expect(response.statusCode).toBe(200);
      expect(response.json()).toEqual(legacy);
    }
    expect(claim).toHaveBeenCalledTimes(2);
    expect(claim).toHaveBeenCalledWith(
      expect.objectContaining({
        accountId: "isolated-account",
        commandId: "saved-happy-1",
        task: "WORD_EXPLANATION_V1",
        request: expect.objectContaining({
          input: expect.objectContaining({
            requestedExpression: { expressionId: "happy-1", display: "happy" },
            settings,
          }),
        }),
      }),
    );
    expect(generate).not.toHaveBeenCalled();
    expect(complete).not.toHaveBeenCalled();
    expect(fail).not.toHaveBeenCalled();
  });

  it("delivers each expression's own complete structured fields including a null hint", async () => {
    const first = {
      ...legacy,
      contextualMeaningZh: "高兴的",
      usageZh: "在 was 后说明过去的感受。",
      expressionHintZh: "也可用 feel happy 描述感受。",
    };
    const second = {
      ...legacy,
      expressionId: "was-1",
      explanationZh: "这里表示过去的状态。",
      contextualMeaningZh: "过去处于某种状态",
      usageZh: "第一人称单数过去时中连接主语与表语。",
      expressionHintZh: null,
    };
    claim
      .mockResolvedValueOnce(delivered(first))
      .mockResolvedValueOnce(delivered(second));
    app = await createHttpApp(TestModule);
    const happy = await request();
    const was = await request("was-1", "was");
    expect(happy.statusCode).toBe(200);
    expect(was.statusCode).toBe(200);
    expect(happy.json()).toEqual(first);
    expect(was.json()).toEqual(second);
    expect(generate).not.toHaveBeenCalled();
    expect(complete).not.toHaveBeenCalled();
  });

  it("does not turn a corrupt partial detail group into a successful HTTP delivery or an AI retry", async () => {
    claim.mockResolvedValue(
      delivered({ ...legacy, contextualMeaningZh: "高兴的" }),
    );
    app = await createHttpApp(TestModule);
    const response = await request();
    expect(response.statusCode).toBe(500);
    expect(response.json()).not.toHaveProperty("explanationZh");
    expect(generate).not.toHaveBeenCalled();
    expect(complete).not.toHaveBeenCalled();
  });
});
