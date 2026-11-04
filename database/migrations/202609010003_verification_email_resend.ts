import { sql } from "kysely";
import type { Migration } from "kysely/migration";

export const verificationEmailResendMigration: Migration = {
  async up(database) {
    await database.schema
      .withSchema("identity")
      .alterTable("verification_challenges")
      .addColumn("last_email_requested_at", "timestamptz", (column) =>
        column.notNull().defaultTo(sql`now()`),
      )
      .addColumn("email_request_count", "integer", (column) =>
        column.notNull().defaultTo(1),
      )
      .execute();
    await sql`
      alter table identity.verification_challenges
      add constraint verification_challenges_email_request_count_positive
      check (email_request_count > 0)
    `.execute(database);
  },

  async down(database) {
    await sql`
      alter table identity.verification_challenges
      drop constraint verification_challenges_email_request_count_positive
    `.execute(database);
    await database.schema
      .withSchema("identity")
      .alterTable("verification_challenges")
      .dropColumn("email_request_count")
      .dropColumn("last_email_requested_at")
      .execute();
  },
};
