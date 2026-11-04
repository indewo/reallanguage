import { PGlite } from "@electric-sql/pglite";
import { migrateToLatest } from "@reallanguage/database";
import { Kysely, PGliteDialect, sql } from "kysely";
import { afterEach, beforeEach, describe, expect, it } from "vitest";

import type { SettingsDatabase } from "./kysely-initial-settings-writer.js";
import { KyselyInitialSettingsWriter } from "./kysely-initial-settings-writer.js";

const accountId = "30000000-0000-4000-8000-000000000001";
let database: Kysely<SettingsDatabase>;

beforeEach(async () => {
  database = new Kysely({
    dialect: new PGliteDialect({ pglite: new PGlite() }),
  });
  await migrateToLatest(database);
  await sql`
    insert into identity.accounts (
      account_id, birth_year_month_ciphertext, birth_year_month_algorithm,
      birth_year_month_key_version, age_eligible, age_eligibility_confirmed_at,
      email_verified_at
    ) values (
      ${accountId}, decode('01', 'hex'), 'AES-256-GCM/v1', 1, true, now(), now()
    )
  `.execute(database);
});

afterEach(async () => database.destroy());

describe("KyselyInitialSettingsWriter", () => {
  it("stores the complete server-authoritative snapshot and makes retries idempotent", async () => {
    const repository = new KyselyInitialSettingsWriter(database);
    const settings = {
      formalityLevel: 3,
      slangEnabled: true,
      basicVocabulary: "CET_6" as const,
      purposeVocabularies: ["GRE", "BUSINESS_ENGLISH"] as const,
      styleReference: "Clear and direct",
      voiceEnabled: true,
      autoPlayEnabled: false,
      speechSpeedLevel: 4,
    };

    await expect(repository.hasCompleted(accountId)).resolves.toBe(false);
    await expect(repository.complete(accountId, settings)).resolves.toBe(1);
    await expect(
      repository.complete(accountId, { ...settings, formalityLevel: 5 }),
    ).resolves.toBe(1);
    await expect(repository.hasCompleted(accountId)).resolves.toBe(true);
    await expect(repository.read(accountId)).resolves.toEqual(settings);

    const stored = await sql<{
      formality_level: number;
      purpose_vocabularies: string[];
    }>`
      select formality_level, purpose_vocabularies
      from settings.user_settings where user_id = ${accountId}
    `.execute(database);
    expect(stored.rows[0]).toMatchObject({ formality_level: 3 });
    expect(stored.rows[0]?.purpose_vocabularies).toEqual([
      "GRE",
      "BUSINESS_ENGLISH",
    ]);
  });
});
