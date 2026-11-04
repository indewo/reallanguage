import { sql } from "kysely";
import type { Migration } from "kysely/migration";

export const safetyChecksMigration: Migration = {
  async up(database) {
    await database.schema.createSchema("safety").ifNotExists().execute();

    await database.schema
      .withSchema("safety")
      .createTable("check_operations")
      .addColumn("operation_id", "uuid", (column) => column.primaryKey())
      .addColumn("user_id", "uuid", (column) =>
        column
          .notNull()
          .references("identity.accounts.account_id")
          .onDelete("cascade"),
      )
      .addColumn("command_id", "varchar(128)", (column) => column.notNull())
      .addColumn("request_digest", "bytea", (column) => column.notNull())
      .addColumn("source_type", "varchar(16)", (column) => column.notNull())
      .addColumn("content_shape", "varchar(16)", (column) => column.notNull())
      .addColumn("payload_ciphertext", "bytea")
      .addColumn("payload_algorithm", "varchar(32)")
      .addColumn("payload_key_version", "smallint")
      .addColumn("status", "varchar(16)", (column) =>
        column.notNull().defaultTo("PROCESSING"),
      )
      .addColumn("policy_version", "varchar(128)")
      .addColumn("provider_operation_id", "varchar(256)")
      .addColumn("safety_case_id", "uuid")
      .addColumn("expires_at", "timestamptz", (column) => column.notNull())
      .addColumn("row_version", "bigint", (column) =>
        column.notNull().defaultTo(0),
      )
      .addColumn("created_at", "timestamptz", (column) =>
        column.notNull().defaultTo(sql`now()`),
      )
      .addColumn("updated_at", "timestamptz", (column) =>
        column.notNull().defaultTo(sql`now()`),
      )
      .addUniqueConstraint("safety_check_operations_user_command", [
        "user_id",
        "command_id",
      ])
      .addCheckConstraint(
        "safety_check_operations_source_type",
        sql`source_type in ('DIARY', 'RECORDING', 'AI_SCENE')`,
      )
      .addCheckConstraint(
        "safety_check_operations_content_shape",
        sql`content_shape in ('ARTICLE', 'DIALOGUE')`,
      )
      .addCheckConstraint(
        "safety_check_operations_status",
        sql`status in ('PROCESSING', 'PASSED', 'BLOCKED', 'FAILED')`,
      )
      .addCheckConstraint(
        "safety_check_operations_payload_shape",
        sql`(
          payload_ciphertext is null
          and payload_algorithm is null
          and payload_key_version is null
        ) or (
          payload_ciphertext is not null
          and payload_algorithm is not null
          and payload_key_version > 0
        )`,
      )
      .addCheckConstraint(
        "safety_check_operations_expiry",
        sql`expires_at > created_at`,
      )
      .execute();

    await database.schema
      .withSchema("safety")
      .createTable("safety_cases")
      .addColumn("safety_case_id", "uuid", (column) => column.primaryKey())
      .addColumn("operation_id", "uuid", (column) =>
        column
          .notNull()
          .unique()
          .references("safety.check_operations.operation_id")
          .onDelete("cascade"),
      )
      .addColumn("user_id", "uuid", (column) =>
        column
          .notNull()
          .references("identity.accounts.account_id")
          .onDelete("cascade"),
      )
      .addColumn("policy_version", "varchar(128)", (column) => column.notNull())
      .addColumn("reason_code", "varchar(128)", (column) => column.notNull())
      .addColumn("user_reason_zh", "text", (column) => column.notNull())
      .addColumn("risk_spans", "jsonb", (column) => column.notNull())
      .addColumn("confidence", "double precision", (column) => column.notNull())
      .addColumn("context_considered", "boolean", (column) => column.notNull())
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
        "safety_cases_confidence",
        sql`confidence >= 0 and confidence <= 1`,
      )
      .addCheckConstraint(
        "safety_cases_context",
        sql`context_considered = true`,
      )
      .execute();
  },

  async down(database) {
    await database.schema
      .withSchema("safety")
      .dropTable("safety_cases")
      .execute();
    await database.schema
      .withSchema("safety")
      .dropTable("check_operations")
      .execute();
    await database.schema.dropSchema("safety").execute();
  },
};
