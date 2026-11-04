import {
  createCipheriv,
  createDecipheriv,
  createHmac,
  randomBytes,
} from "node:crypto";

export type AdminVersionedKey = Readonly<{
  key: Uint8Array;
  version: number;
}>;

export type ProtectedAdminValue = Readonly<{
  ciphertext: Uint8Array;
  algorithm: "AES-256-GCM/v1";
  keyVersion: number;
}>;

export type AdminDigest = Readonly<{
  digest: Uint8Array;
  keyVersion: number;
}>;

const EMAIL_PURPOSE = "reallanguage:admin:email:v1";
const TOTP_PURPOSE = "reallanguage:admin:totp-secret:v1";
const EMAIL_INDEX_PURPOSE = "reallanguage:admin:email-index:v1";
const SESSION_PURPOSE = "reallanguage:admin:session:v1";
const ALGORITHM = "AES-256-GCM/v1" as const;
const IV_BYTES = 12;
const AUTH_TAG_BYTES = 16;

export class AdminCryptography {
  private readonly encryptionKey: AdminVersionedKey;
  private readonly digestKey: AdminVersionedKey;

  public constructor(config: {
    encryptionKey: AdminVersionedKey;
    digestKey: AdminVersionedKey;
  }) {
    this.encryptionKey = validatedKey(config.encryptionKey, "encryption");
    this.digestKey = validatedKey(config.digestKey, "digest");
  }

  public protectEmail(value: string): ProtectedAdminValue {
    return this.protect(value, EMAIL_PURPOSE);
  }

  public protectTotpSecret(value: string): ProtectedAdminValue {
    return this.protect(value, TOTP_PURPOSE);
  }

  public async unprotectEmail(value: ProtectedAdminValue): Promise<string> {
    return this.unprotect(value, EMAIL_PURPOSE);
  }

  public async unprotectTotpSecret(
    value: ProtectedAdminValue,
  ): Promise<string> {
    return this.unprotect(value, TOTP_PURPOSE);
  }

  public indexEmail(email: string): AdminDigest {
    return this.digest(EMAIL_INDEX_PURPOSE, email.trim().toLowerCase());
  }

  public digestSession(bearer: string): AdminDigest {
    return this.digest(SESSION_PURPOSE, bearer);
  }

  public createSession(): Readonly<
    { bearer: string } & AdminDigest & { keyVersion: number }
  > {
    const bearer = randomBytes(32).toString("base64url");
    return { bearer, ...this.digestSession(bearer) };
  }

  private protect(value: string, purpose: string): ProtectedAdminValue {
    if (!value) throw new Error("Admin protected value must not be empty");
    const initializationVector = randomBytes(IV_BYTES);
    const cipher = createCipheriv(
      "aes-256-gcm",
      this.encryptionKey.key,
      initializationVector,
    );
    cipher.setAAD(Buffer.from(purpose, "utf8"));
    const encrypted = Buffer.concat([
      cipher.update(value, "utf8"),
      cipher.final(),
    ]);
    return {
      ciphertext: new Uint8Array(
        Buffer.concat([initializationVector, cipher.getAuthTag(), encrypted]),
      ),
      algorithm: ALGORITHM,
      keyVersion: this.encryptionKey.version,
    };
  }

  private unprotect(value: ProtectedAdminValue, purpose: string): string {
    if (
      value.algorithm !== ALGORITHM ||
      value.keyVersion !== this.encryptionKey.version ||
      value.ciphertext.byteLength <= IV_BYTES + AUTH_TAG_BYTES
    ) {
      throw new Error("Invalid protected admin value");
    }
    const contents = Buffer.from(value.ciphertext);
    const decipher = createDecipheriv(
      "aes-256-gcm",
      this.encryptionKey.key,
      contents.subarray(0, IV_BYTES),
    );
    decipher.setAAD(Buffer.from(purpose, "utf8"));
    decipher.setAuthTag(contents.subarray(IV_BYTES, IV_BYTES + AUTH_TAG_BYTES));
    return Buffer.concat([
      decipher.update(contents.subarray(IV_BYTES + AUTH_TAG_BYTES)),
      decipher.final(),
    ]).toString("utf8");
  }

  private digest(purpose: string, value: string): AdminDigest {
    if (!value) throw new Error("Admin digest value must not be empty");
    return {
      digest: new Uint8Array(
        createHmac("sha256", this.digestKey.key)
          .update(purpose, "utf8")
          .update("\0", "utf8")
          .update(value, "utf8")
          .digest(),
      ),
      keyVersion: this.digestKey.version,
    };
  }
}

const validatedKey = (value: AdminVersionedKey, label: string) => {
  if (value.key.byteLength !== 32) {
    throw new Error(`Admin ${label} key must be exactly 32 bytes`);
  }
  if (!Number.isInteger(value.version) || value.version < 1) {
    throw new Error(`Admin ${label} key version must be positive`);
  }
  return { key: Uint8Array.from(value.key), version: value.version };
};
