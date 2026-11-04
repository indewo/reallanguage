import { PGlite } from "@electric-sql/pglite";
import { migrateToLatest } from "@reallanguage/database";
import { Kysely, PGliteDialect, sql } from "kysely";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import {
  KyselyCredentialVerifier,
  type IdentitySessionDatabase,
} from "./kysely-credential-verifier.js";

let database: Kysely<IdentitySessionDatabase>;

beforeEach(async () => {
  database = new Kysely({
    dialect: new PGliteDialect({ pglite: new PGlite() }),
  });
  await migrateToLatest(database);
});

afterEach(async () => database.destroy());

describe("KyselyCredentialVerifier", () => {
  it("finds the blind-indexed identity, verifies its hash, and reads settings state", async () => {
    await insertIdentity();
    const verify = vi.fn().mockResolvedValue(true);
    const hasCompleted = vi.fn().mockResolvedValue(false);
    const verifier = new KyselyCredentialVerifier(database, {
      emailIndex: {
        create: () => ({ digest: Uint8Array.of(7), keyVersion: 3 }),
      },
      passwordHasher: { verify },
      settings: { hasCompleted },
      dummyPasswordHash: "dummy-hash",
    });

    await expect(
      verifier.verify({ email: "Learner@Example.com", password: "password" }),
    ).resolves.toEqual({ accountId: accountId, settingsCompleted: false });
    expect(verify).toHaveBeenCalledWith("stored-hash", "password");
    expect(hasCompleted).toHaveBeenCalledWith(accountId);
  });

  it("uses a dummy hash for an unknown email and exposes only invalid credentials", async () => {
    const verify = vi.fn().mockResolvedValue(false);
    const verifier = new KyselyCredentialVerifier(database, {
      emailIndex: {
        create: () => ({ digest: Uint8Array.of(9), keyVersion: 3 }),
      },
      passwordHasher: { verify },
      settings: { hasCompleted: vi.fn() },
      dummyPasswordHash: "dummy-hash",
    });

    await expect(
      verifier.verify({ email: "missing@example.com", password: "password" }),
    ).resolves.toBeNull();
    expect(verify).toHaveBeenCalledWith("dummy-hash", "password");
  });
});

const accountId = "30000000-0000-4000-8000-000000000001";

const insertIdentity = async () => {
  await sql`
    insert into identity.accounts (
      account_id, birth_year_month_ciphertext, birth_year_month_algorithm,
      birth_year_month_key_version, age_eligible, age_eligibility_confirmed_at,
      email_verified_at
    ) values (
      ${accountId}, decode('01', 'hex'), 'AES-256-GCM/v1', 1, true, now(), now()
    )
  `.execute(database);
  await sql`
    insert into identity.auth_identities (
      account_id, normalized_email_index, normalized_email_index_key_version,
      email_ciphertext, email_algorithm, email_key_version, password_hash,
      password_hash_version, verified_at
    ) values (
      ${accountId}, decode('07', 'hex'), 3, decode('02', 'hex'),
      'AES-256-GCM/v1', 1, 'stored-hash', 1, now()
    )
  `.execute(database);
};
