import { describe, expect, it, vi } from "vitest";

import { AdminApi, AdminApiError } from "./admin-api.js";

describe("AdminApi", () => {
  it("keeps the browser fetch invocation bound through a wrapper", async () => {
    const original = globalThis.fetch;
    const fetchMock = vi.fn().mockResolvedValue(
      response({
        configured: true,
        enrollmentPending: false,
        email: "indewo@outlook.com",
      }),
    );
    globalThis.fetch = fetchMock;
    try {
      await expect(new AdminApi().status()).resolves.toMatchObject({
        configured: true,
      });
      expect(fetchMock).toHaveBeenCalledOnce();
    } finally {
      globalThis.fetch = original;
    }
  });

  it("uses real relative API routes and sends the bearer only in authorization", async () => {
    const fetcher = vi
      .fn()
      .mockResolvedValueOnce(
        response({
          configured: false,
          enrollmentPending: false,
          email: "indewo@outlook.com",
        }),
      )
      .mockResolvedValueOnce(response({ status: "AUTHENTICATED" }));
    const api = new AdminApi(fetcher);

    await expect(api.status()).resolves.toEqual({
      configured: false,
      enrollmentPending: false,
      email: "indewo@outlook.com",
    });
    await expect(api.current("secret-bearer")).resolves.toBe(true);
    expect(fetcher).toHaveBeenNthCalledWith(
      1,
      "/api/v1/admin/bootstrap/status",
      expect.objectContaining({ credentials: "omit" }),
    );
    expect(fetcher).toHaveBeenNthCalledWith(
      2,
      "/api/v1/admin/sessions/current",
      expect.objectContaining({
        headers: { authorization: "Bearer secret-bearer" },
      }),
    );
  });

  it("recovers the same pending MFA enrollment through the local route", async () => {
    const fetcher = vi.fn().mockResolvedValue(
      response({
        status: "CREATED",
        email: "indewo@outlook.com",
        totpSecret: "BASE32SECRET",
        provisioningUri: "otpauth://totp/example",
      }),
    );

    await expect(new AdminApi(fetcher).resumeEnrollment()).resolves.toEqual({
      status: "CREATED",
      email: "indewo@outlook.com",
      totpSecret: "BASE32SECRET",
      provisioningUri: "otpauth://totp/example",
    });
    expect(fetcher).toHaveBeenCalledWith(
      "/api/v1/admin/bootstrap/enrollment",
      expect.objectContaining({ method: "GET", cache: "no-store" }),
    );
  });

  it("maps setup and MFA login without retaining plaintext inputs", async () => {
    const fetcher = vi
      .fn()
      .mockResolvedValueOnce(
        response({
          status: "CREATED",
          email: "indewo@outlook.com",
          totpSecret: "BASE32SECRET",
          provisioningUri: "otpauth://totp/example",
        }),
      )
      .mockResolvedValueOnce(
        response({
          status: "AUTHENTICATED",
          accessToken: "secret-bearer",
          expiresAt: "2026-09-04T12:15:00.000Z",
        }),
      );
    const api = new AdminApi(fetcher);

    await expect(api.setup("strong passphrase")).resolves.toMatchObject({
      status: "CREATED",
      totpSecret: "BASE32SECRET",
    });
    await expect(
      api.login("indewo@outlook.com", "strong passphrase", "123456"),
    ).resolves.toMatchObject({
      status: "AUTHENTICATED",
      accessToken: "secret-bearer",
    });
  });

  it("turns non-success HTTP responses into safe user-facing failures", async () => {
    const api = new AdminApi(
      vi.fn().mockResolvedValue(
        response(
          {
            error: {
              code: "AUTHENTICATION_REQUIRED",
              message: "请重新登录。",
            },
          },
          401,
        ),
      ),
    );

    await expect(api.login("a@b.cn", "bad", "000000")).rejects.toEqual(
      new AdminApiError(401, "请重新登录。"),
    );
  });

  it("loads review metadata separately from audited details and submits a decision", async () => {
    const fetcher = vi
      .fn()
      .mockResolvedValueOnce(
        response({
          items: [
            {
              reviewRequestId: "review-1",
              sourceType: "DIARY",
              reasonCode: "PERSONAL_DATA",
              userReasonZh: "包含个人信息",
              createdAt: "2026-09-04T12:00:00.000Z",
            },
          ],
        }),
      )
      .mockResolvedValueOnce(
        response({
          reviewRequestId: "review-1",
          sourceType: "DIARY",
          contentShape: "ARTICLE",
          reasonCode: "PERSONAL_DATA",
          userReasonZh: "包含个人信息",
          createdAt: "2026-09-04T12:00:00.000Z",
          text: "必要内容",
        }),
      )
      .mockResolvedValueOnce(response({ status: "COMPLETED" }));
    const api = new AdminApi(fetcher);

    await expect(api.listReviews("secret-bearer")).resolves.toHaveLength(1);
    await expect(
      api.getReview("secret-bearer", "review-1"),
    ).resolves.toMatchObject({ text: "必要内容" });
    await expect(
      api.decideReview("secret-bearer", "review-1", {
        decision: "APPROVE",
        reasonCode: "CONTEXT_ACCEPTABLE",
        reasonZh: "结合上下文可继续处理。",
      }),
    ).resolves.toEqual({ status: "COMPLETED" });
    for (const call of fetcher.mock.calls) {
      expect(call[1]).toMatchObject({
        headers: expect.objectContaining({
          authorization: "Bearer secret-bearer",
        }),
      });
    }
  });
});

const response = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json" },
  });
