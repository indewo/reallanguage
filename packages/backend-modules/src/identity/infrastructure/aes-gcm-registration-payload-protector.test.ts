import { describe, expect, it, vi } from "vitest";

import type { PendingRegistrationPayload } from "./kysely-eligible-registration-starter.js";
import { AesGcmRegistrationPayloadProtector } from "./aes-gcm-registration-payload-protector.js";
import { HmacEmailIndex } from "./hmac-session-cryptography.js";

const encryptionKey = Uint8Array.from({ length: 32 }, (_, index) => index + 1);
const indexKey = Uint8Array.from({ length: 32 }, (_, index) => 255 - index);
const initializationVector = Uint8Array.from(
  { length: 12 },
  (_, index) => index,
);
const payload: PendingRegistrationPayload = {
  email: " Learner@Example.com ",
  passwordHash: "$argon2id$not-a-real-hash",
  birthYearMonth: { year: 2000, month: 1 },
  acceptedTerms: true,
  acceptedPrivacyPolicy: true,
  acceptedAt: new Date("2026-09-01T00:00:00.000Z"),
  verificationBearer: "private-verification-bearer",
};

describe("AesGcmRegistrationPayloadProtector", () => {
  it("protects the complete payload and can restore it for verification", async () => {
    const canonicalize = vi.fn((email: string) => email.trim().toLowerCase());
    const protector = createProtector(canonicalize);

    const protectedPayload = await protector.protect(payload);

    expect(protectedPayload).toMatchObject({
      algorithm: "AES-256-GCM/v1",
      keyVersion: 2,
      subjectIndexKeyVersion: 3,
    });
    expect(canonicalize).toHaveBeenCalledWith(payload.email);
    expect(
      Buffer.from(protectedPayload.ciphertext).toString("utf8"),
    ).not.toContain("Learner@Example.com");
    await expect(protector.unprotect(protectedPayload)).resolves.toEqual(
      payload,
    );
  });

  it("uses the canonical email for a stable blind index", async () => {
    const canonicalize = (email: string) => email.trim().toLowerCase();
    const protector = createProtector(canonicalize);

    const first = await protector.protect(payload);
    const second = await protector.protect({
      ...payload,
      email: "learner@example.com",
    });

    expect(first.subjectIndex).toEqual(second.subjectIndex);
    expect(first.subjectIndex).toEqual(
      new HmacEmailIndex({ key: indexKey, version: 3 }, canonicalize).create(
        payload.email,
      ).digest,
    );
  });

  it("rejects tampered ciphertext, unknown algorithms, and unknown keys", async () => {
    const protector = createProtector((email) => email);
    const protectedPayload = await protector.protect(payload);
    const tampered = Uint8Array.from(protectedPayload.ciphertext);
    tampered[tampered.length - 1] = tampered[tampered.length - 1]! ^ 1;

    await expect(
      protector.unprotect({ ...protectedPayload, ciphertext: tampered }),
    ).rejects.toThrow();
    await expect(
      protector.unprotect({ ...protectedPayload, algorithm: "AES-CBC" }),
    ).rejects.toThrow("algorithm");
    await expect(
      protector.unprotect({ ...protectedPayload, keyVersion: 99 }),
    ).rejects.toThrow("key version");
  });

  it("rejects invalid key and entropy configuration", async () => {
    expect(
      () =>
        new AesGcmRegistrationPayloadProtector({
          activeEncryptionKey: { key: Uint8Array.of(1), version: 1 },
          decryptionKeys: [],
          emailIndexKey: { key: indexKey, version: 1 },
          canonicalizeEmail: (email) => email,
        }),
    ).toThrow("32 bytes");

    const protector = new AesGcmRegistrationPayloadProtector(
      {
        activeEncryptionKey: { key: encryptionKey, version: 1 },
        decryptionKeys: [],
        emailIndexKey: { key: indexKey, version: 1 },
        canonicalizeEmail: (email) => email,
      },
      { bytes: () => Uint8Array.of(1) },
    );
    await expect(protector.protect(payload)).rejects.toThrow("12 bytes");
  });
});

const createProtector = (canonicalizeEmail: (email: string) => string) =>
  new AesGcmRegistrationPayloadProtector(
    {
      activeEncryptionKey: { key: encryptionKey, version: 2 },
      decryptionKeys: [],
      emailIndexKey: { key: indexKey, version: 3 },
      canonicalizeEmail,
    },
    { bytes: () => initializationVector },
  );
