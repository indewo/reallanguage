import { PGlite } from "@electric-sql/pglite";
import { migrateToLatest } from "@reallanguage/database";
import { Kysely, PGliteDialect, sql } from "kysely";
import { afterEach, describe, expect, it, vi } from "vitest";

import { StartRegistration } from "./application/start-registration.js";
import { VerifyRegistration } from "./application/verify-registration.js";
import { PasswordPolicy } from "./domain/password-policy.js";
import { AesGcmAccountSensitiveFieldsProtector } from "./infrastructure/aes-gcm-account-sensitive-fields-protector.js";
import { AesGcmRegistrationPayloadProtector } from "./infrastructure/aes-gcm-registration-payload-protector.js";
import { Argon2PasswordHasher } from "./infrastructure/argon2-password-hasher.js";
import { HmacVerificationSecretFactory } from "./infrastructure/hmac-verification-secret-factory.js";
import { HmacVerificationSecretVerifier } from "./infrastructure/hmac-verification-secret-verifier.js";
import {
  KyselyEligibleRegistrationStarter,
  type IdentityRegistrationDatabase,
} from "./infrastructure/kysely-eligible-registration-starter.js";
import { KyselyRegistrationChallengeReader } from "./infrastructure/kysely-registration-challenge-reader.js";
import { KyselyVerifiedAccountCreator } from "./infrastructure/kysely-verified-account-creator.js";

let database: Kysely<IdentityRegistrationDatabase> | undefined;

afterEach(async () => {
  await database?.destroy();
  database = undefined;
});

describe("registration flow", () => {
  it("creates no account until the email bearer is verified", async () => {
    database = new Kysely({
      dialect: new PGliteDialect({ pglite: new PGlite() }),
    });
    await migrateToLatest(database);

    const now = new Date("2026-09-01T00:00:00.000Z");
    const encryptionKey = Uint8Array.from(
      { length: 32 },
      (_, index) => index + 1,
    );
    const indexKey = Uint8Array.from({ length: 32 }, (_, index) => 255 - index);
    const digestKey = Uint8Array.from(
      { length: 32 },
      (_, index) => 100 + index,
    );
    const secretEntropy = Uint8Array.from({ length: 32 }, (_, index) => index);
    const ivEntropy = {
      bytes: () => Uint8Array.from({ length: 12 }, (_, index) => index),
    };
    const payloadProtector = new AesGcmRegistrationPayloadProtector(
      {
        activeEncryptionKey: { key: encryptionKey, version: 1 },
        decryptionKeys: [],
        emailIndexKey: { key: indexKey, version: 1 },
        canonicalizeEmail: (email) => email.trim().toLowerCase(),
      },
      ivEntropy,
    );
    const accountFields = new AesGcmAccountSensitiveFieldsProtector(
      {
        activeEncryptionKey: { key: encryptionKey, version: 1 },
        decryptionKeys: [],
      },
      ivEntropy,
    );
    const passwordHasher = new Argon2PasswordHasher();
    const identifiers = sequenceIdentifiers([
      "10000000-0000-4000-8000-000000000001",
      "20000000-0000-4000-8000-000000000001",
      "30000000-0000-4000-8000-000000000001",
    ]);
    const secretFactory = new HmacVerificationSecretFactory(
      { key: digestKey, version: 1 },
      { bytes: () => secretEntropy },
    );
    const starter = new KyselyEligibleRegistrationStarter(database, {
      clock: { now: () => now },
      identifiers,
      passwordHasher,
      payloadProtector,
      verificationSecrets: secretFactory,
    });
    const startRegistration = new StartRegistration(
      { current: () => ({ year: 2026, month: 9 }) },
      new PasswordPolicy({ isBlocked: vi.fn().mockResolvedValue(false) }),
      starter,
    );
    const password = "a sufficiently long password";

    const started = await startRegistration.execute({
      requestId: "request-1",
      email: "Learner@Example.com",
      password,
      birthYearMonth: { year: 2000, month: 1 },
      acceptedTerms: true,
      acceptedPrivacyPolicy: true,
    });
    expect(started).toEqual({
      status: "EMAIL_VERIFICATION_REQUIRED",
      registrationId: "10000000-0000-4000-8000-000000000001",
    });
    const beforeVerification = await sql<{ count: number }>`
      select count(*)::int as count from identity.accounts
    `.execute(database);
    expect(beforeVerification.rows[0]?.count).toBe(0);

    const verifyRegistration = new VerifyRegistration(
      { now: () => new Date("2026-09-01T00:05:00.000Z") },
      new KyselyRegistrationChallengeReader(database),
      new HmacVerificationSecretVerifier([{ key: digestKey, version: 1 }]),
      new KyselyVerifiedAccountCreator(database, {
        identifiers,
        payloadProtector,
        accountFields,
      }),
    );
    const bearer = Buffer.from(secretEntropy).toString("base64url");

    await expect(
      verifyRegistration.execute({
        registrationId: "10000000-0000-4000-8000-000000000001",
        bearer,
      }),
    ).resolves.toEqual({
      status: "ACCOUNT_CREATED",
      accountId: "30000000-0000-4000-8000-000000000001",
    });

    const stored = await sql<{
      email_ciphertext: Uint8Array;
      email_algorithm: string;
      email_key_version: number;
      password_hash: string;
    }>`
      select email_ciphertext, email_algorithm, email_key_version, password_hash
      from identity.auth_identities
    `.execute(database);
    expect(
      await passwordHasher.verify(stored.rows[0]!.password_hash, password),
    ).toBe(true);
    await expect(
      accountFields.unprotectEmail({
        ciphertext: stored.rows[0]!.email_ciphertext,
        algorithm: stored.rows[0]!.email_algorithm,
        keyVersion: stored.rows[0]!.email_key_version,
      }),
    ).resolves.toBe("Learner@Example.com");
  });
});

const sequenceIdentifiers = (values: string[]) => ({
  next: () => {
    const value = values.shift();
    if (!value) throw new Error("No test identifier available");
    return value;
  },
});
