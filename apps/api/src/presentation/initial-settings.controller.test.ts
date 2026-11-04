import { Module } from "@nestjs/common";
import type { NestFastifyApplication } from "@nestjs/platform-fastify";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { createHttpApp } from "../create-app.js";
import {
  AUTHENTICATE_ACCESS_TOKEN,
  COMPLETE_INITIAL_SETTINGS_APPLICATION,
  GET_CURRENT_SETTINGS_APPLICATION,
  InitialSettingsController,
} from "./initial-settings.controller.js";

const authenticate = vi.fn();
const execute = vi.fn();
const getCurrent = vi.fn();

@Module({
  controllers: [InitialSettingsController],
  providers: [
    { provide: AUTHENTICATE_ACCESS_TOKEN, useValue: { authenticate } },
    { provide: COMPLETE_INITIAL_SETTINGS_APPLICATION, useValue: { execute } },
    {
      provide: GET_CURRENT_SETTINGS_APPLICATION,
      useValue: { execute: getCurrent },
    },
  ],
})
class TestSettingsModule {}

let app: NestFastifyApplication | undefined;

beforeEach(() => {
  authenticate.mockReset();
  execute.mockReset();
  getCurrent.mockReset();
});

afterEach(async () => {
  await app?.close();
  app = undefined;
});

describe("initial settings HTTP adapter", () => {
  it("authenticates the bearer and completes the authoritative snapshot", async () => {
    authenticate.mockResolvedValue("30000000-0000-4000-8000-000000000001");
    execute.mockResolvedValue({ status: "COMPLETED", rowVersion: 1 });
    app = await createHttpApp(TestSettingsModule);

    const response = await app.inject({
      method: "PUT",
      url: "/api/v1/settings/current/initial",
      headers: {
        authorization: "Bearer access-token",
        "x-request-id": "request-1",
      },
      payload: validSettings,
    });

    expect(response.statusCode).toBe(200);
    expect(response.json()).toEqual({ status: "COMPLETED", rowVersion: 1 });
    expect(authenticate).toHaveBeenCalledWith("access-token");
    expect(execute).toHaveBeenCalledWith({
      accountId: "30000000-0000-4000-8000-000000000001",
      settings: validSettings,
    });
  });

  it("rejects missing authentication and invalid settings without writing", async () => {
    app = await createHttpApp(TestSettingsModule);
    const unauthorized = await app.inject({
      method: "PUT",
      url: "/api/v1/settings/current/initial",
      payload: validSettings,
    });
    expect(unauthorized.statusCode).toBe(401);

    authenticate.mockResolvedValue("30000000-0000-4000-8000-000000000001");
    const invalid = await app.inject({
      method: "PUT",
      url: "/api/v1/settings/current/initial",
      headers: { authorization: "Bearer access-token" },
      payload: { ...validSettings, speechSpeedLevel: 6 },
    });
    expect(invalid.statusCode).toBe(400);
    expect(execute).not.toHaveBeenCalled();
  });

  it("returns the authenticated current settings snapshot", async () => {
    authenticate.mockResolvedValue("30000000-0000-4000-8000-000000000001");
    getCurrent.mockResolvedValue(validSettings);
    app = await createHttpApp(TestSettingsModule);

    const response = await app.inject({
      method: "GET",
      url: "/api/v1/settings/current",
      headers: { authorization: "Bearer access-token" },
    });

    expect(response.statusCode).toBe(200);
    expect(response.json()).toEqual(validSettings);
    expect(getCurrent).toHaveBeenCalledWith({
      accountId: "30000000-0000-4000-8000-000000000001",
    });
  });
});

const validSettings = {
  formalityLevel: 3,
  slangEnabled: true,
  basicVocabulary: "CET_6",
  purposeVocabularies: ["IELTS_TOEFL", "BUSINESS_ENGLISH"],
  styleReference: "Clear and direct",
  voiceEnabled: true,
  autoPlayEnabled: false,
  speechSpeedLevel: 4,
};
