import { Module } from "@nestjs/common";
import type { NestFastifyApplication } from "@nestjs/platform-fastify";
import { afterEach, describe, expect, it, vi } from "vitest";

import { createHttpApp } from "../create-app.js";
import {
  AGREEMENT_DOCUMENT_READER,
  AgreementDocumentController,
} from "./agreement-document.controller.js";

const load = vi.fn();

@Module({
  controllers: [AgreementDocumentController],
  providers: [{ provide: AGREEMENT_DOCUMENT_READER, useValue: { load } }],
})
class TestAgreementModule {}

let app: NestFastifyApplication | undefined;
afterEach(async () => {
  await app?.close();
  app = undefined;
  load.mockReset();
});

describe("agreement document HTTP adapter", () => {
  it("returns only the configured versioned authoritative document", async () => {
    load.mockResolvedValue({
      kind: "PRIVACY_POLICY",
      titleZh: "隐私政策",
      version: "2026-09-01",
      sections: ["第一节"],
    });
    app = await createHttpApp(TestAgreementModule);
    const response = await app.inject({
      method: "GET",
      url: "/api/v1/legal/documents/privacy-policy",
    });
    expect(response.statusCode).toBe(200);
    expect(response.json()).toMatchObject({
      kind: "PRIVACY_POLICY",
      version: "2026-09-01",
    });
    expect(load).toHaveBeenCalledWith("PRIVACY_POLICY");
  });

  it("rejects an unknown document kind without calling the source", async () => {
    app = await createHttpApp(TestAgreementModule);
    const response = await app.inject({
      method: "GET",
      url: "/api/v1/legal/documents/unknown",
    });
    expect(response.statusCode).toBe(400);
    expect(load).not.toHaveBeenCalled();
  });
});
