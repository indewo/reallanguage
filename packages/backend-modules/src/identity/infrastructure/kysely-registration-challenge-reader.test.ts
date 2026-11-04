import { PGlite } from "@electric-sql/pglite";
import { migrateToLatest } from "@reallanguage/database";
import { Kysely, PGliteDialect, sql } from "kysely";
import { afterEach, beforeEach, describe, expect, it } from "vitest";

import type { IdentityRegistrationDatabase } from "./kysely-eligible-registration-starter.js";
import { KyselyRegistrationChallengeReader } from "./kysely-registration-challenge-reader.js";

let database: Kysely<IdentityRegistrationDatabase>;

beforeEach(async () => {
  database = new Kysely({
    dialect: new PGliteDialect({ pglite: new PGlite() }),
  });
  await migrateToLatest(database);
  await sql`
    insert into identity.verification_challenges (
      challenge_id,
      purpose,
      subject_index,
      subject_index_key_version,
      secret_digest,
      secret_digest_key_version,
      pending_registration_ciphertext,
      payload_algorithm,
      payload_key_version,
      status,
      expires_at,
      created_at,
      updated_at
    ) values (
      '10000000-0000-4000-8000-000000000001',
      'EMAIL_VERIFICATION',
      decode('01', 'hex'),
      2,
      decode('02', 'hex'),
      3,
      decode('03', 'hex'),
      'AES-256-GCM/v1',
      4,
      'ACTIVE',
      '2026-09-01T00:30:00.000Z',
      '2026-09-01T00:00:00.000Z',
      '2026-09-01T00:00:00.000Z'
    )
  `.execute(database);
});

afterEach(async () => {
  await database.destroy();
});

describe("KyselyRegistrationChallengeReader", () => {
  it("maps a pending email-verification challenge", async () => {
    const reader = new KyselyRegistrationChallengeReader(database);

    await expect(
      reader.findById("10000000-0000-4000-8000-000000000001"),
    ).resolves.toEqual({
      challengeId: "10000000-0000-4000-8000-000000000001",
      accountId: null,
      status: "ACTIVE",
      expiresAt: new Date("2026-09-01T00:30:00.000Z"),
      subjectIndex: Uint8Array.of(1),
      subjectIndexKeyVersion: 2,
      secretDigest: Uint8Array.of(2),
      secretDigestKeyVersion: 3,
      protectedPayload: {
        ciphertext: Uint8Array.of(3),
        algorithm: "AES-256-GCM/v1",
        keyVersion: 4,
      },
    });
  });

  it("returns null for an unknown challenge", async () => {
    const reader = new KyselyRegistrationChallengeReader(database);

    await expect(
      reader.findById("10000000-0000-4000-8000-000000000099"),
    ).resolves.toBeNull();
  });
});
