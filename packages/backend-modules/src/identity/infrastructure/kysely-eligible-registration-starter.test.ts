import { PGlite } from "@electric-sql/pglite";
import { migrateToLatest } from "@reallanguage/database";
import { Kysely, PGliteDialect, sql } from "kysely";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import type { StartRegistrationCommand } from "../application/start-registration.js";
import {
  KyselyEligibleRegistrationStarter,
  type IdentityRegistrationDatabase,
} from "./kysely-eligible-registration-starter.js";

const now = new Date("2026-09-01T00:00:00.000Z");
const command: StartRegistrationCommand = {
  requestId: "request-1",
  email: "learner@example.com",
  password: "a normalized password",
  birthYearMonth: { year: 2000, month: 1 },
  acceptedTerms: true,
  acceptedPrivacyPolicy: true,
};

let database: Kysely<IdentityRegistrationDatabase>;

beforeEach(async () => {
  database = new Kysely({
    dialect: new PGliteDialect({ pglite: new PGlite() }),
  });
  await migrateToLatest(database);
});

afterEach(async () => {
  await database.destroy();
});

describe("KyselyEligibleRegistrationStarter", () => {
  it("atomically stores a pending challenge and an email outbox event without an account", async () => {
    const protect = vi.fn().mockResolvedValue({
      subjectIndex: Uint8Array.of(1),
      subjectIndexKeyVersion: 6,
      ciphertext: Uint8Array.of(2),
      algorithm: "AES-256-GCM",
      keyVersion: 7,
    });
    const starter = new KyselyEligibleRegistrationStarter(database, {
      clock: { now: () => now },
      identifiers: sequenceIdentifiers([
        "10000000-0000-4000-8000-000000000001",
        "20000000-0000-4000-8000-000000000001",
      ]),
      passwordHasher: { hash: vi.fn().mockResolvedValue("argon2id-hash") },
      payloadProtector: { protect },
      verificationSecrets: {
        create: () => ({
          bearer: "secret-token",
          digest: Uint8Array.of(3),
          digestKeyVersion: 9,
        }),
      },
    });

    await expect(starter.startEligibleRegistration(command)).resolves.toEqual({
      registrationId: "10000000-0000-4000-8000-000000000001",
    });

    expect(protect).toHaveBeenCalledWith({
      email: command.email,
      passwordHash: "argon2id-hash",
      birthYearMonth: command.birthYearMonth,
      acceptedTerms: true,
      acceptedPrivacyPolicy: true,
      acceptedAt: now,
      verificationBearer: "secret-token",
    });

    const accounts =
      await sql`select account_id from identity.accounts`.execute(database);
    const challenges = await sql<{
      challenge_id: string;
      expires_at: Date;
      created_at: Date;
    }>`select challenge_id, expires_at, created_at from identity.verification_challenges`.execute(
      database,
    );
    const events = await sql<{
      aggregate_id: string;
      event_type: string;
      payload: unknown;
    }>`select aggregate_id, event_type, payload from platform.outbox_events`.execute(
      database,
    );

    expect(accounts.rows).toEqual([]);
    expect(challenges.rows).toHaveLength(1);
    expect(challenges.rows[0]?.challenge_id).toBe(
      "10000000-0000-4000-8000-000000000001",
    );
    expect(new Date(challenges.rows[0]!.expires_at).toISOString()).toBe(
      "2026-09-01T00:30:00.000Z",
    );
    expect(new Date(challenges.rows[0]!.created_at)).toEqual(now);
    expect(events.rows).toEqual([
      {
        aggregate_id: "10000000-0000-4000-8000-000000000001",
        event_type: "identity.email-verification-requested",
        payload: {
          challengeId: "10000000-0000-4000-8000-000000000001",
        },
      },
    ]);
  });

  it("rolls back the challenge if the matching outbox write fails", async () => {
    const duplicateEventId = "20000000-0000-4000-8000-000000000001";
    const starter = new KyselyEligibleRegistrationStarter(database, {
      clock: { now: () => now },
      identifiers: sequenceIdentifiers([
        "10000000-0000-4000-8000-000000000001",
        duplicateEventId,
        "10000000-0000-4000-8000-000000000002",
        duplicateEventId,
      ]),
      passwordHasher: { hash: vi.fn().mockResolvedValue("argon2id-hash") },
      payloadProtector: {
        protect: vi
          .fn()
          .mockResolvedValueOnce({
            subjectIndex: Uint8Array.of(1),
            subjectIndexKeyVersion: 1,
            ciphertext: Uint8Array.of(2),
            algorithm: "AES-256-GCM",
            keyVersion: 1,
          })
          .mockResolvedValueOnce({
            subjectIndex: Uint8Array.of(4),
            subjectIndexKeyVersion: 1,
            ciphertext: Uint8Array.of(5),
            algorithm: "AES-256-GCM",
            keyVersion: 1,
          }),
      },
      verificationSecrets: {
        create: () => ({
          bearer: "secret-token",
          digest: Uint8Array.of(3),
          digestKeyVersion: 9,
        }),
      },
    });

    await starter.startEligibleRegistration(command);
    await expect(
      starter.startEligibleRegistration({ ...command, requestId: "request-2" }),
    ).rejects.toThrow();

    const challenges = await sql<{ count: number }>`
      select count(*)::int as count from identity.verification_challenges
    `.execute(database);
    expect(challenges.rows[0]?.count).toBe(1);
  });

  it("supersedes an earlier active challenge when the same email is submitted again", async () => {
    const starter = new KyselyEligibleRegistrationStarter(database, {
      clock: { now: () => now },
      identifiers: sequenceIdentifiers([
        "10000000-0000-4000-8000-000000000001",
        "20000000-0000-4000-8000-000000000001",
        "10000000-0000-4000-8000-000000000002",
        "20000000-0000-4000-8000-000000000002",
      ]),
      passwordHasher: { hash: vi.fn().mockResolvedValue("argon2id-hash") },
      payloadProtector: {
        protect: vi.fn().mockResolvedValue({
          subjectIndex: Uint8Array.of(1),
          subjectIndexKeyVersion: 1,
          ciphertext: Uint8Array.of(2),
          algorithm: "AES-256-GCM",
          keyVersion: 1,
        }),
      },
      verificationSecrets: {
        create: () => ({
          bearer: "secret-token",
          digest: Uint8Array.of(3),
          digestKeyVersion: 9,
        }),
      },
    });

    await starter.startEligibleRegistration(command);
    await expect(
      starter.startEligibleRegistration({ ...command, requestId: "request-2" }),
    ).resolves.toEqual({
      registrationId: "10000000-0000-4000-8000-000000000002",
    });

    const challenges = await sql<{
      challenge_id: string;
      status: string;
      superseded_at: Date | null;
    }>`
      select challenge_id, status, superseded_at
      from identity.verification_challenges
      order by created_at, challenge_id
    `.execute(database);
    expect(challenges.rows).toEqual([
      {
        challenge_id: "10000000-0000-4000-8000-000000000001",
        status: "SUPERSEDED",
        superseded_at: now,
      },
      {
        challenge_id: "10000000-0000-4000-8000-000000000002",
        status: "ACTIVE",
        superseded_at: null,
      },
    ]);
  });
});

const sequenceIdentifiers = (values: string[]) => ({
  next: vi.fn(() => {
    const value = values.shift();
    if (!value) throw new Error("No test identifier available");
    return value;
  }),
});
