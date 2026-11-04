import { Module } from "@nestjs/common";
import type { NestFastifyApplication } from "@nestjs/platform-fastify";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createHttpApp } from "../create-app.js";
import { AUTHENTICATE_ACCESS_TOKEN } from "./initial-settings.controller.js";
import {
  HANDLE_BLOCKED_SAFETY_APPLICATION,
  SafetyHandlingController,
} from "./safety-handling.controller.js";

const caseId = "20000000-0000-4000-8000-000000000001";
const reviewId = "50000000-0000-4000-8000-000000000001";
const authenticate = vi.fn();
const handling = {
  prepare: vi.fn(),
  accept: vi.fn(),
  returnToEdit: vi.fn(),
  submitManualReview: vi.fn(),
  manualReviewResult: vi.fn(),
};
@Module({
  controllers: [SafetyHandlingController],
  providers: [
    { provide: AUTHENTICATE_ACCESS_TOKEN, useValue: { authenticate } },
    { provide: HANDLE_BLOCKED_SAFETY_APPLICATION, useValue: handling },
  ],
})
class TestModule {}
let app: NestFastifyApplication | undefined;
beforeEach(() => {
  authenticate.mockReset().mockResolvedValue("account");
  Object.values(handling).forEach((fn) => fn.mockReset());
});
afterEach(async () => {
  await app?.close();
  app = undefined;
});

describe("safety handling HTTP adapter", () => {
  it("loads one owned case and exposes visible deidentification edits", async () => {
    handling.prepare.mockResolvedValue({
      source: {
        text: "联系张三",
        sourceType: "DIARY",
        contentShape: "ARTICLE",
        userReasonZh: "具体原因",
      },
      candidate: {
        sanitizedText: "联系某人",
        edits: [
          {
            originalText: "张三",
            replacementText: "某人",
            explanationZh: "隐藏姓名",
          },
        ],
      },
    });
    app = await createHttpApp(TestModule);
    const response = await app.inject({
      method: "POST",
      url: `/api/v1/safety/cases/${caseId}/handling`,
      headers: { authorization: "Bearer token" },
    });
    expect(response.statusCode).toBe(200);
    expect(response.json()).toMatchObject({
      safetyCaseId: caseId,
      deidentifiedSource: { text: "联系某人" },
    });
  });

  it("requires authentication and a stable explicit command for actions", async () => {
    handling.accept.mockResolvedValue(true);
    handling.submitManualReview.mockResolvedValue({
      reviewRequestId: reviewId,
      reviewStatus: "PENDING",
    });
    app = await createHttpApp(TestModule);
    expect(
      (
        await app.inject({
          method: "POST",
          url: `/api/v1/safety/cases/${caseId}/accept-deidentified`,
          payload: { commandId: "accept-1" },
        })
      ).statusCode,
    ).toBe(401);
    const accepted = await app.inject({
      method: "POST",
      url: `/api/v1/safety/cases/${caseId}/accept-deidentified`,
      headers: { authorization: "Bearer token" },
      payload: { commandId: "accept-1" },
    });
    expect(accepted.json()).toEqual({ status: "COMPLETED" });
    const reviewed = await app.inject({
      method: "POST",
      url: `/api/v1/safety/cases/${caseId}/manual-reviews`,
      headers: { authorization: "Bearer token" },
      payload: { commandId: "review-1" },
    });
    expect(reviewed.json()).toEqual({
      status: "SUBMITTED",
      reviewRequestId: reviewId,
      reviewStatus: "PENDING",
    });
  });

  it("resolves an owned message target to an adjudicated result", async () => {
    handling.manualReviewResult.mockResolvedValue({
      outcome: "APPROVED",
      source: {
        safetyCaseId: caseId,
        text: "内容",
        sourceType: "DIARY",
        contentShape: "ARTICLE",
        userReasonZh: "已通过",
      },
    });
    app = await createHttpApp(TestModule);
    const response = await app.inject({
      method: "GET",
      url: `/api/v1/safety/cases/manual-reviews/${reviewId}`,
      headers: { authorization: "Bearer token" },
    });
    expect(response.statusCode).toBe(200);
    expect(response.json()).toMatchObject({
      outcome: "APPROVED",
      source: { safetyCaseId: caseId, text: "内容" },
    });
    expect(handling.manualReviewResult).toHaveBeenCalledWith(
      "account",
      reviewId,
    );
  });
});
