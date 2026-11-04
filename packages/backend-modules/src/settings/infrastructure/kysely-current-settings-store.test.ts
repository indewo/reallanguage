import { PGlite } from "@electric-sql/pglite";
import { migrateToLatest } from "@reallanguage/database";
import { Kysely, PGliteDialect, sql } from "kysely";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import {
  KyselyInitialSettingsWriter,
  type SettingsDatabase,
} from "./kysely-initial-settings-writer.js";
import { KyselyCurrentSettingsStore } from "./kysely-current-settings-store.js";

const owner = "30000000-0000-4000-8000-000000000011";
const other = "30000000-0000-4000-8000-000000000012";
const absent = "30000000-0000-4000-8000-000000000013";
const initial = {
  formalityLevel: 3,
  slangEnabled: false,
  basicVocabulary: "CET_6" as const,
  purposeVocabularies: ["GRE", "BUSINESS_ENGLISH"] as const,
  styleReference: "Clear and direct",
  voiceEnabled: true,
  autoPlayEnabled: false,
  speechSpeedLevel: 3,
};
let database: Kysely<SettingsDatabase>;
let store: KyselyCurrentSettingsStore;
beforeEach(async () => {
  database = new Kysely({
    dialect: new PGliteDialect({ pglite: new PGlite() }),
  });
  await migrateToLatest(database);
  for (const id of [owner, other]) {
    await sql`insert into identity.accounts (
      account_id,birth_year_month_ciphertext,birth_year_month_algorithm,
      birth_year_month_key_version,age_eligible,age_eligibility_confirmed_at,email_verified_at
    ) values (${id},decode('01','hex'),'AES-256-GCM/v1',1,true,now(),now())`.execute(
      database,
    );
    await new KyselyInitialSettingsWriter(database).complete(id, initial);
  }
  store = new KyselyCurrentSettingsStore(database);
});
afterEach(async () => database.destroy());
describe("versioned subsequent settings persistence", () => {
  it("updates every explicit setting and reads the same snapshot from a new repository", async () => {
    const settings = {
      ...initial,
      formalityLevel: 5,
      slangEnabled: true,
      basicVocabulary: "CET_4" as const,
      purposeVocabularies: ["IELTS_TOEFL"] as const,
      styleReference: "Brief examples",
      voiceEnabled: false,
      autoPlayEnabled: false,
      speechSpeedLevel: 1,
    };
    expect(await store.readVersioned(owner)).toEqual({
      settings: initial,
      rowVersion: 1,
    });
    expect(
      await store.update({ accountId: owner, expectedRowVersion: 1, settings }),
    ).toEqual({ status: "SAVED", rowVersion: 2 });
    expect(
      await new KyselyCurrentSettingsStore(database).readVersioned(owner),
    ).toEqual({ settings, rowVersion: 2 });
    expect(await new KyselyInitialSettingsWriter(database).read(owner)).toEqual(
      settings,
    );
  });
  it("a lost-response retry confirms identical stored values without rewriting or increasing the version", async () => {
    const command = {
      accountId: owner,
      expectedRowVersion: 1,
      settings: { ...initial, speechSpeedLevel: 4 },
    };
    expect(await store.update(command)).toEqual({
      status: "SAVED",
      rowVersion: 2,
    });
    const before = await database
      .selectFrom("settings.user_settings")
      .selectAll()
      .where("user_id", "=", owner)
      .executeTakeFirstOrThrow();
    expect(await store.update(command)).toEqual({
      status: "SAVED",
      rowVersion: 2,
    });
    const after = await database
      .selectFrom("settings.user_settings")
      .selectAll()
      .where("user_id", "=", owner)
      .executeTakeFirstOrThrow();
    expect(after).toEqual(before);
  });
  it("different concurrent edits cannot both overwrite the same version", async () => {
    const results = await Promise.all(
      [4, 5].map((speechSpeedLevel) =>
        store.update({
          accountId: owner,
          expectedRowVersion: 1,
          settings: { ...initial, speechSpeedLevel },
        }),
      ),
    );
    expect(results.map((result) => result.status).sort()).toEqual([
      "CONFLICT",
      "SAVED",
    ]);
    expect(results.find((result) => result.status === "CONFLICT")).toEqual({
      status: "CONFLICT",
      rowVersion: 2,
    });
    expect((await store.readVersioned(owner))?.rowVersion).toBe(2);
  });
  it("does not cross accounts, create missing initial settings, or let initial replay overwrite a subsequent save", async () => {
    expect(
      await store.update({
        accountId: owner,
        expectedRowVersion: 1,
        settings: { ...initial, speechSpeedLevel: 4 },
      }),
    ).toEqual({ status: "SAVED", rowVersion: 2 });
    expect(await store.readVersioned(other)).toEqual({
      settings: initial,
      rowVersion: 1,
    });
    expect(
      await store.update({
        accountId: absent,
        expectedRowVersion: 1,
        settings: initial,
      }),
    ).toEqual({ status: "NOT_FOUND" });
    expect(await store.readVersioned(absent)).toBeNull();
    expect(
      await new KyselyInitialSettingsWriter(database).complete(owner, initial),
    ).toBe(2);
    expect((await store.readVersioned(owner))?.settings.speechSpeedLevel).toBe(
      4,
    );
  });
  it("does not change a row for a semantically unchanged set and rejects a future version", async () => {
    expect(
      await store.update({
        accountId: owner,
        expectedRowVersion: 1,
        settings: {
          ...initial,
          purposeVocabularies: ["BUSINESS_ENGLISH", "GRE"],
        },
      }),
    ).toEqual({ status: "SAVED", rowVersion: 1 });
    expect(
      await store.update({
        accountId: owner,
        expectedRowVersion: 9,
        settings: initial,
      }),
    ).toEqual({ status: "CONFLICT", rowVersion: 1 });
  });
});
