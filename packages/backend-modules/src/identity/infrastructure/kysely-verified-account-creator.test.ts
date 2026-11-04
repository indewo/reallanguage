import { PGlite } from "@electric-sql/pglite";
import { migrateToLatest } from "@reallanguage/database";
import { Kysely, PGliteDialect, sql } from "kysely";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import type { PendingRegistrationChallenge } from "../application/verify-registration.js";
import type { IdentityRegistrationDatabase } from "./kysely-eligible-registration-starter.js";
import { KyselyVerifiedAccountCreator } from "./kysely-verified-account-creator.js";

const verifiedAt = new Date("2026-09-01T00:10:00.000Z");
const challenge: PendingRegistrationChallenge = {
  challengeId: "10000000-0000-4000-8000-000000000001",
  accountId: null,
  status: "ACTIVE",
  expiresAt: new Date("2026-09-01T00:30:00.000Z"),
  subjectIndex: Uint8Array.of(1),
  subjectIndexKeyVersion: 2,
  secretDigest: Uint8Array.of(3),
  secretDigestKeyVersion: 4,
  protectedPayload: {
    ciphertext: Uint8Array.of(5),
    algorithm: "AES-256-GCM/v1",
    keyVersion: 6,
  },
};

let database: Kysely<IdentityRegistrationDatabase>;

beforeEach(async () => {
  database = new Kysely({
    dialect: new PGliteDialect({ pglite: new PGlite() }),
  });
  await migrateToLatest(database);
  await sql`
    insert into identity.verification_challenges (
      challenge_id, purpose, subject_index, subject_index_key_version,
      secret_digest, secret_digest_key_version,
      pending_registration_ciphertext, payload_algorithm, payload_key_version,
      status, expires_at, created_at, updated_at
    ) values (
      ${challenge.challengeId}, 'EMAIL_VERIFICATION', decode('01', 'hex'), 2,
      decode('03', 'hex'), 4, decode('05', 'hex'), 'AES-256-GCM/v1', 6,
      'ACTIVE', ${challenge.expiresAt}, '2026-09-01T00:00:00.000Z',
      '2026-09-01T00:00:00.000Z'
    )
  `.execute(database);
});

afterEach(async () => {
  await database.destroy();
});

describe("KyselyVerifiedAccountCreator", () => {
  it("consumes the challenge and creates the verified account atomically", async () => {
    const creator = createCreator();

    await expect(creator.create({ challenge, verifiedAt })).resolves.toEqual({
      accountId: "20000000-0000-4000-8000-000000000001",
    });

    const account = await sql<{
      account_id: string;
      age_eligible: boolean;
      email_verified_at: Date;
    }>`select account_id, age_eligible, email_verified_at from identity.accounts`.execute(
      database,
    );
    const identity = await sql<{
      account_id: string;
      password_hash: string;
      verified_at: Date;
    }>`select account_id, password_hash, verified_at from identity.auth_identities`.execute(
      database,
    );
    const consumed = await sql<{
      status: string;
      account_id: string | null;
      consumed_at: Date;
    }>`select status, account_id, consumed_at from identity.verification_challenges`.execute(
      database,
    );

    expect(account.rows).toHaveLength(1);
    expect(account.rows[0]).toMatchObject({
      account_id: "20000000-0000-4000-8000-000000000001",
      age_eligible: true,
    });
    expect(identity.rows[0]).toMatchObject({
      account_id: "20000000-0000-4000-8000-000000000001",
      password_hash: "argon2id-hash",
    });
    expect(consumed.rows[0]).toMatchObject({
      status: "CONSUMED",
      account_id: "20000000-0000-4000-8000-000000000001",
    });
    expect(new Date(consumed.rows[0]!.consumed_at).toISOString()).toBe(
      verifiedAt.toISOString(),
    );
  });

  it("returns null without creating another account after the challenge is consumed", async () => {
    const creator = createCreator();

    await creator.create({ challenge, verifiedAt });
    await expect(creator.create({ challenge, verifiedAt })).resolves.toBeNull();

    const result = await sql<{ count: number }>`
      select count(*)::int as count from identity.accounts
    `.execute(database);
    expect(result.rows[0]?.count).toBe(1);
  });

  it("consumes a new verified challenge for an existing email without replacing its account", async () => {
    const creator = createCreator();
    const created = await creator.create({ challenge, verifiedAt });
    if (!created) throw new Error("Expected initial account");
    const secondChallenge = {
      ...challenge,
      challengeId: "10000000-0000-4000-8000-000000000002",
      accountId: created.accountId,
    };
    await sql`
      insert into identity.verification_challenges (
        challenge_id, purpose, subject_index, subject_index_key_version,
        secret_digest, secret_digest_key_version,
        pending_registration_ciphertext, payload_algorithm, payload_key_version,
        status, expires_at, created_at, updated_at
      ) values (
        ${secondChallenge.challengeId}, 'EMAIL_VERIFICATION', decode('01', 'hex'), 2,
        decode('03', 'hex'), 4, decode('05', 'hex'), 'AES-256-GCM/v1', 6,
        'ACTIVE', ${secondChallenge.expiresAt}, '2026-09-01T00:01:00.000Z',
        '2026-09-01T00:01:00.000Z'
      )
    `.execute(database);

    await expect(
      creator.create({ challenge: secondChallenge, verifiedAt }),
    ).resolves.toEqual({ accountId: created.accountId });

    const accounts = await sql<{ count: number }>`
      select count(*)::int as count from identity.accounts
    `.execute(database);
    const consumed = await sql<{ status: string; account_id: string | null }>`
      select status, account_id from identity.verification_challenges
      where challenge_id = ${secondChallenge.challengeId}
    `.execute(database);
    expect(accounts.rows[0]?.count).toBe(1);
    expect(consumed.rows[0]).toEqual({
      status: "CONSUMED",
      account_id: created.accountId,
    });
  });

  it("rolls challenge consumption back when account creation fails", async () => {
    const creator = createCreator();
    await creator.create({ challenge, verifiedAt });
    const secondChallenge = {
      ...challenge,
      challengeId: "10000000-0000-4000-8000-000000000002",
    };
    await sql`
      insert into identity.verification_challenges (
        challenge_id, purpose, subject_index, subject_index_key_version,
        secret_digest, secret_digest_key_version,
        pending_registration_ciphertext, payload_algorithm, payload_key_version,
        status, expires_at, created_at, updated_at
      ) values (
        ${secondChallenge.challengeId}, 'EMAIL_VERIFICATION', decode('01', 'hex'), 2,
        decode('03', 'hex'), 4, decode('05', 'hex'), 'AES-256-GCM/v1', 6,
        'ACTIVE', ${secondChallenge.expiresAt}, '2026-09-01T00:00:00.000Z',
        '2026-09-01T00:00:00.000Z'
      )
    `.execute(database);

    await expect(
      creator.create({ challenge: secondChallenge, verifiedAt }),
    ).rejects.toThrow();

    const accounts = await sql<{ count: number }>`
      select count(*)::int as count from identity.accounts
    `.execute(database);
    const challengeStatus = await sql<{ status: string }>`
      select status from identity.verification_challenges
      where challenge_id = ${secondChallenge.challengeId}
    `.execute(database);
    expect(accounts.rows[0]?.count).toBe(1);
    expect(challengeStatus.rows[0]?.status).toBe("ACTIVE");
  });
});

const createCreator = () =>
  new KyselyVerifiedAccountCreator(database, {
    identifiers: {
      next: vi
        .fn()
        .mockReturnValueOnce("20000000-0000-4000-8000-000000000001")
        .mockReturnValue("20000000-0000-4000-8000-000000000002"),
    },
    payloadProtector: {
      unprotect: vi.fn().mockResolvedValue({
        email: "learner@example.com",
        passwordHash: "argon2id-hash",
        birthYearMonth: { year: 2000, month: 1 },
        acceptedTerms: true,
        acceptedPrivacyPolicy: true,
        acceptedAt: new Date("2026-09-01T00:00:00.000Z"),
        verificationBearer: "bearer",
      }),
    },
    accountFields: {
      protectEmail: vi.fn().mockResolvedValue({
        ciphertext: Uint8Array.of(7),
        algorithm: "AES-256-GCM/v1",
        keyVersion: 8,
      }),
      protectBirthYearMonth: vi.fn().mockResolvedValue({
        ciphertext: Uint8Array.of(9),
        algorithm: "AES-256-GCM/v1",
        keyVersion: 10,
      }),
    },
  });
