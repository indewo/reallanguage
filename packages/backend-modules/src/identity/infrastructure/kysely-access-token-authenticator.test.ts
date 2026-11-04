import { PGlite } from "@electric-sql/pglite";
import { migrateToLatest } from "@reallanguage/database";
import { Kysely, PGliteDialect, sql } from "kysely";
import { afterEach, beforeEach, describe, expect, it } from "vitest";

import type { IdentitySessionDatabase } from "./kysely-credential-verifier.js";
import { KyselyAccessTokenAuthenticator } from "./kysely-access-token-authenticator.js";

const accountId = "30000000-0000-4000-8000-000000000001";
const now = new Date("2026-09-02T00:00:00.000Z");
let database: Kysely<IdentitySessionDatabase>;

beforeEach(async () => {
  database = new Kysely({
    dialect: new PGliteDialect({ pglite: new PGlite() }),
  });
  await migrateToLatest(database);
  await sql`
    insert into identity.accounts (
      account_id, birth_year_month_ciphertext, birth_year_month_algorithm,
      birth_year_month_key_version, age_eligible, age_eligibility_confirmed_at,
      email_verified_at
    ) values (${accountId}, decode('01', 'hex'), 'AES-256-GCM/v1', 1, true, ${now}, ${now})
  `.execute(database);
  await sql`
    insert into identity.user_sessions (
      session_id, account_id, access_token_digest, access_token_digest_key_version,
      access_token_expires_at, refresh_token_digest, refresh_token_digest_key_version,
      device_id_digest, device_id_digest_key_version, expires_at, last_used_at,
      created_at, updated_at
    ) values (
      '40000000-0000-4000-8000-000000000001', ${accountId}, decode('06', 'hex'), 3,
      ${new Date("2026-09-02T00:15:00.000Z")}, decode('07', 'hex'), 4,
      decode('08', 'hex'), 5, ${new Date("2026-10-01T00:00:00.000Z")}, ${now}, ${now}, ${now}
    )
  `.execute(database);
});

afterEach(async () => database.destroy());

describe("KyselyAccessTokenAuthenticator", () => {
  it("returns the account only for an active unexpired access digest", async () => {
    const authenticator = new KyselyAccessTokenAuthenticator(
      database,
      { digest: () => ({ digest: Uint8Array.of(6), keyVersion: 3 }) },
      { now: () => now },
    );
    await expect(authenticator.authenticate("access")).resolves.toBe(accountId);

    const expired = new KyselyAccessTokenAuthenticator(
      database,
      { digest: () => ({ digest: Uint8Array.of(6), keyVersion: 3 }) },
      { now: () => new Date("2026-09-02T00:15:00.000Z") },
    );
    await expect(expired.authenticate("access")).resolves.toBeNull();
  });

  it("rejects a revoked session and an unknown digest neutrally", async () => {
    await sql`update identity.user_sessions set revoked_at = ${now}`.execute(
      database,
    );
    const revoked = new KyselyAccessTokenAuthenticator(
      database,
      { digest: () => ({ digest: Uint8Array.of(6), keyVersion: 3 }) },
      { now: () => now },
    );
    await expect(revoked.authenticate("access")).resolves.toBeNull();

    const unknown = new KyselyAccessTokenAuthenticator(
      database,
      { digest: () => ({ digest: Uint8Array.of(99), keyVersion: 3 }) },
      { now: () => now },
    );
    await expect(unknown.authenticate("access")).resolves.toBeNull();
  });
});
