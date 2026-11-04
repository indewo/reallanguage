import type { Migration } from "kysely/migration";

export const adminMfaEnrollmentMigration: Migration = {
  async up(database) {
    await database.schema
      .withSchema("admin")
      .alterTable("accounts")
      .addColumn("mfa_enrollment_confirmed_at", "timestamptz")
      .execute();
  },
  async down(database) {
    await database.schema
      .withSchema("admin")
      .alterTable("accounts")
      .dropColumn("mfa_enrollment_confirmed_at")
      .execute();
  },
};
