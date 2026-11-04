import { sql } from "kysely";
import type { Migration } from "kysely/migration";

export const identityPasswordResetMigration: Migration = {
  async up(database) {
    await database.schema
      .withSchema("identity")
      .createTable("password_reset_requests")
      .addColumn("command_id", "varchar(128)", (column) => column.primaryKey())
      .addColumn("challenge_id", "uuid", (column) => column.notNull().unique())
      .addColumn("account_id", "uuid", (column) =>
        column.references("identity.accounts.account_id").onDelete("cascade"),
      )
      .addColumn("delivery_secret_ciphertext", "bytea")
      .addColumn("delivery_secret_algorithm", "varchar(32)")
      .addColumn("delivery_secret_key_version", "smallint")
      .addColumn("created_at", "timestamptz", (column) =>
        column.notNull().defaultTo(sql`now()`),
      )
      .addCheckConstraint(
        "password_reset_requests_delivery_shape",
        sql`(
          account_id is null
          and delivery_secret_ciphertext is null
          and delivery_secret_algorithm is null
          and delivery_secret_key_version is null
        ) or (
          account_id is not null
          and delivery_secret_ciphertext is not null
          and delivery_secret_algorithm is not null
          and delivery_secret_key_version > 0
        )`,
      )
      .execute();

    await database.schema
      .withSchema("identity")
      .createTable("password_reset_grants")
      .addColumn("grant_id", "uuid", (column) => column.primaryKey())
      .addColumn("challenge_id", "uuid", (column) =>
        column
          .notNull()
          .unique()
          .references("identity.verification_challenges.challenge_id")
          .onDelete("cascade"),
      )
      .addColumn("account_id", "uuid", (column) =>
        column
          .notNull()
          .references("identity.accounts.account_id")
          .onDelete("cascade"),
      )
      .addColumn("reset_token_digest", "bytea", (column) =>
        column.notNull().unique(),
      )
      .addColumn("reset_token_digest_key_version", "smallint", (column) =>
        column.notNull(),
      )
      .addColumn("expires_at", "timestamptz", (column) => column.notNull())
      .addColumn("consumed_at", "timestamptz")
      .addColumn("completion_command_id", "varchar(128)", (column) =>
        column.unique(),
      )
      .addColumn("created_at", "timestamptz", (column) =>
        column.notNull().defaultTo(sql`now()`),
      )
      .addColumn("updated_at", "timestamptz", (column) =>
        column.notNull().defaultTo(sql`now()`),
      )
      .addCheckConstraint(
        "password_reset_grants_digest_key_positive",
        sql`reset_token_digest_key_version > 0`,
      )
      .addCheckConstraint(
        "password_reset_grants_expiry",
        sql`expires_at > created_at`,
      )
      .addCheckConstraint(
        "password_reset_grants_completion_shape",
        sql`(
          consumed_at is null and completion_command_id is null
        ) or (
          consumed_at is not null and completion_command_id is not null
        )`,
      )
      .execute();

    await database.schema
      .withSchema("identity")
      .createIndex("password_reset_grants_active_account")
      .on("password_reset_grants")
      .columns(["account_id", "expires_at"])
      .where(sql.ref("consumed_at"), "is", null)
      .execute();
  },

  async down(database) {
    await database.schema
      .withSchema("identity")
      .dropTable("password_reset_grants")
      .execute();
    await database.schema
      .withSchema("identity")
      .dropTable("password_reset_requests")
      .execute();
  },
};
