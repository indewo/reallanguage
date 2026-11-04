import { createCipheriv, createDecipheriv, randomBytes } from "node:crypto";

import type { YearMonth } from "../domain/registration-age.js";
import type { CryptographicEntropy } from "./hmac-verification-secret-factory.js";
import type {
  AccountSensitiveFieldsProtector,
  ProtectedAccountField,
} from "./kysely-verified-account-creator.js";
import type { VersionedEncryptionKey } from "./aes-gcm-registration-payload-protector.js";

export type AccountFieldProtectionConfig = Readonly<{
  activeEncryptionKey: VersionedEncryptionKey;
  decryptionKeys: readonly VersionedEncryptionKey[];
}>;

const ALGORITHM = "AES-256-GCM/v1";
const KEY_BYTES = 32;
const IV_BYTES = 12;
const AUTH_TAG_BYTES = 16;
const EMAIL_ASSOCIATED_DATA = Buffer.from(
  "reallanguage:identity:account-email:v1",
  "utf8",
);
const BIRTH_ASSOCIATED_DATA = Buffer.from(
  "reallanguage:identity:account-birth-year-month:v1",
  "utf8",
);
const systemEntropy: CryptographicEntropy = {
  bytes: (size) => randomBytes(size),
};

export class AesGcmAccountSensitiveFieldsProtector implements AccountSensitiveFieldsProtector {
  private readonly activeKey: VersionedEncryptionKey;
  private readonly decryptionKeys = new Map<number, Uint8Array>();

  public constructor(
    config: AccountFieldProtectionConfig,
    private readonly entropy: CryptographicEntropy = systemEntropy,
  ) {
    this.activeKey = copyAndValidateKey(config.activeEncryptionKey);
    this.decryptionKeys.set(this.activeKey.version, this.activeKey.key);
    for (const source of config.decryptionKeys) {
      const key = copyAndValidateKey(source);
      if (this.decryptionKeys.has(key.version)) {
        throw new Error("Account encryption key versions must be unique");
      }
      this.decryptionKeys.set(key.version, key.key);
    }
  }

  public async protectEmail(email: string): Promise<ProtectedAccountField> {
    return this.protectValue(email, EMAIL_ASSOCIATED_DATA);
  }

  public async protectBirthYearMonth(
    birthYearMonth: YearMonth,
  ): Promise<ProtectedAccountField> {
    return this.protectValue(
      `${birthYearMonth.year.toString().padStart(4, "0")}-${birthYearMonth.month
        .toString()
        .padStart(2, "0")}`,
      BIRTH_ASSOCIATED_DATA,
    );
  }

  public async unprotectEmail(field: ProtectedAccountField): Promise<string> {
    return this.unprotectValue(field, EMAIL_ASSOCIATED_DATA);
  }

  public async unprotectBirthYearMonth(
    field: ProtectedAccountField,
  ): Promise<YearMonth> {
    const plaintext = await this.unprotectValue(field, BIRTH_ASSOCIATED_DATA);
    const match = /^(\d{4})-(0[1-9]|1[0-2])$/.exec(plaintext);
    if (!match) throw new Error("Invalid protected birth year-month");
    return { year: Number(match[1]), month: Number(match[2]) };
  }

  private async protectValue(
    plaintext: string,
    associatedData: Uint8Array,
  ): Promise<ProtectedAccountField> {
    const initializationVector = this.entropy.bytes(IV_BYTES);
    if (initializationVector.byteLength !== IV_BYTES) {
      throw new Error("AES-GCM entropy source must return exactly 12 bytes");
    }
    const cipher = createCipheriv(
      "aes-256-gcm",
      this.activeKey.key,
      initializationVector,
    );
    cipher.setAAD(associatedData);
    const encrypted = Buffer.concat([
      cipher.update(plaintext, "utf8"),
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

  private async unprotectValue(
    field: ProtectedAccountField,
    associatedData: Uint8Array,
  ): Promise<string> {
    if (field.algorithm !== ALGORITHM) {
      throw new Error("Unsupported account field algorithm");
    }
    const key = this.decryptionKeys.get(field.keyVersion);
    if (!key) throw new Error("Unknown account field key version");
    if (field.ciphertext.byteLength <= IV_BYTES + AUTH_TAG_BYTES) {
      throw new Error("Invalid protected account field");
    }

    const ciphertext = Buffer.from(field.ciphertext);
    const decipher = createDecipheriv(
      "aes-256-gcm",
      key,
      ciphertext.subarray(0, IV_BYTES),
    );
    decipher.setAAD(associatedData);
    decipher.setAuthTag(
      ciphertext.subarray(IV_BYTES, IV_BYTES + AUTH_TAG_BYTES),
    );
    return Buffer.concat([
      decipher.update(ciphertext.subarray(IV_BYTES + AUTH_TAG_BYTES)),
      decipher.final(),
    ]).toString("utf8");
  }
}

const copyAndValidateKey = (
  source: VersionedEncryptionKey,
): VersionedEncryptionKey => {
  if (source.key.byteLength !== KEY_BYTES) {
    throw new Error("Account encryption key must be exactly 32 bytes");
  }
  if (!Number.isInteger(source.version) || source.version < 1) {
    throw new Error(
      "Account encryption key version must be a positive integer",
    );
  }
  return { key: Uint8Array.from(source.key), version: source.version };
};
