import { sql } from "kysely";
import type { Migration } from "kysely/migration";

export const platformOutboxMigration: Migration = {
  async up(database) {
    await database.schema.createSchema("platform").ifNotExists().execute();

    await database.schema
      .withSchema("platform")
      .createTable("outbox_events")
      .addColumn("event_id", "uuid", (column) => column.primaryKey())
      .addColumn("user_id", "uuid")
      .addColumn("aggregate_id", "uuid", (column) => column.notNull())
      .addColumn("event_type", "varchar(100)", (column) => column.notNull())
      .addColumn("schema_version", "smallint", (column) => column.notNull())
      .addColumn("payload", "jsonb", (column) => column.notNull())
      .addColumn("request_id", "varchar(128)", (column) => column.notNull())
      .addColumn("status", "varchar(16)", (column) =>
        column.notNull().defaultTo("PENDING"),
      )
      .addColumn("attempt_count", "integer", (column) =>
        column.notNull().defaultTo(0),
      )
      .addColumn("available_at", "timestamptz", (column) =>
        column.notNull().defaultTo(sql`now()`),
      )
      .addColumn("published_at", "timestamptz")
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
        "outbox_events_schema_version_positive",
        sql`schema_version > 0`,
      )
      .addCheckConstraint(
        "outbox_events_attempt_count_nonnegative",
        sql`attempt_count >= 0`,
      )
      .addCheckConstraint(
        "outbox_events_row_version_nonnegative",
        sql`row_version >= 0`,
      )
      .addCheckConstraint(
        "outbox_events_status",
        sql`status in ('PENDING', 'PUBLISHED')`,
      )
      .addCheckConstraint(
        "outbox_events_publication_timestamp",
        sql`(
          status = 'PENDING' and published_at is null
        ) or (
          status = 'PUBLISHED' and published_at is not null
        )`,
      )
      .execute();

    await database.schema
      .withSchema("platform")
      .createIndex("outbox_events_pending_available")
      .on("outbox_events")
      .columns(["status", "available_at"])
      .where(sql.ref("status"), "=", "PENDING")
      .execute();
  },

  async down(database) {
    await database.schema
      .withSchema("platform")
      .dropTable("outbox_events")
      .execute();
    await database.schema.dropSchema("platform").execute();
  },
};
