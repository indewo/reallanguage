import { sql } from "kysely";
import type { Migration } from "kysely/migration";

export const adminReviewMigration: Migration = {
  async up(database) {
    await database.schema.createSchema("admin").ifNotExists().execute();
    await database.schema
      .withSchema("admin")
      .createTable("accounts")
      .addColumn("admin_id", "uuid", (column) => column.primaryKey())
      .addColumn("email_ciphertext", "bytea", (column) => column.notNull())
      .addColumn("email_algorithm", "varchar(32)", (column) => column.notNull())
      .addColumn("email_key_version", "smallint", (column) => column.notNull())
      .addColumn("email_index", "bytea", (column) => column.notNull().unique())
      .addColumn("email_index_key_version", "smallint", (column) =>
        column.notNull(),
      )
      .addColumn("password_hash", "text", (column) => column.notNull())
      .addColumn("totp_secret_ciphertext", "bytea", (column) =>
        column.notNull(),
      )
      .addColumn("totp_algorithm", "varchar(32)", (column) => column.notNull())
      .addColumn("totp_key_version", "smallint", (column) => column.notNull())
      .addColumn("last_accepted_totp_counter", "bigint")
      .addColumn("enabled", "boolean", (column) =>
        column.notNull().defaultTo(true),
      )
      .addColumn("row_version", "bigint", (column) =>
        column.notNull().defaultTo(1),
      )
      .addColumn("created_at", "timestamptz", (column) =>
        column.notNull().defaultTo(sql`now()`),
      )
      .addColumn("updated_at", "timestamptz", (column) =>
        column.notNull().defaultTo(sql`now()`),
      )
      .execute();
    await database.schema
      .withSchema("admin")
      .createTable("sessions")
      .addColumn("session_id", "uuid", (column) => column.primaryKey())
      .addColumn("admin_id", "uuid", (column) =>
        column
          .notNull()
          .references("admin.accounts.admin_id")
          .onDelete("cascade"),
      )
      .addColumn("bearer_digest", "bytea", (column) =>
        column.notNull().unique(),
      )
      .addColumn("bearer_digest_key_version", "smallint", (column) =>
        column.notNull(),
      )
      .addColumn("expires_at", "timestamptz", (column) => column.notNull())
      .addColumn("revoked_at", "timestamptz")
      .addColumn("created_at", "timestamptz", (column) =>
        column.notNull().defaultTo(sql`now()`),
      )
      .execute();

    await database.schema
      .withSchema("platform")
      .createTable("audit_events")
      .addColumn("audit_event_id", "uuid", (column) => column.primaryKey())
      .addColumn("user_id", "uuid")
      .addColumn("actor_type", "varchar(16)", (column) => column.notNull())
      .addColumn("actor_id", "varchar(128)", (column) => column.notNull())
      .addColumn("action_type", "varchar(96)", (column) => column.notNull())
      .addColumn("resource_type", "varchar(64)", (column) => column.notNull())
      .addColumn("resource_id", "varchar(128)", (column) => column.notNull())
      .addColumn("result", "varchar(32)", (column) => column.notNull())
      .addColumn("reason_code", "varchar(96)")
      .addColumn("request_id", "varchar(128)")
      .addColumn("created_at", "timestamptz", (column) =>
        column.notNull().defaultTo(sql`now()`),
      )
      .addCheckConstraint(
        "audit_events_actor_type",
        sql`actor_type in ('USER', 'ADMIN', 'SYSTEM')`,
      )
      .execute();

    await database.schema
      .withSchema("safety")
      .createTable("review_decisions")
      .addColumn("review_decision_id", "uuid", (column) => column.primaryKey())
      .addColumn("review_request_id", "uuid", (column) =>
        column
          .notNull()
          .unique()
          .references("safety.manual_review_requests.review_request_id")
          .onDelete("cascade"),
      )
      .addColumn("user_id", "uuid", (column) =>
        column
          .notNull()
          .references("identity.accounts.account_id")
          .onDelete("cascade"),
      )
      .addColumn("reviewer_admin_id", "uuid", (column) =>
        column.notNull().references("admin.accounts.admin_id"),
      )
      .addColumn("decision", "varchar(32)", (column) => column.notNull())
      .addColumn("reason_code", "varchar(96)", (column) => column.notNull())
      .addColumn("reason_zh", "text", (column) => column.notNull())
      .addColumn("created_at", "timestamptz", (column) =>
        column.notNull().defaultTo(sql`now()`),
      )
      .addCheckConstraint(
        "review_decisions_decision",
        sql`decision in ('APPROVE', 'KEEP_BLOCKED', 'REQUIRES_DEIDENTIFICATION')`,
      )
      .execute();
  },

  async down(database) {
    await database.schema
      .withSchema("safety")
      .dropTable("review_decisions")
      .execute();
    await database.schema
      .withSchema("platform")
      .dropTable("audit_events")
      .execute();
    await database.schema.withSchema("admin").dropTable("sessions").execute();
    await database.schema.withSchema("admin").dropTable("accounts").execute();
    await database.schema.dropSchema("admin").execute();
  },
};
