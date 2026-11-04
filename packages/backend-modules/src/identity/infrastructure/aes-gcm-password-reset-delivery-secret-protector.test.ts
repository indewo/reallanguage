import { describe, expect, it } from "vitest";

import { AesGcmPasswordResetDeliverySecretProtector } from "./aes-gcm-password-reset-delivery-secret-protector.js";

describe("password reset delivery secret protection", () => {
  it("stores an authenticated ciphertext and supports configured key rotation", async () => {
    const oldKey = {
      key: Uint8Array.from({ length: 32 }, () => 3),
      version: 1,
    };
    const nextKey = {
      key: Uint8Array.from({ length: 32 }, () => 4),
      version: 2,
    };
    const oldProtector = new AesGcmPasswordResetDeliverySecretProtector(
      { activeEncryptionKey: oldKey, decryptionKeys: [] },
      { bytes: () => Uint8Array.from({ length: 12 }, () => 5) },
    );
    const protectedSecret = await oldProtector.protect("verification-bearer");

    expect(
      Buffer.from(protectedSecret.ciphertext).toString("utf8"),
    ).not.toContain("verification-bearer");
    await expect(
      new AesGcmPasswordResetDeliverySecretProtector(
        { activeEncryptionKey: nextKey, decryptionKeys: [oldKey] },
        { bytes: () => Uint8Array.from({ length: 12 }, () => 6) },
      ).unprotect(protectedSecret),
    ).resolves.toBe("verification-bearer");
  });
});
