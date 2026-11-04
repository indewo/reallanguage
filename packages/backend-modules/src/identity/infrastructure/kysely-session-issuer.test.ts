import { PGlite } from "@electric-sql/pglite";
import { migrateToLatest } from "@reallanguage/database";
import { Kysely, PGliteDialect, sql } from "kysely";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import type { IdentitySessionDatabase } from "./kysely-credential-verifier.js";
import { KyselySessionIssuer } from "./kysely-session-issuer.js";

const accountId = "30000000-0000-4000-8000-000000000001";
const now = new Date("2026-09-01T00:00:00.000Z");
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
    ) values (
      ${accountId}, decode('01', 'hex'), 'AES-256-GCM/v1', 1, true, ${now}, ${now}
    )
  `.execute(database);
});

afterEach(async () => database.destroy());

describe("KyselySessionIssuer", () => {
  it("stores only refresh and device digests with a server-controlled expiry", async () => {
    const issuer = new KyselySessionIssuer(database, {
      clock: { now: () => now },
      identifiers: {
        next: vi.fn().mockReturnValue("40000000-0000-4000-8000-000000000001"),
      },
      secrets: {
        create: () => ({
          accessToken: "access-private",
          accessTokenDigest: Uint8Array.of(6),
          accessTokenDigestKeyVersion: 3,
          refreshToken: "refresh-private",
          refreshTokenDigest: Uint8Array.of(7),
          refreshTokenDigestKeyVersion: 4,
        }),
      },
      devices: {
        digest: () => ({ digest: Uint8Array.of(8), keyVersion: 5 }),
      },
    });

    await expect(
      issuer.issue({ accountId, deviceId: "android-installation-1" }),
    ).resolves.toEqual({
      accessToken: "access-private",
      refreshToken: "refresh-private",
      accessTokenExpiresInSeconds: 900,
    });

    const stored = await sql<{
      access_token_digest: Uint8Array;
      access_token_expires_at: Date;
      refresh_token_digest: Uint8Array;
      device_id_digest: Uint8Array;
      expires_at: Date;
      revoked_at: Date | null;
    }>`
      select access_token_digest, access_token_expires_at,
             refresh_token_digest, device_id_digest, expires_at, revoked_at
      from identity.user_sessions
    `.execute(database);
    expect(Buffer.from(stored.rows[0]!.refresh_token_digest)).toEqual(
      Buffer.from([7]),
    );
    expect(Buffer.from(stored.rows[0]!.access_token_digest)).toEqual(
      Buffer.from([6]),
    );
    expect(
      new Date(stored.rows[0]!.access_token_expires_at).toISOString(),
    ).toBe("2026-09-01T00:15:00.000Z");
    expect(Buffer.from(stored.rows[0]!.device_id_digest)).toEqual(
      Buffer.from([8]),
    );
    expect(new Date(stored.rows[0]!.expires_at).toISOString()).toBe(
      "2026-10-01T00:00:00.000Z",
    );
    expect(stored.rows[0]!.revoked_at).toBeNull();
  });
});
