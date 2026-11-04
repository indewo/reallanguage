import { PGlite } from "@electric-sql/pglite";
import { migrateToLatest } from "@reallanguage/database";
import { Kysely, PGliteDialect, sql } from "kysely";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import type { IdentityPasswordResetDatabase } from "./kysely-password-reset-store.js";
import { KyselyPasswordResetStore } from "./kysely-password-reset-store.js";

const accountId = "30000000-0000-4000-8000-000000000001";
const challengeId = "10000000-0000-4000-8000-000000000001";
const eventId = "20000000-0000-4000-8000-000000000001";
const grantId = "40000000-0000-4000-8000-000000000001";
const now = new Date("2026-09-01T12:00:00.000Z");
const verificationBearer = "v".repeat(43);
const resetBearer = "r".repeat(43);
let database: Kysely<IdentityPasswordResetDatabase>;

beforeEach(async () => {
  database = new Kysely({
    dialect: new PGliteDialect({ pglite: new PGlite() }),
  });
  await migrateToLatest(database);
});

afterEach(async () => database.destroy());

describe("KyselyPasswordResetStore", () => {
  it("keeps unknown emails neutral and replays the stable request identity", async () => {
    const store = createStore([challengeId]);

    await expect(
      store.request({ email: "unknown@example.com", commandId: "request-1" }),
    ).resolves.toEqual({ challengeId });
    await expect(
      store.request({ email: "unknown@example.com", commandId: "request-1" }),
    ).resolves.toEqual({ challengeId });

    expect(await count("identity.verification_challenges")).toBe(0);
    expect(await count("platform.outbox_events")).toBe(0);
    expect(await count("identity.password_reset_requests")).toBe(1);
  });

  it("consumes the link once, changes the password atomically, and revokes sessions", async () => {
    await seedAccountAndSession();
    const passwordHasher = {
      hash: vi.fn().mockResolvedValue("new-password-hash"),
    };
    const store = createStore([challengeId, eventId, grantId], passwordHasher);

    await expect(
      store.request({ email: "learner@example.com", commandId: "request-1" }),
    ).resolves.toEqual({ challengeId });
    const event = await sql<{ payload: unknown }>`
      select payload from platform.outbox_events where aggregate_id = ${challengeId}
    `.execute(database);
    expect(event.rows[0]?.payload).toEqual({ challengeId });
    const delivery = await sql<{
      delivery_secret_ciphertext: Uint8Array;
      delivery_secret_algorithm: string;
      delivery_secret_key_version: number;
    }>`
      select delivery_secret_ciphertext, delivery_secret_algorithm, delivery_secret_key_version
      from identity.password_reset_requests where challenge_id = ${challengeId}
    `.execute(database);
    expect(delivery.rows[0]).toEqual({
      delivery_secret_ciphertext: Uint8Array.of(9, 9),
      delivery_secret_algorithm: "TEST/v1",
      delivery_secret_key_version: 1,
    });

    await expect(
      store.verify({ challengeId, bearer: verificationBearer }),
    ).resolves.toEqual({ resetToken: resetBearer });
    await expect(
      store.verify({ challengeId, bearer: verificationBearer }),
    ).resolves.toBeNull();

    await expect(
      store.complete({
        resetToken: resetBearer,
        normalizedPassword: "normalized-password",
        commandId: "completion-1",
      }),
    ).resolves.toEqual({ initialSettingsComplete: true });
    await expect(
      store.complete({
        resetToken: resetBearer,
        normalizedPassword: "ignored-on-replay",
        commandId: "completion-1",
      }),
    ).resolves.toEqual({ initialSettingsComplete: true });
    await expect(
      store.complete({
        resetToken: resetBearer,
        normalizedPassword: "cannot-reuse",
        commandId: "completion-2",
      }),
    ).resolves.toBeNull();

    expect(passwordHasher.hash).toHaveBeenCalledTimes(1);
    const identity = await sql<{ password_hash: string; row_version: number }>`
      select password_hash, row_version from identity.auth_identities where account_id = ${accountId}
    `.execute(database);
    expect(identity.rows[0]).toEqual({
      password_hash: "new-password-hash",
      row_version: 1,
    });
    const session = await sql<{ revoked_at: Date | null }>`
      select revoked_at from identity.user_sessions where account_id = ${accountId}
    `.execute(database);
    expect(session.rows[0]?.revoked_at).not.toBeNull();
  });
});

const createStore = (
  identifiers: string[],
  passwordHasher = { hash: vi.fn().mockResolvedValue("unused-hash") },
) => {
  const remaining = [...identifiers];
  return new KyselyPasswordResetStore(database, {
    clock: { now: () => now },
    identifiers: { next: () => remaining.shift() ?? grantId },
    emailIndex: {
      create: () => ({ digest: Uint8Array.of(1), keyVersion: 1 }),
    },
    verificationSecrets: {
      create: () => ({
        bearer: verificationBearer,
        digest: Uint8Array.of(2),
        digestKeyVersion: 1,
      }),
    },
    deliverySecrets: {
      protect: vi.fn().mockResolvedValue({
        ciphertext: Uint8Array.of(9, 9),
        algorithm: "TEST/v1",
        keyVersion: 1,
      }),
    },
    verificationSecretVerifier: {
      verify: (candidate) =>
        candidate.bearer === verificationBearer && candidate.digest[0] === 2,
    },
    resetTokens: {
      create: () => ({
        bearer: resetBearer,
        digest: Uint8Array.of(3),
        digestKeyVersion: 1,
      }),
    },
    resetTokenDigester: {
      digest: () => ({ digest: Uint8Array.of(3), keyVersion: 1 }),
    },
    passwordHasher,
    settings: { hasCompleted: vi.fn().mockResolvedValue(true) },
  });
};

const count = async (table: string) => {
  const result = await sql<{ count: number }>`
    select count(*)::int as count from ${sql.table(table)}
  `.execute(database);
  return result.rows[0]?.count;
};

const seedAccountAndSession = async () => {
  await sql`
    insert into identity.accounts (
      account_id, birth_year_month_ciphertext, birth_year_month_algorithm,
      birth_year_month_key_version, age_eligible, age_eligibility_confirmed_at,
      email_verified_at, created_at, updated_at
    ) values (
      ${accountId}, decode('01', 'hex'), 'AES-256-GCM/v1', 1, true, ${now}, ${now}, ${now}, ${now}
    )
  `.execute(database);
  await sql`
    insert into identity.auth_identities (
      account_id, normalized_email_index, normalized_email_index_key_version,
      email_ciphertext, email_algorithm, email_key_version, password_hash,
      password_hash_version, verified_at, created_at, updated_at
    ) values (
      ${accountId}, decode('01', 'hex'), 1, decode('02', 'hex'), 'AES-256-GCM/v1',
      1, 'old-password-hash', 1, ${now}, ${now}, ${now}
    )
  `.execute(database);
  await sql`
    insert into identity.user_sessions (
      session_id, account_id, refresh_token_digest, refresh_token_digest_key_version,
      device_id_digest, device_id_digest_key_version, expires_at, last_used_at,
      created_at, updated_at
    ) values (
      '50000000-0000-4000-8000-000000000001', ${accountId}, decode('04', 'hex'),
      1, decode('05', 'hex'), 1, ${new Date("2026-10-01T00:00:00.000Z")},
      ${now}, ${now}, ${now}
    )
  `.execute(database);
};
