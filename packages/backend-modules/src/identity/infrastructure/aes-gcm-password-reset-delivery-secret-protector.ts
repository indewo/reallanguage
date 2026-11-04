import { createCipheriv, createDecipheriv, randomBytes } from "node:crypto";

import type { VersionedEncryptionKey } from "./aes-gcm-registration-payload-protector.js";
import type { CryptographicEntropy } from "./hmac-verification-secret-factory.js";
import type { PasswordResetDeliverySecretProtector } from "./kysely-password-reset-store.js";

export type PasswordResetDeliverySecretProtectionConfig = Readonly<{
  activeEncryptionKey: VersionedEncryptionKey;
  decryptionKeys: readonly VersionedEncryptionKey[];
}>;

export type ProtectedPasswordResetDeliverySecret = Readonly<{
  ciphertext: Uint8Array;
  algorithm: string;
  keyVersion: number;
}>;

const ALGORITHM = "AES-256-GCM/v1";
const KEY_BYTES = 32;
const IV_BYTES = 12;
const AUTH_TAG_BYTES = 16;
const ASSOCIATED_DATA = Buffer.from(
  "reallanguage:identity:password-reset-delivery-secret:v1",
  "utf8",
);
const systemEntropy: CryptographicEntropy = {
  bytes: (size) => randomBytes(size),
};

export class AesGcmPasswordResetDeliverySecretProtector implements PasswordResetDeliverySecretProtector {
  private readonly activeKey: VersionedEncryptionKey;
  private readonly keys = new Map<number, Uint8Array>();

  public constructor(
    config: PasswordResetDeliverySecretProtectionConfig,
    private readonly entropy: CryptographicEntropy = systemEntropy,
  ) {
    this.activeKey = validate(config.activeEncryptionKey);
    this.keys.set(this.activeKey.version, this.activeKey.key);
    for (const source of config.decryptionKeys) {
      const key = validate(source);
      if (this.keys.has(key.version)) {
        throw new Error(
          "Password reset encryption key versions must be unique",
        );
      }
      this.keys.set(key.version, key.key);
    }
  }

  public async protect(
    bearer: string,
  ): Promise<ProtectedPasswordResetDeliverySecret> {
    const initializationVector = this.entropy.bytes(IV_BYTES);
    if (initializationVector.byteLength !== IV_BYTES) {
      throw new Error("AES-GCM entropy source must return exactly 12 bytes");
    }
    const cipher = createCipheriv(
      "aes-256-gcm",
      this.activeKey.key,
      initializationVector,
    );
    cipher.setAAD(ASSOCIATED_DATA);
    const encrypted = Buffer.concat([
      cipher.update(bearer, "utf8"),
      cipher.final(),
    ]);
    return {
      ciphertext: new Uint8Array(
        Buffer.concat([
          Buffer.from(initializationVector),
          cipher.getAuthTag(),
          encrypted,
        ]),
      ),
      algorithm: ALGORITHM,
      keyVersion: this.activeKey.version,
    };
  }

  public async unprotect(
    protectedSecret: ProtectedPasswordResetDeliverySecret,
  ): Promise<string> {
    if (protectedSecret.algorithm !== ALGORITHM) {
      throw new Error("Unsupported password reset delivery algorithm");
    }
    const key = this.keys.get(protectedSecret.keyVersion);
    if (!key) throw new Error("Unknown password reset encryption key version");
    if (protectedSecret.ciphertext.byteLength <= IV_BYTES + AUTH_TAG_BYTES) {
      throw new Error("Invalid password reset delivery ciphertext");
    }
    const ciphertext = Buffer.from(protectedSecret.ciphertext);
    const decipher = createDecipheriv(
      "aes-256-gcm",
      key,
      ciphertext.subarray(0, IV_BYTES),
    );
    decipher.setAAD(ASSOCIATED_DATA);
    decipher.setAuthTag(
      ciphertext.subarray(IV_BYTES, IV_BYTES + AUTH_TAG_BYTES),
    );
    return Buffer.concat([
      decipher.update(ciphertext.subarray(IV_BYTES + AUTH_TAG_BYTES)),
      decipher.final(),
    ]).toString("utf8");
  }
}

const validate = (source: VersionedEncryptionKey): VersionedEncryptionKey => {
  if (source.key.byteLength !== KEY_BYTES) {
    throw new Error("Password reset encryption key must be exactly 32 bytes");
  }
  if (!Number.isInteger(source.version) || source.version < 1) {
    throw new Error(
      "Password reset encryption key version must be a positive integer",
    );
  }
  return { key: Uint8Array.from(source.key), version: source.version };
};
