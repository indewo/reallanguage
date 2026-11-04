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
});
