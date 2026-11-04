import { Module } from "@nestjs/common";
import type { NestFastifyApplication } from "@nestjs/platform-fastify";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createHttpApp } from "../create-app.js";
import { ExtractLearningTargets } from "@reallanguage/backend-modules/content-formation";
import { AUTHENTICATE_ACCESS_TOKEN } from "./initial-settings.controller.js";
import {
  EXTRACT_LEARNING_TARGETS_APPLICATION,
  LearningTargetController,
} from "./learning-target.controller.js";
const authenticate = vi.fn();
const execute = vi.fn();
@Module({
  controllers: [LearningTargetController],
  providers: [
    { provide: AUTHENTICATE_ACCESS_TOKEN, useValue: { authenticate } },
    { provide: EXTRACT_LEARNING_TARGETS_APPLICATION, useValue: { execute } },
  ],
})
class TestModule {}
let app: NestFastifyApplication | undefined;
beforeEach(() => {
  authenticate.mockReset().mockResolvedValue("account");
  execute.mockReset().mockResolvedValue({
    targets: [],
    queryableExpressions: [],
    learningScopes: ["CET_4"],
  });
});
afterEach(async () => {
  await app?.close();
  app = undefined;
});
describe("learning target HTTP adapter", () => {
  const settings = {
    formalityLevel: 3,
    slangEnabled: false,
    basicVocabulary: "CET_4",
    purposeVocabularies: [],
    styleReference: "NATURAL_CONCISE",
  };
  const wordTarget = (
    display: string,
    surfaceForm: string,
    startUtf16: number,
  ) => ({
    targetId: "target",
    unitId: "u1",
    display,
    explanationZh: "隔离语境义",
    type: "WORD" as const,
    structureParts: [],
    relatedVocabulary: [],
    selectedByDefault: true as const,
    sourceEvidence: {
      version: 1 as const,
      startUtf16,
      endUtf16: startUtf16 + surfaceForm.length,
      surfaceForm,
      normalizedForm: display,
      allowedForms: [surfaceForm],
    },
  });
  const inputFor = (textEn: string) => ({
    commandId: "word-binding-http",
    chapters: [
      {
        chapterId: "c1",
        sentences: [
          { unitId: "u1", textEn, textZh: "隔离原句", isFocus: true },
        ],
      },
    ],
  });
  it.each(["valid", "wrong-occurrence", "paraphrased-output", "null"])(
    "preserves and enforces accepted word explanations through the real HTTP adapter: %s",
    async (variant) => {
      const acceptedVocabulary = {
        display: "tea",
        senseZh: "茶",
        unitType: "WORD",
        sourceEvidence: wordTarget(
          "tea",
          "tea",
          variant === "wrong-occurrence" ? 16 : 8,
        ).sourceEvidence,
      };
      const target = {
        ...wordTarget("tea", "tea", 8),
        explanationZh: variant === "paraphrased-output" ? "茶叶" : "茶",
      };
      const extract = vi.fn().mockResolvedValue({
        targets: [target],
        queryableExpressions: [],
      });
      const application = new ExtractLearningTargets(
        { read: async () => settings },
        { extract },
      );
      execute.mockImplementation((command) => application.execute(command));
      app = await createHttpApp(TestModule);
      const response = await app.inject({
        method: "POST",
        url: "/api/v1/content-formation/learning-targets",
        headers: { authorization: "Bearer token" },
        payload: {
          ...inputFor("I drink tea and tea."),
          requestedExpression: {
            unitId: "u1",
            expressionId: "word:8",
            display: "tea",
            acceptedVocabulary: variant === "null" ? null : acceptedVocabulary,
          },
        },
      });
      expect(response.statusCode).toBe(
        variant === "valid"
          ? 200
          : variant === "paraphrased-output"
            ? 500
            : 400,
      );
      if (variant === "valid") {
        expect(response.json().targets[0]).toMatchObject({
          explanationZh: "茶",
          sourceEvidence: acceptedVocabulary.sourceEvidence,
        });
        expect(extract.mock.calls[0]?.[0]).toMatchObject({
          requestedExpression: { acceptedVocabulary },
        });
      } else if (variant !== "paraphrased-output")
        expect(extract).not.toHaveBeenCalled();
      else expect(response.json()).not.toHaveProperty("targets");
    },
  );
  it.each(["valid", "dangling", "cross-sentence"])(
    "delivers only a resolvable related snapshot through HTTP: %s",
    async (variant) => {
      const sourceEvidence = wordTarget("tea", "tea", 8).sourceEvidence;
      const relatedItem = {
        itemRef: "local-tea",
        unitId: variant === "cross-sentence" ? "u2" : "u1",
        display: "tea",
        senseZh: "茶",
        unitType: "WORD" as const,
        sourceEvidence,
      };
      const target = {
        ...wordTarget("drink tea", "drink tea", 2),
        type: "PATTERN" as const,
        structureParts: ["动词与宾语"],
        relatedVocabulary: ["tea"],
        relatedVocabularyItems: [relatedItem],
        relatedVocabularyItemRefs: [
          variant === "dangling" ? "missing" : relatedItem.itemRef,
        ],
      };
      const provider = {
        extract: vi.fn(async () => ({
          targets: [target],
          queryableExpressions: [],
        })),
      };
      const application = new ExtractLearningTargets(
        { read: async () => settings },
        provider,
      );
      execute.mockImplementation((command) => application.execute(command));
      app = await createHttpApp(TestModule);
      const response = await app.inject({
        method: "POST",
        url: "/api/v1/content-formation/learning-targets",
        headers: { authorization: "Bearer token" },
        payload: inputFor("I drink tea."),
      });
      expect(response.statusCode).toBe(variant === "valid" ? 200 : 500);
      if (variant === "valid") {
        expect(response.json().targets).toEqual([target]);
        expect(provider.extract).toHaveBeenCalledTimes(1);
        expect(execute.mock.calls[0]?.[0]).toMatchObject({
          accountId: "account",
          commandId: "word-binding-http",
        });
      } else {
        expect(response.json()).not.toHaveProperty("targets");
        expect(response.body).not.toContain("local-tea");
      }
    },
  );
  it("returns the delivered bilingual candidate example instead of dropping it at the HTTP boundary", async () => {
    const candidateExample = {
      textEn: "She drinks tea after lunch.",
      textZh: "她午饭后喝茶。",
    };
    execute.mockResolvedValue({
      targets: [{ ...wordTarget("tea", "tea", 8), candidateExample }],
      queryableExpressions: [],
      learningScopes: ["CET_4"],
    });
    app = await createHttpApp(TestModule);
    const response = await app.inject({
      method: "POST",
      url: "/api/v1/content-formation/learning-targets",
      headers: { authorization: "Bearer token" },
      payload: inputFor("I drink tea."),
    });
    expect(response.statusCode).toBe(200);
    expect(response.json().targets[0].candidateExample).toEqual(
      candidateExample,
    );
  });
  it("does not send a malformed bilingual candidate example as HTTP success", async () => {
    execute.mockResolvedValue({
      targets: [
        {
          ...wordTarget("tea", "tea", 8),
          candidateExample: { textEn: "She drinks tea." },
        },
      ],
      queryableExpressions: [],
      learningScopes: ["CET_4"],
    });
    app = await createHttpApp(TestModule);
    const response = await app.inject({
      method: "POST",
      url: "/api/v1/content-formation/learning-targets",
      headers: { authorization: "Bearer token" },
      payload: inputFor("I drink tea."),
    });
    expect(response.statusCode).toBe(500);
  });
  it.each([
    ["😀 I visited a café at 2pm.", "café", "cafe"],
    ["😀 I visited a café at 2pm.", "2pm", "pm"],
    ["It is 3D-printed.", "3D-printed", "D-printed"],
  ])(
    "accepts complete Unicode word %s / %s without accepting its %s fragment",
    async (textEn, surface, fragment) => {
      const start = textEn.indexOf(surface);
      const requestedExpression = {
        unitId: "u1",
        expressionId: `word:${start}`,
        display: surface,
      };
      const target = wordTarget(surface, surface, start);
      const extract = vi.fn(async () => ({
        targets: [target],
        queryableExpressions: [requestedExpression],
      }));
      const service = new ExtractLearningTargets(
        { read: async () => settings },
        { extract },
      );
      execute.mockImplementation((command) => service.execute(command));
      app = await createHttpApp(TestModule);
      const request = {
        method: "POST" as const,
        url: "/api/v1/content-formation/learning-targets",
        headers: { authorization: "Bearer token" },
      };
      const half = await app.inject({
        ...request,
        payload: {
          ...inputFor(textEn),
          requestedExpression: {
            unitId: "u1",
            expressionId: `word:${textEn.indexOf(fragment)}`,
            display: fragment,
          },
        },
      });
      expect(half.statusCode).toBe(400);
      expect(extract).not.toHaveBeenCalled();
      const full = await app.inject({
        ...request,
        payload: { ...inputFor(textEn), requestedExpression },
      });
      expect(full.statusCode).toBe(200);
      expect(full.json().targets[0].sourceEvidence).toEqual(
        target.sourceEvidence,
      );
      expect(full.json().queryableExpressions).toEqual([requestedExpression]);
      expect(extract).toHaveBeenCalledTimes(1);
    },
  );
  it.each(["application", "response-contract"])(
    "does not publish a tea target backed by coffee through %s",
    async (boundary) => {
      const invalidOutput = {
        targets: [wordTarget("tea", "coffee", 16)],
        queryableExpressions: [],
        learningScopes: ["CET_4"],
      };
      if (boundary === "application") {
        const service = new ExtractLearningTargets(
          { read: async () => settings },
          { extract: async () => invalidOutput },
        );
        execute.mockImplementation((command) => service.execute(command));
      } else execute.mockResolvedValue(invalidOutput);
      app = await createHttpApp(TestModule);
      const response = await app.inject({
        method: "POST",
        url: "/api/v1/content-formation/learning-targets",
        headers: { authorization: "Bearer token" },
        payload: inputFor("I drink tea and coffee."),
      });
      expect(response.statusCode).toBe(500);
      expect(response.json()).not.toHaveProperty("targets");
      expect(response.body).not.toContain("coffee");
    },
  );
  it.each(["'", "‘", "’", "ʼ", "＇"])(
    "rejects half a %s contraction before generation, then delivers its complete clicked word",
    async (apostrophe) => {
      const surface = `I${apostrophe}m`;
      const target = wordTarget(surface, surface, 0);
      const extract = vi.fn(async () => ({
        targets: [target],
        queryableExpressions: [],
      }));
      const service = new ExtractLearningTargets(
        { read: async () => settings },
        { extract },
      );
      execute.mockImplementation((command) => service.execute(command));
      app = await createHttpApp(TestModule);
      const request = {
        method: "POST" as const,
        url: "/api/v1/content-formation/learning-targets",
        headers: { authorization: "Bearer token" },
      };
      const half = await app.inject({
        ...request,
        payload: {
          ...inputFor(`${surface} ready.`),
          requestedExpression: {
            unitId: "u1",
            expressionId: "word:2",
            display: "m",
          },
        },
      });
      expect(half.statusCode).toBe(400);
      expect(extract).not.toHaveBeenCalled();
      const full = await app.inject({
        ...request,
        payload: {
          ...inputFor(`${surface} ready.`),
          requestedExpression: {
            unitId: "u1",
            expressionId: "word:0",
            display: surface,
          },
        },
      });
      expect(full.statusCode).toBe(200);
      expect(full.json().targets[0].sourceEvidence).toEqual(
        target.sourceEvidence,
      );
      expect(extract).toHaveBeenCalledTimes(1);
    },
  );
  it("returns a non-retryable input error before AI for a mismatched word offset", async () => {
    const extract = vi.fn(async () => ({
      targets: [],
      queryableExpressions: [],
    }));
    const service = new ExtractLearningTargets(
      {
        read: async () => ({
          formalityLevel: 3,
          slangEnabled: false,
          basicVocabulary: "CET_4",
          purposeVocabularies: [],
          styleReference: "NATURAL_CONCISE",
        }),
      },
      { extract },
    );
    execute.mockImplementation((command) => service.execute(command));
    app = await createHttpApp(TestModule);
    const response = await app.inject({
      method: "POST",
      url: "/api/v1/content-formation/learning-targets",
      headers: { authorization: "Bearer token" },
      payload: {
        chapters: [
          {
            chapterId: "c1",
            sentences: [
              {
                unitId: "u1",
                textZh: "我很高兴。",
                textEn: "I was happy.",
                isFocus: true,
              },
            ],
          },
        ],
        commandId: "mismatch",
        requestedExpression: {
          unitId: "u1",
          expressionId: "word:2",
          display: "happy",
        },
      },
    });
    expect(response.statusCode).toBe(400);
    expect(response.json().error).toMatchObject({
      code: "VALIDATION_FAILED",
      retryable: false,
    });
    expect(extract).not.toHaveBeenCalled();
  });
  it("authenticates and forwards confirmed bilingual chapters", async () => {
    app = await createHttpApp(TestModule);
    const payload = {
      chapters: [
        {
          chapterId: "c1",
          sentences: [
            {
              unitId: "u1",
              textZh: "我很高兴。",
              textEn: "I was happy.",
              isFocus: true,
            },
          ],
        },
      ],
      commandId: "cmd",
    };
    const response = await app.inject({
      method: "POST",
      url: "/api/v1/content-formation/learning-targets",
      headers: { authorization: "Bearer token" },
      payload,
    });
    expect(response.statusCode).toBe(200);
    expect(response.json().learningScopes).toEqual(["CET_4"]);
    expect(execute).toHaveBeenCalledWith({ accountId: "account", ...payload });
  });
});
