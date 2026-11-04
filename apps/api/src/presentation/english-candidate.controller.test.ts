import { Module } from "@nestjs/common";
import type { NestFastifyApplication } from "@nestjs/platform-fastify";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createHttpApp } from "../create-app.js";
import { AUTHENTICATE_ACCESS_TOKEN } from "./initial-settings.controller.js";
import {
  EnglishCandidateController,
  GENERATE_ENGLISH_CANDIDATES_APPLICATION,
} from "./english-candidate.controller.js";

const authenticate = vi.fn();
const execute = vi.fn();
@Module({
  controllers: [EnglishCandidateController],
  providers: [
    { provide: AUTHENTICATE_ACCESS_TOKEN, useValue: { authenticate } },
    { provide: GENERATE_ENGLISH_CANDIDATES_APPLICATION, useValue: { execute } },
  ],
})
class TestModule {}
let app: NestFastifyApplication | undefined;
beforeEach(() => {
  authenticate.mockReset().mockResolvedValue("account");
  execute.mockReset().mockResolvedValue({
    chapters: [
      {
        chapterId: "c1",
        sentences: [
          {
            sourceUnitId: "u1",
            textZh: "今天很好。",
            isFocus: true,
            candidates: [
              {
                candidateId: "e1",
                english: "Today was good.",
                chineseCorrespondenceZh: "今天很好。",
                meaningClaims: [],
                styleFit: "natural",
                reusedKnowledgeRefs: [],
              },
            ],
          },
        ],
      },
    ],
  });
});
afterEach(async () => {
  await app?.close();
  app = undefined;
});

describe("English candidate HTTP adapter", () => {
  it("authenticates and forwards the complete generation boundary", async () => {
    app = await createHttpApp(TestModule);
    const payload = {
      sourceType: "DIARY",
      units: [{ unitId: "u1", order: 0, textZh: "今天很好。" }],
      chapters: [{ chapterId: "c1", unitIds: ["u1"] }],
      selectedUnitIds: ["u1"],
      commandId: "cmd",
      knownKnowledge: [
        {
          ref: "PATTERN:p1",
          type: "PATTERN",
          display: "used to + verb",
          meaningZh: "表示过去习惯",
        },
      ],
    };
    const response = await app.inject({
      method: "POST",
      url: "/api/v1/content-formation/english-candidates",
      headers: { authorization: "Bearer token" },
      payload,
    });
    expect(response.statusCode).toBe(200);
    expect(execute).toHaveBeenCalledWith({ accountId: "account", ...payload });
  });
  it("rejects invalid chapter input before generation", async () => {
    app = await createHttpApp(TestModule);
    const response = await app.inject({
      method: "POST",
      url: "/api/v1/content-formation/english-candidates",
      headers: { authorization: "Bearer token" },
      payload: {
        sourceType: "DIARY",
        units: [],
        chapters: [],
        selectedUnitIds: [],
        commandId: "cmd",
      },
    });
    expect(response.statusCode).toBe(400);
    expect(execute).not.toHaveBeenCalled();
  });
});
