import { sql } from "kysely";
import type { Migration } from "kysely/migration";

export const identityUserSessionsMigration: Migration = {
  async up(database) {
    await database.schema
      .withSchema("identity")
      .createTable("user_sessions")
      .addColumn("session_id", "uuid", (column) => column.primaryKey())
      .addColumn("account_id", "uuid", (column) =>
        column
          .notNull()
          .references("identity.accounts.account_id")
          .onDelete("cascade"),
      )
      .addColumn("refresh_token_digest", "bytea", (column) =>
        column.notNull().unique(),
      )
      .addColumn("refresh_token_digest_key_version", "smallint", (column) =>
        column.notNull(),
      )
      .addColumn("device_id_digest", "bytea", (column) => column.notNull())
      .addColumn("device_id_digest_key_version", "smallint", (column) =>
        column.notNull(),
      )
      .addColumn("expires_at", "timestamptz", (column) => column.notNull())
      .addColumn("last_used_at", "timestamptz", (column) => column.notNull())
      .addColumn("revoked_at", "timestamptz")
      .addColumn("row_version", "bigint", (column) =>
        column.notNull().defaultTo(0),
      )
      .addColumn("created_at", "timestamptz", (column) =>
        column.notNull().defaultTo(sql`now()`),
      )
      .addColumn("updated_at", "timestamptz", (column) =>
        column.notNull().defaultTo(sql`now()`),
      )
      .addCheckConstraint(
        "user_sessions_refresh_digest_key_version_positive",
        sql`refresh_token_digest_key_version > 0`,
      )
      .addCheckConstraint(
        "user_sessions_device_digest_key_version_positive",
        sql`device_id_digest_key_version > 0`,
      )
      .addCheckConstraint("user_sessions_expiry", sql`expires_at > created_at`)
      .addCheckConstraint(
        "user_sessions_last_used",
        sql`last_used_at >= created_at and last_used_at <= updated_at`,
      )
      .addCheckConstraint(
        "user_sessions_revocation",
        sql`revoked_at is null or revoked_at >= created_at`,
      )
      .addCheckConstraint(
        "user_sessions_row_version_nonnegative",
        sql`row_version >= 0`,
      )
      .execute();

    await database.schema
      .withSchema("identity")
      .createIndex("user_sessions_active_account")
      .on("user_sessions")
      .columns(["account_id", "expires_at"])
      .where(sql.ref("revoked_at"), "is", null)
      .execute();
  },

  async down(database) {
    await database.schema
      .withSchema("identity")
      .dropTable("user_sessions")
      .execute();
  },
};
