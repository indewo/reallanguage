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
  authenticate.mockReset().mockResolvedValue("owned-account");
  execute.mockReset();
});
afterEach(async () => {
  await app?.close();
  app = undefined;
});
const known = {
  ref: "PATTERN:p1",
  type: "PATTERN",
  display: "used to + verb",
  meaningZh: "过去的习惯",
};
const input = {
  commandId: "target-known-http",
  chapters: [
    {
      chapterId: "c1",
      sentences: [
        {
          unitId: "u1",
          textEn: "I used to walk.",
          textZh: "我以前常步行。",
          isFocus: true,
          reusedKnowledge: [known],
        },
      ],
    },
  ],
};
const target = {
  targetId: "t1",
  unitId: "u1",
  display: known.display,
  explanationZh: known.meaningZh,
  type: "PATTERN" as const,
  structureParts: ["used to", "+ verb"],
  relatedVocabulary: [],
  selectedByDefault: true as const,
  reusedKnowledgeRef: known.ref,
  sourceEvidence: {
    version: 1 as const,
    startUtf16: 2,
    endUtf16: 14,
    surfaceForm: "used to walk",
    normalizedForm: known.display,
    allowedForms: ["used to walk"],
  },
};
describe("owned known-target HTTP round trip", () => {
  const setup = async (targets = [target]) => {
    const extract = vi
      .fn()
      .mockResolvedValue({ targets, queryableExpressions: [] });
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
    return extract;
  };
  const send = (payload: Record<string, unknown>, token = "owned-token") =>
    app!.inject({
      method: "POST",
      url: "/api/v1/content-formation/learning-targets",
      headers: { authorization: `Bearer ${token}` },
      payload,
    });
  it("preserves current sentence and old identity across actual HTTP validation and decoding", async () => {
    const extract = await setup();
    const result = await send({ ...input, accountId: "wrong-owner" });
    expect(result.statusCode).toBe(200);
    expect(result.json().targets[0]).toEqual(target);
    expect(extract).toHaveBeenCalledTimes(1);
    expect(extract.mock.calls[0]![0].accountId).toBe("owned-account");
    expect(extract.mock.calls[0]![0].chapters).toEqual(input.chapters);
  });
  it("rejects malformed input before invoking the provider", async () => {
    const extract = await setup();
    const bad = {
      ...input,
      chapters: [
        {
          chapterId: "c1",
          sentences: [
            {
              ...input.chapters[0]!.sentences[0],
              reusedKnowledge: [known, known],
            },
          ],
        },
      ],
    };
    expect((await send(bad)).statusCode).toBe(400);
    expect(extract).not.toHaveBeenCalled();
  });
  it("does not publish a cross-sentence or omitted old identity as success", async () => {
    await setup([{ ...target, reusedKnowledgeRef: "PATTERN:wrong" }]);
    const result = await send(input);
    expect(result.statusCode).toBe(500);
    expect(result.json()).not.toHaveProperty("targets");
    expect(result.body).not.toContain("used to walk");
  });
  it("authentication failure never resolves knowledge or calls AI", async () => {
    const extract = await setup();
    authenticate.mockResolvedValue(undefined);
    expect((await send(input)).statusCode).toBe(401);
    expect(extract).not.toHaveBeenCalled();
  });
});
