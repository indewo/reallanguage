import { createCipheriv, createDecipheriv, randomBytes } from "node:crypto";

import type { VersionedEncryptionKey } from "../../identity/infrastructure/aes-gcm-registration-payload-protector.js";
import type { ContentSafetyPayloadCipher } from "./kysely-content-safety-gateway.js";

const ALGORITHM = "AES-256-GCM/v1";
const IV_BYTES = 12;
const ASSOCIATED_DATA = Buffer.from(
  "reallanguage:safety:transient-content:v1",
  "utf8",
);

export class AesGcmContentSafetyPayloadProtector implements ContentSafetyPayloadCipher {
  private readonly key: VersionedEncryptionKey;

  public constructor(source: VersionedEncryptionKey) {
    if (source.key.byteLength !== 32) {
      throw new Error("Safety payload encryption key must be exactly 32 bytes");
    }
    if (!Number.isInteger(source.version) || source.version < 1) {
      throw new Error("Safety payload encryption key version must be positive");
    }
    this.key = { key: Uint8Array.from(source.key), version: source.version };
  }

  public async protect(text: string) {
    const initializationVector = randomBytes(IV_BYTES);
    const cipher = createCipheriv(
      "aes-256-gcm",
      this.key.key,
      initializationVector,
    );
    cipher.setAAD(ASSOCIATED_DATA);
    const encrypted = Buffer.concat([
      cipher.update(text, "utf8"),
      cipher.final(),
    ]);
    return {
      ciphertext: new Uint8Array(
        Buffer.concat([initializationVector, cipher.getAuthTag(), encrypted]),
      ),
      algorithm: ALGORITHM,
      keyVersion: this.key.version,
    };
  }

  public async unprotect(input: {
    ciphertext: Uint8Array;
    algorithm: string;
    keyVersion: number;
  }): Promise<string> {
    if (
      input.algorithm !== ALGORITHM ||
      input.keyVersion !== this.key.version
    ) {
      throw new Error("Unsupported safety payload key or algorithm");
    }
    const payload = Buffer.from(input.ciphertext);
    if (payload.byteLength <= IV_BYTES + 16)
      throw new Error("Invalid safety payload");
    const decipher = createDecipheriv(
      "aes-256-gcm",
      this.key.key,
      payload.subarray(0, IV_BYTES),
    );
    decipher.setAAD(ASSOCIATED_DATA);
    decipher.setAuthTag(payload.subarray(IV_BYTES, IV_BYTES + 16));
    return Buffer.concat([
      decipher.update(payload.subarray(IV_BYTES + 16)),
      decipher.final(),
    ]).toString("utf8");
  }
}
