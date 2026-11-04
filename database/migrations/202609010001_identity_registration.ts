import { sql } from "kysely";
import type { Migration } from "kysely/migration";

export const identityRegistrationMigration: Migration = {
  async up(database) {
    await database.schema.createSchema("identity").ifNotExists().execute();

    await database.schema
      .withSchema("identity")
      .createTable("accounts")
      .addColumn("account_id", "uuid", (column) => column.primaryKey())
      .addColumn("birth_year_month_ciphertext", "bytea", (column) =>
        column.notNull(),
      )
      .addColumn("birth_year_month_algorithm", "varchar(32)", (column) =>
        column.notNull(),
      )
      .addColumn("birth_year_month_key_version", "smallint", (column) =>
        column.notNull(),
      )
      .addColumn("age_eligible", "boolean", (column) => column.notNull())
      .addColumn("age_eligibility_confirmed_at", "timestamptz", (column) =>
        column.notNull(),
      )
      .addColumn("email_verified_at", "timestamptz", (column) =>
        column.notNull(),
      )
      .addColumn("row_version", "bigint", (column) =>
        column.notNull().defaultTo(0),
      )
      .addColumn("created_at", "timestamptz", (column) =>
        column.notNull().defaultTo(sql`now()`),
      )
      .addColumn("updated_at", "timestamptz", (column) =>
        column.notNull().defaultTo(sql`now()`),
      )
      .addCheckConstraint("accounts_age_eligible", sql`age_eligible = true`)
      .addCheckConstraint(
        "accounts_birth_key_version_positive",
        sql`birth_year_month_key_version > 0`,
      )
      .addCheckConstraint(
        "accounts_row_version_nonnegative",
        sql`row_version >= 0`,
      )
      .execute();

    await database.schema
      .withSchema("identity")
      .createTable("auth_identities")
      .addColumn("account_id", "uuid", (column) =>
        column
          .primaryKey()
          .references("identity.accounts.account_id")
          .onDelete("cascade"),
      )
      .addColumn("normalized_email_index", "bytea", (column) =>
        column.notNull().unique(),
      )
      .addColumn("normalized_email_index_key_version", "smallint", (column) =>
        column.notNull(),
      )
      .addColumn("email_ciphertext", "bytea", (column) => column.notNull())
      .addColumn("email_algorithm", "varchar(32)", (column) => column.notNull())
      .addColumn("email_key_version", "smallint", (column) => column.notNull())
      .addColumn("password_hash", "text", (column) => column.notNull())
      .addColumn("password_hash_version", "smallint", (column) =>
        column.notNull(),
      )
      .addColumn("verified_at", "timestamptz", (column) => column.notNull())
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
        "auth_identities_email_index_key_version_positive",
        sql`normalized_email_index_key_version > 0`,
      )
      .addCheckConstraint(
        "auth_identities_email_key_version_positive",
        sql`email_key_version > 0`,
      )
      .addCheckConstraint(
        "auth_identities_password_hash_version_positive",
        sql`password_hash_version > 0`,
      )
      .addCheckConstraint(
        "auth_identities_row_version_nonnegative",
        sql`row_version >= 0`,
      )
      .execute();

    await database.schema
      .withSchema("identity")
      .createTable("verification_challenges")
      .addColumn("challenge_id", "uuid", (column) => column.primaryKey())
      .addColumn("account_id", "uuid", (column) =>
        column.references("identity.accounts.account_id").onDelete("cascade"),
      )
      .addColumn("purpose", "varchar(32)", (column) => column.notNull())
      .addColumn("subject_index", "bytea", (column) => column.notNull())
      .addColumn("subject_index_key_version", "smallint", (column) =>
        column.notNull(),
      )
      .addColumn("secret_digest", "bytea", (column) => column.notNull())
      .addColumn("secret_digest_key_version", "smallint", (column) =>
        column.notNull(),
      )
      .addColumn("pending_registration_ciphertext", "bytea")
      .addColumn("payload_algorithm", "varchar(32)")
      .addColumn("payload_key_version", "smallint")
      .addColumn("status", "varchar(16)", (column) =>
        column.notNull().defaultTo("ACTIVE"),
      )
      .addColumn("expires_at", "timestamptz", (column) => column.notNull())
      .addColumn("consumed_at", "timestamptz")
      .addColumn("superseded_at", "timestamptz")
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
        "verification_challenges_purpose",
        sql`purpose in ('EMAIL_VERIFICATION', 'PASSWORD_RESET')`,
      )
      .addCheckConstraint(
        "verification_challenges_status",
        sql`status in ('ACTIVE', 'CONSUMED', 'SUPERSEDED', 'EXPIRED')`,
      )
      .addCheckConstraint(
        "verification_challenges_expiry",
        sql`expires_at > created_at`,
      )
      .addCheckConstraint(
        "verification_challenges_row_version_nonnegative",
        sql`row_version >= 0`,
      )
      .addCheckConstraint(
        "verification_challenges_subject_key_version_positive",
        sql`subject_index_key_version > 0`,
      )
      .addCheckConstraint(
        "verification_challenges_secret_key_version_positive",
        sql`secret_digest_key_version > 0`,
      )
      .addCheckConstraint(
        "verification_challenges_payload_shape",
        sql`(
          purpose = 'EMAIL_VERIFICATION'
          and account_id is null
          and pending_registration_ciphertext is not null
          and payload_algorithm is not null
          and payload_key_version is not null
          and payload_key_version > 0
        ) or (
          purpose = 'PASSWORD_RESET'
          and account_id is not null
          and pending_registration_ciphertext is null
          and payload_algorithm is null
          and payload_key_version is null
        )`,
      )
      .addCheckConstraint(
        "verification_challenges_terminal_timestamp",
        sql`(
          status = 'ACTIVE'
          and consumed_at is null
          and superseded_at is null
        ) or (
          status = 'CONSUMED'
          and consumed_at is not null
          and superseded_at is null
        ) or (
          status = 'SUPERSEDED'
          and superseded_at is not null
          and consumed_at is null
        ) or (
          status = 'EXPIRED'
          and consumed_at is null
          and superseded_at is null
        )`,
      )
      .execute();

    await database.schema
      .withSchema("identity")
      .createIndex("verification_challenges_one_active_subject")
      .unique()
      .on("verification_challenges")
      .columns(["subject_index", "purpose"])
      .where(sql.ref("status"), "=", "ACTIVE")
      .execute();

    await database.schema
      .withSchema("identity")
      .createIndex("verification_challenges_expiry_index")
      .on("verification_challenges")
      .columns(["status", "expires_at"])
      .execute();
  },

  async down(database) {
    await database.schema
      .withSchema("identity")
      .dropTable("verification_challenges")
      .execute();
    await database.schema
      .withSchema("identity")
      .dropTable("auth_identities")
      .execute();
    await database.schema
      .withSchema("identity")
      .dropTable("accounts")
      .execute();
    await database.schema.dropSchema("identity").execute();
  },
};
