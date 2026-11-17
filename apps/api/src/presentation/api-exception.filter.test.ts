import type { ArgumentsHost } from "@nestjs/common";
import { describe, expect, it, vi } from "vitest";

import { ApiExceptionFilter } from "./api-exception.filter.js";

describe("ApiExceptionFilter", () => {
  it.each([
    ["AI_OPERATION_PROCESSING", 409, true],
    ["IDEMPOTENCY_KEY_REUSED", 409, false],
    ["AI_OPERATION_EXPIRED", 410, false],
    ["AI_OPERATION_FAILED", 409, false],
    ["AI_CHARGE_RECONCILIATION_REQUIRED", 409, false],
  ])(
    "preserves safe delivery status %s instead of a generic server error",
    (code, expectedStatus, retryable) => {
      const status = vi.fn().mockReturnThis();
      const send = vi.fn();
      const host = {
        switchToHttp: () => ({
          getResponse: () => ({ status, send }),
          getRequest: () => ({ headers: {} }),
        }),
      } as unknown as ArgumentsHost;
      new ApiExceptionFilter().catch(
        { code, privateDetail: "never expose" },
        host,
      );
      expect(status).toHaveBeenCalledWith(expectedStatus);
      expect(send).toHaveBeenCalledWith({
        error: expect.objectContaining({ code, retryable, details: {} }),
      });
      expect(JSON.stringify(send.mock.calls)).not.toContain("never expose");
    },
  );
  it("returns the standard non-retryable balance error without leaking internals", () => {
    const status = vi.fn().mockReturnThis();
    const send = vi.fn();
    const host = {
      switchToHttp: () => ({
        getResponse: () => ({ status, send }),
        getRequest: () => ({ headers: { "x-request-id": "request-1" } }),
      }),
    } as unknown as ArgumentsHost;

    new ApiExceptionFilter().catch(
      { code: "AI_BALANCE_INSUFFICIENT", privateDetail: "hidden" },
      host,
    );

    expect(status).toHaveBeenCalledWith(402);
    expect(send).toHaveBeenCalledWith({
      error: {
        code: "AI_BALANCE_INSUFFICIENT",
        message: "余额不足，请先充值。",
        retryable: false,
        requestId: "request-1",
        details: {},
      },
    });
  });

  it.each([
    ["AbortSignal.timeout DOMException", new DOMException("timed out", "TimeoutError")],
    [
      "fetch wrapped undici cause",
      Object.assign(new TypeError("fetch failed"), {
        cause: Object.assign(new Error("connect timeout"), {
          code: "UND_ERR_CONNECT_TIMEOUT",
        }),
      }),
    ],
  ])(
    "maps %s to a retryable gateway timeout instead of a 500",
    (_label, failure) => {
      const status = vi.fn().mockReturnThis();
      const send = vi.fn();
      const host = {
        switchToHttp: () => ({
          getResponse: () => ({ status, send }),
          getRequest: () => ({ headers: { "x-request-id": "request-2" } }),
        }),
      } as unknown as ArgumentsHost;

      new ApiExceptionFilter().catch(failure, host);

      expect(status).toHaveBeenCalledWith(504);
      expect(send).toHaveBeenCalledWith({
        error: {
          code: "TEMPORARILY_UNAVAILABLE",
          message: "服务暂时不可用，请稍后重试。",
          retryable: true,
          requestId: "request-2",
          details: {},
        },
      });
    },
  );
});
