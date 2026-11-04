import { createCipheriv, createDecipheriv, randomBytes } from "node:crypto";

import type { CryptographicEntropy } from "./hmac-verification-secret-factory.js";
import type {
  PendingRegistrationPayload,
  RegistrationPayloadProtector,
} from "./kysely-eligible-registration-starter.js";
import { HmacEmailIndex } from "./hmac-session-cryptography.js";

export type VersionedEncryptionKey = Readonly<{
  key: Uint8Array;
  version: number;
}>;

export type RegistrationPayloadProtectionConfig = Readonly<{
  activeEncryptionKey: VersionedEncryptionKey;
  decryptionKeys: readonly VersionedEncryptionKey[];
  emailIndexKey: VersionedEncryptionKey;
  canonicalizeEmail(email: string): string;
}>;

export type ProtectedRegistrationPayload = Readonly<{
  subjectIndex: Uint8Array;
  subjectIndexKeyVersion: number;
  ciphertext: Uint8Array;
  algorithm: string;
  keyVersion: number;
}>;

const ALGORITHM = "AES-256-GCM/v1";
const KEY_BYTES = 32;
const IV_BYTES = 12;
const AUTH_TAG_BYTES = 16;
const ASSOCIATED_DATA = Buffer.from(
  "reallanguage:identity:pending-registration:v1",
  "utf8",
);
const systemEntropy: CryptographicEntropy = {
  bytes: (size) => randomBytes(size),
};

export class AesGcmRegistrationPayloadProtector implements RegistrationPayloadProtector {
  private readonly activeKey: VersionedEncryptionKey;
  private readonly decryptionKeys = new Map<number, Uint8Array>();
  private readonly emailIndexKey: VersionedEncryptionKey;
  private readonly emailIndex: HmacEmailIndex;

  public constructor(
    private readonly config: RegistrationPayloadProtectionConfig,
    private readonly entropy: CryptographicEntropy = systemEntropy,
  ) {
    this.activeKey = copyAndValidateEncryptionKey(
      config.activeEncryptionKey,
      "Active encryption key",
    );
    this.emailIndexKey = copyAndValidateIndexKey(config.emailIndexKey);
    this.emailIndex = new HmacEmailIndex(
      this.emailIndexKey,
      config.canonicalizeEmail,
    );
    this.decryptionKeys.set(this.activeKey.version, this.activeKey.key);

    for (const key of config.decryptionKeys) {
      const validated = copyAndValidateEncryptionKey(key, "Decryption key");
      if (this.decryptionKeys.has(validated.version)) {
        throw new Error("Registration encryption key versions must be unique");
      }
      this.decryptionKeys.set(validated.version, validated.key);
    }
  }

  public async protect(
    payload: PendingRegistrationPayload,
  ): Promise<ProtectedRegistrationPayload> {
    const initializationVector = this.entropy.bytes(IV_BYTES);
    if (initializationVector.byteLength !== IV_BYTES) {
      throw new Error("AES-GCM entropy source must return exactly 12 bytes");
    }

    const subjectIndex = this.emailIndex.create(payload.email).digest;
    const plaintext = Buffer.from(
      JSON.stringify({
        ...payload,
        acceptedAt: payload.acceptedAt.toISOString(),
      }),
      "utf8",
    );
    const cipher = createCipheriv(
      "aes-256-gcm",
      this.activeKey.key,
      initializationVector,
    );
    cipher.setAAD(ASSOCIATED_DATA);
    const encrypted = Buffer.concat([cipher.update(plaintext), cipher.final()]);
    const authenticationTag = cipher.getAuthTag();

    return {
      subjectIndex,
      subjectIndexKeyVersion: this.emailIndexKey.version,
      ciphertext: new Uint8Array(
        Buffer.concat([
          Buffer.from(initializationVector),
          authenticationTag,
          encrypted,
        ]),
      ),
      algorithm: ALGORITHM,
      keyVersion: this.activeKey.version,
    };
  }

  public async unprotect(
    protectedPayload: Pick<
      ProtectedRegistrationPayload,
      "ciphertext" | "algorithm" | "keyVersion"
    >,
  ): Promise<PendingRegistrationPayload> {
    if (protectedPayload.algorithm !== ALGORITHM) {
      throw new Error("Unsupported registration payload algorithm");
    }
    const key = this.decryptionKeys.get(protectedPayload.keyVersion);
    if (!key) {
      throw new Error("Unknown registration payload key version");
    }
    if (protectedPayload.ciphertext.byteLength <= IV_BYTES + AUTH_TAG_BYTES) {
      throw new Error("Invalid registration payload ciphertext");
    }

    const ciphertext = Buffer.from(protectedPayload.ciphertext);
    const initializationVector = ciphertext.subarray(0, IV_BYTES);
    const authenticationTag = ciphertext.subarray(
      IV_BYTES,
      IV_BYTES + AUTH_TAG_BYTES,
    );
    const encrypted = ciphertext.subarray(IV_BYTES + AUTH_TAG_BYTES);
    const decipher = createDecipheriv("aes-256-gcm", key, initializationVector);
    decipher.setAAD(ASSOCIATED_DATA);
    decipher.setAuthTag(authenticationTag);
    const plaintext = Buffer.concat([
      decipher.update(encrypted),
      decipher.final(),
    ]);

    return parsePendingRegistrationPayload(
      JSON.parse(plaintext.toString("utf8")) as unknown,
    );
  }
}

const copyAndValidateEncryptionKey = (
  source: VersionedEncryptionKey,
  label: string,
): VersionedEncryptionKey => {
  if (source.key.byteLength !== KEY_BYTES) {
    throw new Error(`${label} must be exactly 32 bytes`);
  }
  validateKeyVersion(source.version, label);
  return { key: Uint8Array.from(source.key), version: source.version };
};

const copyAndValidateIndexKey = (
  source: VersionedEncryptionKey,
): VersionedEncryptionKey => {
  if (source.key.byteLength < KEY_BYTES) {
    throw new Error("Email index key must be at least 32 bytes");
  }
  validateKeyVersion(source.version, "Email index key");
  return { key: Uint8Array.from(source.key), version: source.version };
};

const validateKeyVersion = (version: number, label: string): void => {
  if (!Number.isInteger(version) || version < 1) {
    throw new Error(`${label} version must be a positive integer`);
  }
};

const parsePendingRegistrationPayload = (
  value: unknown,
): PendingRegistrationPayload => {
  if (!isRecord(value) || !isRecord(value.birthYearMonth)) {
    throw new Error("Invalid pending registration payload");
  }
  const acceptedAt =
    typeof value.acceptedAt === "string" ? new Date(value.acceptedAt) : null;
  if (
    typeof value.email !== "string" ||
    typeof value.passwordHash !== "string" ||
    typeof value.verificationBearer !== "string" ||
    value.acceptedTerms !== true ||
    value.acceptedPrivacyPolicy !== true ||
    !Number.isInteger(value.birthYearMonth.year) ||
    !Number.isInteger(value.birthYearMonth.month) ||
    Number(value.birthYearMonth.month) < 1 ||
    Number(value.birthYearMonth.month) > 12 ||
    !acceptedAt ||
    Number.isNaN(acceptedAt.getTime())
  ) {
    throw new Error("Invalid pending registration payload");
  }

  return {
    email: value.email,
    passwordHash: value.passwordHash,
    birthYearMonth: {
      year: Number(value.birthYearMonth.year),
      month: Number(value.birthYearMonth.month),
    },
    acceptedTerms: true,
    acceptedPrivacyPolicy: true,
    acceptedAt,
    verificationBearer: value.verificationBearer,
  };
};

const isRecord = (value: unknown): value is Record<string, unknown> =>
  typeof value === "object" && value !== null;
