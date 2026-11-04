import { sql } from "kysely";
import type { Migration } from "kysely/migration";

export const safetyHandlingMigration: Migration = {
  async up(database) {
    await database.schema
      .withSchema("safety")
      .alterTable("safety_cases")
      .addColumn("resolution_status", "varchar(24)", (column) =>
        column.notNull().defaultTo("BLOCKED"),
      )
      .addColumn("resolution_command_id", "varchar(128)")
      .execute();
    await database.schema
      .withSchema("safety")
      .alterTable("safety_cases")
      .addCheckConstraint(
        "safety_cases_resolution_status",
        sql`resolution_status in ('BLOCKED', 'DEIDENTIFIED_ACCEPTED', 'RETURNED_TO_EDIT', 'REVIEW_PENDING')`,
      )
      .execute();

    await database.schema
      .withSchema("safety")
      .createTable("deidentified_versions")
      .addColumn("deidentified_version_id", "uuid", (column) =>
        column.primaryKey(),
      )
      .addColumn("safety_case_id", "uuid", (column) =>
        column
          .notNull()
          .unique()
          .references("safety.safety_cases.safety_case_id")
          .onDelete("cascade"),
      )
      .addColumn("user_id", "uuid", (column) =>
        column
          .notNull()
          .references("identity.accounts.account_id")
          .onDelete("cascade"),
      )
      .addColumn("payload_ciphertext", "bytea", (column) => column.notNull())
      .addColumn("payload_algorithm", "varchar(32)", (column) =>
        column.notNull(),
      )
      .addColumn("payload_key_version", "smallint", (column) =>
        column.notNull(),
      )
      .addColumn("edits", "jsonb", (column) => column.notNull())
      .addColumn("preserved_intent_summary_zh", "text", (column) =>
        column.notNull(),
      )
      .addColumn("provider_operation_id", "varchar(256)", (column) =>
        column.notNull(),
      )
      .addColumn("internal_safety_valid", "boolean", (column) =>
        column.notNull(),
      )
      .addColumn("expires_at", "timestamptz", (column) => column.notNull())
      .addColumn("created_at", "timestamptz", (column) =>
        column.notNull().defaultTo(sql`now()`),
      )
      .addCheckConstraint(
        "deidentified_versions_safety_valid",
        sql`internal_safety_valid = true`,
      )
      .execute();

    await database.schema
      .withSchema("safety")
      .createTable("manual_review_requests")
      .addColumn("review_request_id", "uuid", (column) => column.primaryKey())
      .addColumn("safety_case_id", "uuid", (column) =>
        column
          .notNull()
          .unique()
          .references("safety.safety_cases.safety_case_id")
          .onDelete("cascade"),
      )
      .addColumn("user_id", "uuid", (column) =>
        column
          .notNull()
          .references("identity.accounts.account_id")
          .onDelete("cascade"),
      )
      .addColumn("command_id", "varchar(128)", (column) => column.notNull())
      .addColumn("status", "varchar(16)", (column) =>
        column.notNull().defaultTo("PENDING"),
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
      .addUniqueConstraint("manual_review_requests_user_command", [
        "user_id",
        "command_id",
      ])
      .addCheckConstraint(
        "manual_review_requests_status",
        sql`status in ('PENDING', 'APPROVED', 'NOT_APPROVED')`,
      )
      .execute();
  },
  async down(database) {
    await database.schema
      .withSchema("safety")
      .dropTable("manual_review_requests")
      .execute();
    await database.schema
      .withSchema("safety")
      .dropTable("deidentified_versions")
      .execute();
    await database.schema
      .withSchema("safety")
      .alterTable("safety_cases")
      .dropConstraint("safety_cases_resolution_status")
      .execute();
    await database.schema
      .withSchema("safety")
      .alterTable("safety_cases")
      .dropColumn("resolution_command_id")
      .execute();
    await database.schema
      .withSchema("safety")
      .alterTable("safety_cases")
      .dropColumn("resolution_status")
      .execute();
  },
};
