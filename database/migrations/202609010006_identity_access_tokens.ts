import { sql } from "kysely";
import type { Migration } from "kysely/migration";

export const identityAccessTokensMigration: Migration = {
  async up(database) {
    await database.schema
      .withSchema("identity")
      .alterTable("user_sessions")
      .addColumn("access_token_digest", "bytea")
      .addColumn("access_token_digest_key_version", "smallint")
      .addColumn("access_token_expires_at", "timestamptz")
      .execute();
    await database.schema
      .withSchema("identity")
      .alterTable("user_sessions")
      .addCheckConstraint(
        "user_sessions_access_digest_shape",
        sql`(
          access_token_digest is null
          and access_token_digest_key_version is null
          and access_token_expires_at is null
        ) or (
          access_token_digest is not null
          and access_token_digest_key_version > 0
          and access_token_expires_at > created_at
          and access_token_expires_at <= expires_at
        )`,
      )
      .execute();
    await database.schema
      .withSchema("identity")
      .createIndex("user_sessions_access_token")
      .on("user_sessions")
      .columns(["access_token_digest", "access_token_digest_key_version"])
      .where("access_token_digest", "is not", null)
      .execute();
  },

  async down(database) {
    await database.schema
      .withSchema("identity")
      .dropIndex("user_sessions_access_token")
      .execute();
    await database.schema
      .withSchema("identity")
      .alterTable("user_sessions")
      .dropConstraint("user_sessions_access_digest_shape")
      .execute();
    await database.schema
      .withSchema("identity")
      .alterTable("user_sessions")
      .dropColumn("access_token_digest")
      .execute();
    await database.schema
      .withSchema("identity")
      .alterTable("user_sessions")
      .dropColumn("access_token_digest_key_version")
      .execute();
    await database.schema
      .withSchema("identity")
      .alterTable("user_sessions")
      .dropColumn("access_token_expires_at")
      .execute();
  },
};
