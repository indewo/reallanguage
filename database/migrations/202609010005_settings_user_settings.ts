import { sql } from "kysely";
import type { Migration } from "kysely/migration";

export const settingsUserSettingsMigration: Migration = {
  async up(database) {
    await database.schema.createSchema("settings").ifNotExists().execute();
    await database.schema
      .withSchema("settings")
      .createTable("user_settings")
      .addColumn("user_id", "uuid", (column) =>
        column
          .primaryKey()
          .references("identity.accounts.account_id")
          .onDelete("cascade"),
      )
      .addColumn("formality_level", "smallint", (column) => column.notNull())
      .addColumn("slang_enabled", "boolean", (column) => column.notNull())
      .addColumn("basic_vocabulary", "text", (column) => column.notNull())
      .addColumn("purpose_vocabularies", sql`text[]`, (column) =>
        column.notNull(),
      )
      .addColumn("style_reference", "text", (column) => column.notNull())
      .addColumn("voice_enabled", "boolean", (column) => column.notNull())
      .addColumn("auto_play_enabled", "boolean", (column) => column.notNull())
      .addColumn("speech_speed_level", "smallint", (column) => column.notNull())
      .addColumn("american_english_default", "boolean", (column) =>
        column.notNull().defaultTo(true),
      )
      .addColumn("notification_timezone", "text")
      .addColumn("reminder_time", "time")
      .addColumn("row_version", "bigint", (column) =>
        column.notNull().defaultTo(1),
      )
      .addColumn("created_at", "timestamptz", (column) =>
        column.notNull().defaultTo(sql`now()`),
      )
      .addColumn("updated_at", "timestamptz", (column) =>
        column.notNull().defaultTo(sql`now()`),
      )
      .addCheckConstraint(
        "user_settings_formality_level",
        sql`formality_level between 1 and 5`,
      )
      .addCheckConstraint(
        "user_settings_speech_speed_level",
        sql`speech_speed_level between 1 and 5`,
      )
      .addCheckConstraint(
        "user_settings_basic_vocabulary",
        sql`basic_vocabulary in ('PRIMARY_SCHOOL', 'JUNIOR_HIGH', 'SENIOR_HIGH', 'CET_4', 'CET_6', 'TEM_8')`,
      )
      .addCheckConstraint("user_settings_row_version", sql`row_version > 0`)
      .execute();
  },

  async down(database) {
    await database.schema
      .withSchema("settings")
      .dropTable("user_settings")
      .execute();
    await database.schema.dropSchema("settings").ifExists().execute();
  },
};
