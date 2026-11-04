import { describe, expect, it } from "vitest";

import { AesGcmAccountSensitiveFieldsProtector } from "./aes-gcm-account-sensitive-fields-protector.js";

const firstKey = Uint8Array.from({ length: 32 }, (_, index) => index + 1);
const secondKey = Uint8Array.from({ length: 32 }, (_, index) => 255 - index);
const initializationVector = Uint8Array.from(
  { length: 12 },
  (_, index) => index,
);

describe("AesGcmAccountSensitiveFieldsProtector", () => {
  it("round-trips email and birth year-month without plaintext storage", async () => {
    const protector = createProtector(firstKey, 1);

    const email = await protector.protectEmail("Learner@Example.com");
    const birth = await protector.protectBirthYearMonth({
      year: 2000,
      month: 1,
    });

    expect(email).toMatchObject({ algorithm: "AES-256-GCM/v1", keyVersion: 1 });
    expect(birth).toMatchObject({ algorithm: "AES-256-GCM/v1", keyVersion: 1 });
    expect(Buffer.from(email.ciphertext).toString("utf8")).not.toContain(
      "Learner@Example.com",
    );
    expect(Buffer.from(birth.ciphertext).toString("utf8")).not.toContain(
      "2000-01",
    );
    await expect(protector.unprotectEmail(email)).resolves.toBe(
      "Learner@Example.com",
    );
    await expect(protector.unprotectBirthYearMonth(birth)).resolves.toEqual({
      year: 2000,
      month: 1,
    });
  });

  it("binds ciphertext to its field purpose", async () => {
    const protector = createProtector(firstKey, 1);
    const email = await protector.protectEmail("Learner@Example.com");

    await expect(protector.unprotectBirthYearMonth(email)).rejects.toThrow();
  });

  it("decrypts retained old key versions during rotation", async () => {
    const oldProtector = createProtector(firstKey, 1);
    const oldEmail = await oldProtector.protectEmail("learner@example.com");
    const rotatedProtector = new AesGcmAccountSensitiveFieldsProtector(
      {
        activeEncryptionKey: { key: secondKey, version: 2 },
        decryptionKeys: [{ key: firstKey, version: 1 }],
      },
      { bytes: () => initializationVector },
    );

    await expect(rotatedProtector.unprotectEmail(oldEmail)).resolves.toBe(
      "learner@example.com",
    );
    await expect(
      rotatedProtector.protectEmail("learner@example.com"),
    ).resolves.toMatchObject({ keyVersion: 2 });
  });

  it("rejects tampering and unknown metadata", async () => {
    const protector = createProtector(firstKey, 1);
    const email = await protector.protectEmail("learner@example.com");
    const tampered = Uint8Array.from(email.ciphertext);
    tampered[tampered.length - 1] = tampered[tampered.length - 1]! ^ 1;

    await expect(
      protector.unprotectEmail({ ...email, ciphertext: tampered }),
    ).rejects.toThrow();
    await expect(
      protector.unprotectEmail({ ...email, algorithm: "AES-CBC" }),
    ).rejects.toThrow("algorithm");
    await expect(
      protector.unprotectEmail({ ...email, keyVersion: 99 }),
    ).rejects.toThrow("key version");
  });

  it("rejects invalid encryption keys and initialization vectors", async () => {
    expect(
      () =>
        new AesGcmAccountSensitiveFieldsProtector({
          activeEncryptionKey: { key: Uint8Array.of(1), version: 1 },
          decryptionKeys: [],
        }),
    ).toThrow("32 bytes");

    const protector = new AesGcmAccountSensitiveFieldsProtector(
      {
        activeEncryptionKey: { key: firstKey, version: 1 },
        decryptionKeys: [],
      },
      { bytes: () => Uint8Array.of(1) },
    );
    await expect(protector.protectEmail("learner@example.com")).rejects.toThrow(
      "12 bytes",
    );
  });
});

const createProtector = (key: Uint8Array, version: number) =>
  new AesGcmAccountSensitiveFieldsProtector(
    {
      activeEncryptionKey: { key, version },
      decryptionKeys: [],
    },
    { bytes: () => initializationVector },
  );
