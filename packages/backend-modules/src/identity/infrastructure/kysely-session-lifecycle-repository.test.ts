import { PGlite } from "@electric-sql/pglite";
import { migrateToLatest } from "@reallanguage/database";
import { Kysely, PGliteDialect, sql } from "kysely";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import type { IdentitySessionDatabase } from "./kysely-credential-verifier.js";
import { KyselySessionLifecycleRepository } from "./kysely-session-lifecycle-repository.js";
import { KyselyInitialSettingsWriter } from "../../settings/infrastructure/kysely-initial-settings-writer.js";
import type { SettingsDatabase } from "../../settings/infrastructure/kysely-initial-settings-writer.js";

const accountId = "30000000-0000-4000-8000-000000000001";
const oldSessionId = "40000000-0000-4000-8000-000000000001";
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
      session_id, account_id, refresh_token_digest,
      refresh_token_digest_key_version, device_id_digest,
      device_id_digest_key_version, expires_at, last_used_at, created_at, updated_at
    ) values (
      ${oldSessionId}, ${accountId}, decode('07', 'hex'), 4, decode('08', 'hex'), 5,
      ${new Date("2026-10-01T00:00:00.000Z")}, ${now}, ${now}, ${now}
    )
  `.execute(database);
});

afterEach(async () => database.destroy());

describe("KyselySessionLifecycleRepository", () => {
  it("rotates with a database-backed settings reader without deadlocking", async () => {
    const repository = new KyselySessionLifecycleRepository(database, {
      clock: { now: () => now },
      identifiers: { next: () => "40000000-0000-4000-8000-000000000003" },
      bearerDigester: {
        digest: () => ({ digest: Uint8Array.of(7), keyVersion: 4 }),
      },
      devices: { digest: () => ({ digest: Uint8Array.of(8), keyVersion: 5 }) },
      secrets: {
        create: () => ({
          accessToken: "next-access",
          accessTokenDigest: Uint8Array.of(6),
          accessTokenDigestKeyVersion: 3,
          refreshToken: "next-refresh",
          refreshTokenDigest: Uint8Array.of(9),
          refreshTokenDigestKeyVersion: 4,
        }),
      },
      settings: new KyselyInitialSettingsWriter(
        database as unknown as Kysely<SettingsDatabase>,
      ),
    });

    await expect(
      repository.rotate({ refreshToken: "old-refresh", deviceId: "device" }),
    ).resolves.toMatchObject({
      accountId,
      settingsCompleted: false,
    });
  }, 1_000);

  it("atomically rotates once and then revokes the replacement idempotently", async () => {
    const repository = new KyselySessionLifecycleRepository(database, {
      clock: { now: () => now },
      identifiers: { next: () => "40000000-0000-4000-8000-000000000002" },
      bearerDigester: {
        digest: (bearer) => ({
          digest: Uint8Array.of(bearer === "old-refresh" ? 7 : 9),
          keyVersion: 4,
        }),
      },
      devices: { digest: () => ({ digest: Uint8Array.of(8), keyVersion: 5 }) },
      secrets: {
        create: () => ({
          accessToken: "next-access",
          accessTokenDigest: Uint8Array.of(6),
          accessTokenDigestKeyVersion: 3,
          refreshToken: "next-refresh",
          refreshTokenDigest: Uint8Array.of(9),
          refreshTokenDigestKeyVersion: 4,
        }),
      },
      settings: { hasCompleted: vi.fn().mockResolvedValue(true) },
    });

    await expect(
      repository.rotate({ refreshToken: "old-refresh", deviceId: "device" }),
    ).resolves.toEqual({
      accountId,
      settingsCompleted: true,
      accessToken: "next-access",
      refreshToken: "next-refresh",
      accessTokenExpiresInSeconds: 900,
    });
    await expect(
      repository.rotate({ refreshToken: "old-refresh", deviceId: "device" }),
    ).resolves.toBeNull();

    await repository.revoke("next-refresh");
    await repository.revoke("next-refresh");
    const active = await sql<{ count: number }>`
      select count(*)::int as count from identity.user_sessions where revoked_at is null
    `.execute(database);
    expect(active.rows[0]?.count).toBe(0);
  });
});
