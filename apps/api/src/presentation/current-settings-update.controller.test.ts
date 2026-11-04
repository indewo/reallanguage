import { PGlite } from "@electric-sql/pglite";
import { Module } from "@nestjs/common";
import type { NestFastifyApplication } from "@nestjs/platform-fastify";
import { Kysely, PGliteDialect, sql } from "kysely";
import { migrateToLatest } from "@reallanguage/database";
import {
  CompleteInitialSettings,
  GetCurrentSettings,
  KyselyInitialSettingsWriter,
  KyselyCurrentSettingsStore,
  ReadVersionedSettings,
  UpdateCurrentSettings,
  type SettingsDatabase,
} from "@reallanguage/backend-modules/settings";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { createHttpApp } from "../create-app.js";
import {
  AUTHENTICATE_ACCESS_TOKEN,
  COMPLETE_INITIAL_SETTINGS_APPLICATION,
  GET_CURRENT_SETTINGS_APPLICATION,
  InitialSettingsController,
} from "./initial-settings.controller.js";
import {
  CurrentSettingsUpdateController,
  READ_VERSIONED_SETTINGS_APPLICATION,
  UPDATE_CURRENT_SETTINGS_APPLICATION,
} from "./current-settings-update.controller.js";
const owner = "30000000-0000-4000-8000-000000000021";
const other = "30000000-0000-4000-8000-000000000022";
const initial = {
  formalityLevel: 3,
  slangEnabled: false,
  basicVocabulary: "CET_6" as const,
  purposeVocabularies: ["GRE"] as const,
  styleReference: "Clear",
  voiceEnabled: true,
  autoPlayEnabled: false,
  speechSpeedLevel: 3,
};
let database: Kysely<SettingsDatabase>;
let app: NestFastifyApplication;
beforeEach(async () => {
  database = new Kysely({
    dialect: new PGliteDialect({ pglite: new PGlite() }),
  });
  await migrateToLatest(database);
  const initialWriter = new KyselyInitialSettingsWriter(database);
  for (const id of [owner, other]) {
    await sql`insert into identity.accounts (
      account_id,birth_year_month_ciphertext,birth_year_month_algorithm,
      birth_year_month_key_version,age_eligible,age_eligibility_confirmed_at,email_verified_at
    ) values (${id},decode('01','hex'),'AES-256-GCM/v1',1,true,now(),now())`.execute(
      database,
    );
    await initialWriter.complete(id, initial);
  }
  const store = new KyselyCurrentSettingsStore(database);
  @Module({
    controllers: [InitialSettingsController, CurrentSettingsUpdateController],
    providers: [
      {
        provide: AUTHENTICATE_ACCESS_TOKEN,
        useValue: {
          authenticate: async (token: string) =>
            token === "fixture-owner"
              ? owner
              : token === "fixture-other"
                ? other
                : null,
        },
      },
      {
        provide: COMPLETE_INITIAL_SETTINGS_APPLICATION,
        useValue: new CompleteInitialSettings(initialWriter),
      },
      {
        provide: GET_CURRENT_SETTINGS_APPLICATION,
        useValue: new GetCurrentSettings(initialWriter),
      },
      {
        provide: READ_VERSIONED_SETTINGS_APPLICATION,
        useValue: new ReadVersionedSettings(store),
      },
      {
        provide: UPDATE_CURRENT_SETTINGS_APPLICATION,
        useValue: new UpdateCurrentSettings(store),
      },
    ],
  })
  class IsolatedSettingsModule {}
  app = await createHttpApp(IsolatedSettingsModule);
});
afterEach(async () => {
  await app?.close();
  await database.destroy();
});
const headers = { authorization: "Bearer fixture-owner" };
const url = "/api/v1/settings/current/revision";
describe("subsequent settings real HTTP and isolated database", () => {
  it("PUT then GET returns changed values and old initial replay cannot undo them", async () => {
    const settings = { ...initial, formalityLevel: 5, speechSpeedLevel: 4 };
    const response = await app.inject({
      method: "PUT",
      url,
      headers,
      payload: { settings, expectedRowVersion: 1 },
    });
    expect(response.statusCode).toBe(200);
    expect(response.json()).toEqual({ status: "SAVED", rowVersion: 2 });
    const read = await app.inject({ method: "GET", url, headers });
    expect(read.json()).toEqual({ settings, rowVersion: 2 });
    await app.inject({
      method: "PUT",
      url: "/api/v1/settings/current/initial",
      headers,
      payload: initial,
    });
    const legacyRead = await app.inject({
      method: "GET",
      url: "/api/v1/settings/current",
      headers,
    });
    expect(legacyRead.json()).toEqual(settings);
  });
  it("lost-response duplicate is read-confirmed without another version increment", async () => {
    const payload = {
      settings: { ...initial, speechSpeedLevel: 4 },
      expectedRowVersion: 1,
    };
    const responses = await Promise.all(
      [1, 2].map(() => app.inject({ method: "PUT", url, headers, payload })),
    );
    expect(responses.map((response) => response.statusCode)).toEqual([
      200, 200,
    ]);
    expect(responses.map((response) => response.json())).toEqual([
      { status: "SAVED", rowVersion: 2 },
      { status: "SAVED", rowVersion: 2 },
    ]);
  });
  it("conflicting concurrent edits return stable 409 with latest version instead of silent overwrite", async () => {
    const responses = await Promise.all(
      [4, 5].map((speechSpeedLevel) =>
        app.inject({
          method: "PUT",
          url,
          headers,
          payload: {
            settings: { ...initial, speechSpeedLevel },
            expectedRowVersion: 1,
          },
        }),
      ),
    );
    expect(responses.map((response) => response.statusCode).sort()).toEqual([
      200, 409,
    ]);
    expect(
      responses.find((response) => response.statusCode === 409)?.json().error,
    ).toMatchObject({
      code: "SETTINGS_VERSION_CONFLICT",
      retryable: false,
      details: { rowVersion: 2 },
    });
  });
  it("rejects unauthenticated and malformed commands and isolates the authenticated account", async () => {
    const payload = {
      settings: { ...initial, speechSpeedLevel: 4 },
      expectedRowVersion: 1,
    };
    expect((await app.inject({ method: "PUT", url, payload })).statusCode).toBe(
      401,
    );
    expect(
      (
        await app.inject({
          method: "PUT",
          url,
          headers: { authorization: "Bearer invalid" },
          payload,
        })
      ).statusCode,
    ).toBe(401);
    for (const invalid of [
      { ...payload, expectedRowVersion: 0 },
      { ...payload, accountId: other },
      {
        ...payload,
        settings: { ...initial, voiceEnabled: false, autoPlayEnabled: true },
      },
    ]) {
      expect(
        (await app.inject({ method: "PUT", url, headers, payload: invalid }))
          .statusCode,
      ).toBe(400);
    }
    const unchanged = await app.inject({ method: "GET", url, headers });
    expect(unchanged.json()).toEqual({ settings: initial, rowVersion: 1 });
    expect(
      (await app.inject({ method: "PUT", url, headers, payload })).statusCode,
    ).toBe(200);
    const otherRead = await app.inject({
      method: "GET",
      url,
      headers: { authorization: "Bearer fixture-other" },
    });
    expect(otherRead.json()).toEqual({ settings: initial, rowVersion: 1 });
  });
});
