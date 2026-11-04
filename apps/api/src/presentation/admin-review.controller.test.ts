import { Module } from "@nestjs/common";
import type { NestFastifyApplication } from "@nestjs/platform-fastify";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { createHttpApp } from "../create-app.js";
import { ADMIN_SESSION_AUTHENTICATOR } from "./admin-session.controller.js";
import {
  ADMIN_REVIEW_DECISION_APPLICATION,
  ADMIN_REVIEW_DETAILS_APPLICATION,
  ADMIN_REVIEW_LIST_APPLICATION,
  AdminReviewController,
} from "./admin-review.controller.js";

const authenticate = { execute: vi.fn() };
const list = { execute: vi.fn() };
const details = { execute: vi.fn() };
const decide = { execute: vi.fn() };

@Module({
  controllers: [AdminReviewController],
  providers: [
    { provide: ADMIN_SESSION_AUTHENTICATOR, useValue: authenticate },
    { provide: ADMIN_REVIEW_LIST_APPLICATION, useValue: list },
    { provide: ADMIN_REVIEW_DETAILS_APPLICATION, useValue: details },
    { provide: ADMIN_REVIEW_DECISION_APPLICATION, useValue: decide },
  ],
})
class TestAdminReviewModule {}

let app: NestFastifyApplication | undefined;
beforeEach(() => {
  for (const mock of [
    authenticate.execute,
    list.execute,
    details.execute,
    decide.execute,
  ])
    mock.mockReset();
  authenticate.execute.mockResolvedValue({
    status: "AUTHENTICATED",
    adminId: "admin-1",
  });
});
afterEach(async () => {
  await app?.close();
  app = undefined;
});

describe("admin review HTTP adapter", () => {
  it("returns a metadata-only pending queue for an authenticated admin", async () => {
    list.execute.mockResolvedValue([
      {
        reviewRequestId: "review-1",
        sourceType: "DIARY",
        reasonCode: "PERSONAL_DATA",
        userReasonZh: "包含个人信息",
        createdAt: new Date("2026-09-04T12:00:00.000Z"),
      },
    ]);
    app = await createHttpApp(TestAdminReviewModule);
    const response = await app.inject({
      method: "GET",
      url: "/api/v1/admin/manual-reviews",
      headers: { authorization: "Bearer admin-secret" },
    });
    expect(response.statusCode).toBe(200);
    expect(response.json()).toEqual({
      items: [
        expect.objectContaining({
          reviewRequestId: "review-1",
          createdAt: "2026-09-04T12:00:00.000Z",
        }),
      ],
    });
    expect(response.body).not.toContain("text");
  });

  it("passes the authenticated admin and request id into sensitive detail access", async () => {
    details.execute.mockResolvedValue({
      reviewRequestId: "review-1",
      sourceType: "DIARY",
      contentShape: "ARTICLE",
      reasonCode: "PERSONAL_DATA",
      userReasonZh: "包含个人信息",
      createdAt: new Date("2026-09-04T12:00:00.000Z"),
      text: "必要内容",
    });
    app = await createHttpApp(TestAdminReviewModule);
    const response = await app.inject({
      method: "GET",
      url: "/api/v1/admin/manual-reviews/review-1",
      headers: {
        authorization: "Bearer admin-secret",
        "x-request-id": "request-view",
      },
    });
    expect(response.statusCode).toBe(200);
    expect(details.execute).toHaveBeenCalledWith(
      "admin-1",
      "review-1",
      "request-view",
    );
  });

  it("records only a validated decision and reason", async () => {
    decide.execute.mockResolvedValue({ status: "COMPLETED" });
    app = await createHttpApp(TestAdminReviewModule);
    const response = await app.inject({
      method: "POST",
      url: "/api/v1/admin/manual-reviews/review-1/decisions",
      headers: {
        authorization: "Bearer admin-secret",
        "x-request-id": "request-decide",
      },
      payload: {
        decision: "REQUIRES_DEIDENTIFICATION",
        reasonCode: "MORE_REDACTION_REQUIRED",
        reasonZh: "仍包含可识别细节。",
      },
    });
    expect(response.statusCode).toBe(200);
    expect(decide.execute).toHaveBeenCalledWith({
      adminId: "admin-1",
      reviewRequestId: "review-1",
      decision: "REQUIRES_DEIDENTIFICATION",
      reasonCode: "MORE_REDACTION_REQUIRED",
      reasonZh: "仍包含可识别细节。",
      requestId: "request-decide",
    });
  });

  it("rejects missing administrator authentication and malformed decisions", async () => {
    app = await createHttpApp(TestAdminReviewModule);
    const unauthorized = await app.inject({
      method: "GET",
      url: "/api/v1/admin/manual-reviews",
    });
    const malformed = await app.inject({
      method: "POST",
      url: "/api/v1/admin/manual-reviews/review-1/decisions",
      headers: { authorization: "Bearer admin-secret" },
      payload: { decision: "ALLOW_EVERYTHING" },
    });
    expect(unauthorized.statusCode).toBe(401);
    expect(malformed.statusCode).toBe(400);
    expect(decide.execute).not.toHaveBeenCalled();
  });
});
