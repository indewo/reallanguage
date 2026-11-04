import { readFile } from "node:fs/promises";
import { parse } from "yaml";
import { describe, expect, it } from "vitest";
import {
  updateCurrentSettingsRequestSchema,
  updateCurrentSettingsResultSchema,
  versionedSettingsSchema,
} from "./index.js";
const settings = {
  formalityLevel: 3,
  slangEnabled: false,
  basicVocabulary: "CET_6",
  purposeVocabularies: ["GRE"],
  styleReference: "Clear",
  voiceEnabled: true,
  autoPlayEnabled: false,
  speechSpeedLevel: 3,
};
describe("versioned settings contract", () => {
  it("requires an exact version and consistent voice state without changing the initial contract", () => {
    expect(
      updateCurrentSettingsRequestSchema.parse({
        settings,
        expectedRowVersion: 1,
      }),
    ).toEqual({ settings, expectedRowVersion: 1 });
    for (const expectedRowVersion of [
      undefined,
      0,
      -1,
      1.5,
      "1",
      Number.MAX_SAFE_INTEGER + 1,
    ]) {
      expect(
        updateCurrentSettingsRequestSchema.safeParse({
          settings,
          expectedRowVersion,
        }).success,
      ).toBe(false);
    }
    expect(
      updateCurrentSettingsRequestSchema.safeParse({
        settings: { ...settings, voiceEnabled: false, autoPlayEnabled: true },
        expectedRowVersion: 1,
      }).success,
    ).toBe(false);
    expect(
      updateCurrentSettingsRequestSchema.safeParse({
        settings,
        expectedRowVersion: 1,
        accountId: "other",
      }).success,
    ).toBe(false);
    expect(
      updateCurrentSettingsResultSchema.safeParse({
        status: "COMPLETED",
        rowVersion: 1,
      }).success,
    ).toBe(false);
    expect(versionedSettingsSchema.parse({ settings, rowVersion: 2 })).toEqual({
      settings,
      rowVersion: 2,
    });
  });
  it("publishes the separate versioned read and update endpoints and explicit conflict response", async () => {
    const document = parse(
      await readFile(
        new URL("../openapi/real-language-v1.yaml", import.meta.url),
        "utf8",
      ),
    );
    expect(document.paths["/settings/current/revision"]?.get?.operationId).toBe(
      "readVersionedSettings",
    );
    expect(document.paths["/settings/current/revision"]?.put?.operationId).toBe(
      "updateCurrentSettings",
    );
    expect(
      document.paths["/settings/current/revision"]?.put?.responses["409"],
    ).toBeDefined();
    expect(
      document.components.schemas.UpdateCurrentSettingsRequest.required,
    ).toEqual(["settings", "expectedRowVersion"]);
  });
});
