import { sql } from "kysely";
import type { Migration } from "kysely/migration";

// The original constraint only whitelisted the six vocabulary levels that
// existed at the time. The wire contract, the Android client and the public
// types all ship POSTGRADUATE / TOEFL / IELTS / GRE as well, so any account
// selecting one of them failed the initial settings save with a check
// constraint violation.
const vocabularyLevels = [
  "PRIMARY_SCHOOL",
  "JUNIOR_HIGH",
  "SENIOR_HIGH",
  "CET_4",
  "CET_6",
  "POSTGRADUATE",
  "TOEFL",
  "IELTS",
  "GRE",
  "TEM_8",
] as const;

const legacyVocabularyLevels = [
  "PRIMARY_SCHOOL",
  "JUNIOR_HIGH",
  "SENIOR_HIGH",
  "CET_4",
  "CET_6",
  "TEM_8",
] as const;

const vocabularyCheck = (levels: readonly string[]): ReturnType<typeof sql> =>
  sql`basic_vocabulary in (${sql.join(levels.map((level) => sql.lit(level)))})`;

const replaceVocabularyConstraint = async (
  database: Parameters<Migration["up"]>[0],
  levels: readonly string[],
): Promise<void> => {
  await database.schema
    .withSchema("settings")
    .alterTable("user_settings")
    .dropConstraint("user_settings_basic_vocabulary")
    .execute();
  await database.schema
    .withSchema("settings")
    .alterTable("user_settings")
    .addCheckConstraint(
      "user_settings_basic_vocabulary",
      vocabularyCheck(levels),
    )
    .execute();
};

export const settingsBasicVocabularyOptionsMigration: Migration = {
  async up(database) {
    await replaceVocabularyConstraint(database, vocabularyLevels);
  },

  async down(database) {
    await replaceVocabularyConstraint(database, legacyVocabularyLevels);
  },
};
