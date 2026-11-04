import { sql } from "kysely";
import type { Migration } from "kysely/migration";

export const messagingAppMessagesMigration: Migration = {
  async up(database) {
    await database.schema.createSchema("messaging").ifNotExists().execute();
    await database.schema
      .withSchema("messaging")
      .createTable("app_messages")
      .addColumn("message_id", "uuid", (column) => column.primaryKey())
      .addColumn("user_id", "uuid", (column) =>
        column
          .notNull()
          .references("identity.accounts.account_id")
          .onDelete("cascade"),
      )
      .addColumn("kind", "varchar(32)", (column) => column.notNull())
      .addColumn("summary_zh", "text", (column) => column.notNull())
      .addColumn("related_content_label_zh", "text")
      .addColumn("target_route", "varchar(48)")
      .addColumn("target_resource_id", "varchar(128)")
      .addColumn("read_at", "timestamptz")
      .addColumn("expires_at", "timestamptz")
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
        "app_messages_kind",
        sql`kind in ('MANUAL_REVIEW_RESULT', 'SYSTEM')`,
      )
      .addCheckConstraint(
        "app_messages_target_pair",
        sql`(target_route is null) = (target_resource_id is null)`,
      )
      .addCheckConstraint(
        "app_messages_controlled_target",
        sql`target_route is null or target_route in ('MANUAL_REVIEW_RESULT')`,
      )
      .execute();
    await database.schema
      .withSchema("messaging")
      .createIndex("app_messages_user_created")
      .on("app_messages")
      .columns(["user_id", "created_at"])
      .execute();
  },
  async down(database) {
    await database.schema
      .withSchema("messaging")
      .dropTable("app_messages")
      .execute();
    await database.schema.dropSchema("messaging").execute();
  },
};
