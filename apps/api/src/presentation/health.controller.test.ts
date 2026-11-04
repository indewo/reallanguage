import { afterEach, describe, expect, it } from "vitest";
import type { NestFastifyApplication } from "@nestjs/platform-fastify";
import { healthResponseSchema } from "@reallanguage/contracts";
import { AppModule } from "../app.module.js";
import { createHttpApp } from "../create-app.js";

let app: NestFastifyApplication | undefined;

afterEach(async () => {
  await app?.close();
  app = undefined;
});

describe("GET /api/v1/health", () => {
  it("returns readiness with the propagated request id", async () => {
    app = await createHttpApp(AppModule);
    const response = await app.inject({
      method: "GET",
      url: "/api/v1/health",
      headers: { "x-request-id": "req-1" },
    });
    expect(response.statusCode).toBe(200);
    expect(response.headers["x-request-id"]).toBe("req-1");
    expect(healthResponseSchema.parse(response.json())).toEqual({
      status: "ok",
      service: "api",
      requestId: "req-1",
    });
  });
});
