import { Module } from "@nestjs/common";
import type { NestFastifyApplication } from "@nestjs/platform-fastify";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createHttpApp } from "../create-app.js";
import { AUTHENTICATE_ACCESS_TOKEN } from "./initial-settings.controller.js";
import {
  LearningExplanationRecoveryController,
  RECOVER_LEARNING_EXPLANATION_APPLICATION,
} from "./learning-explanation-recovery.controller.js";
const authenticate = vi.fn();
const execute = vi.fn();
@Module({
  controllers: [LearningExplanationRecoveryController],
  providers: [
    { provide: AUTHENTICATE_ACCESS_TOKEN, useValue: { authenticate } },
    {
      provide: RECOVER_LEARNING_EXPLANATION_APPLICATION,
      useValue: { execute },
    },
  ],
})
class TestModule {}
let app: NestFastifyApplication;
beforeEach(async () => {
  authenticate.mockReset().mockResolvedValue("owned-account");
  execute.mockReset().mockResolvedValue({ status: "NO_DELIVERY" });
  app = await createHttpApp(TestModule);
});
afterEach(async () => app.close());
describe("authenticated read-only explanation recovery HTTP", () => {
  it("uses only the authenticated account and returns a stored result without provider metadata", async () => {
    const result = {
      status: "COMPLETED",
      commandId: "paid-command",
      provenance: "EXISTING_DELIVERY",
      result: { kind: "SENTENCE", explanationZh: "既有解释。" },
    };
    execute.mockResolvedValue(result);
    const response = await app.inject({
      method: "GET",
      url: "/api/v1/learning/explanations/deliveries/paid-command?kind=SENTENCE",
      headers: { authorization: "Bearer test-token" },
    });
    expect(response.statusCode).toBe(200);
    expect(response.json()).toEqual(result);
    expect(response.headers["cache-control"]).toBe("private, no-store");
    expect(execute).toHaveBeenCalledWith({
      accountId: "owned-account",
      commandId: "paid-command",
      kind: "SENTENCE",
    });
  });
  it.each([
    "PROCESSING",
    "NO_DELIVERY_WITH_ATTEMPT",
    "EXPIRED",
    "PENDING_SETTLEMENT",
  ])("delivers %s as an explicit non-success state", async (status) => {
    execute.mockResolvedValue({ status });
    const response = await app.inject({
      method: "GET",
      url: "/api/v1/learning/explanations/deliveries/paid-command?kind=EXPRESSION",
      headers: { authorization: "Bearer token" },
    });
    expect(response.statusCode).toBe(200);
    expect(response.json()).toEqual({ status });
  });
  it.each([undefined, "Bearer revoked"])(
    "requires an active session %s",
    async (authorization) => {
      authenticate.mockResolvedValue(null);
      const response = await app.inject({
        method: "GET",
        url: "/api/v1/learning/explanations/deliveries/paid-command?kind=SENTENCE",
        headers: authorization ? { authorization } : {},
      });
      expect(response.statusCode).toBe(401);
      expect(execute).not.toHaveBeenCalled();
    },
  );
  it.each([
    "kind=WORD",
    "kind=EXPRESSION&accountId=other",
    "kind=SENTENCE&lockedTargets=[]",
  ])("rejects unsupported input %s", async (query) => {
    const response = await app.inject({
      method: "GET",
      url: `/api/v1/learning/explanations/deliveries/paid-command?${query}`,
      headers: { authorization: "Bearer token" },
    });
    expect(response.statusCode).toBe(400);
    expect(execute).not.toHaveBeenCalled();
  });
  it("does not expose an invalid successful application envelope", async () => {
    execute.mockResolvedValue({
      status: "COMPLETED",
      commandId: "paid-command",
      provenance: "EXISTING_DELIVERY",
      result: { kind: "SENTENCE", explanationZh: "valid" },
      providerHeaders: { authorization: "must-not-leak" },
    });
    const response = await app.inject({
      method: "GET",
      url: "/api/v1/learning/explanations/deliveries/paid-command?kind=SENTENCE",
      headers: { authorization: "Bearer token" },
    });
    expect(response.statusCode).toBe(500);
    expect(response.body).not.toContain("must-not-leak");
  });
});
