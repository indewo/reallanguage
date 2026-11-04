import { describe, expect, it } from "vitest";

import { AdminCryptography } from "./admin-cryptography.js";

const cryptography = () =>
  new AdminCryptography({
    encryptionKey: {
      key: Uint8Array.from({ length: 32 }, () => 7),
      version: 2,
    },
    digestKey: { key: Uint8Array.from({ length: 32 }, () => 9), version: 3 },
  });

describe("AdminCryptography", () => {
  it("round-trips encrypted email and TOTP values with separate purposes", async () => {
    const service = cryptography();
    const email = service.protectEmail("indewo@outlook.com");
    const totp = service.protectTotpSecret("BASE32SECRET");

    await expect(service.unprotectEmail(email)).resolves.toBe(
      "indewo@outlook.com",
    );
    await expect(service.unprotectTotpSecret(totp)).resolves.toBe(
      "BASE32SECRET",
    );
    await expect(service.unprotectEmail(totp)).rejects.toThrow();
    expect(Buffer.from(email.ciphertext).toString("utf8")).not.toContain(
      "indewo@outlook.com",
    );
  });

  it("creates stable purpose-separated digests", () => {
    const service = cryptography();

    expect(service.indexEmail(" INDEWO@OUTLOOK.COM ")).toEqual(
      service.indexEmail("indewo@outlook.com"),
    );
    expect(service.indexEmail("indewo@outlook.com").digest).not.toEqual(
      service.digestSession("indewo@outlook.com").digest,
    );
  });

  it("returns a random bearer while retaining only its digest", () => {
    const service = cryptography();
    const first = service.createSession();
    const second = service.createSession();

    expect(first.bearer).not.toBe(second.bearer);
    expect(first.digest).toEqual(service.digestSession(first.bearer).digest);
    expect(Buffer.from(first.digest).toString("base64url")).not.toBe(
      first.bearer,
    );
  });
});
