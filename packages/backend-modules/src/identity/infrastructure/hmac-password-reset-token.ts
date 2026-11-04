import { createHmac, randomBytes } from "node:crypto";

import type {
  CryptographicEntropy,
  VersionedDigestKey,
} from "./hmac-verification-secret-factory.js";
import type {
  PasswordResetTokenDigester,
  PasswordResetTokenFactory,
} from "./kysely-password-reset-store.js";

const PURPOSE = "reallanguage:identity:password-reset-grant:v1";
const TOKEN_BYTES = 32;
const MINIMUM_KEY_BYTES = 32;
const systemEntropy: CryptographicEntropy = {
  bytes: (size) => randomBytes(size),
};

export class HmacPasswordResetTokenFactory implements PasswordResetTokenFactory {
  private readonly key: VersionedDigestKey;

  public constructor(
    source: VersionedDigestKey,
    private readonly entropy: CryptographicEntropy = systemEntropy,
  ) {
    this.key = validate(source);
  }

  public create() {
    const bytes = this.entropy.bytes(TOKEN_BYTES);
    if (bytes.byteLength !== TOKEN_BYTES) {
      throw new Error(
        "Password reset entropy source must return exactly 32 bytes",
      );
    }
    const bearer = Buffer.from(bytes).toString("base64url");
    return {
      bearer,
      digest: digest(this.key.key, bearer),
      digestKeyVersion: this.key.version,
    };
  }
}

export class HmacPasswordResetTokenDigester implements PasswordResetTokenDigester {
  private readonly key: VersionedDigestKey;

  public constructor(source: VersionedDigestKey) {
    this.key = validate(source);
  }

  public digest(bearer: string) {
    return {
      digest: digest(this.key.key, bearer),
      keyVersion: this.key.version,
    };
  }
}

const digest = (key: Uint8Array, bearer: string): Uint8Array =>
  new Uint8Array(
    createHmac("sha256", key)
      .update(PURPOSE, "utf8")
      .update("\0", "utf8")
      .update(bearer, "utf8")
      .digest(),
  );

const validate = (source: VersionedDigestKey): VersionedDigestKey => {
  if (source.key.byteLength < MINIMUM_KEY_BYTES) {
    throw new Error("Password reset digest key must be at least 32 bytes");
  }
  if (!Number.isInteger(source.version) || source.version < 1) {
    throw new Error(
      "Password reset digest key version must be a positive integer",
    );
  }
  return { key: Uint8Array.from(source.key), version: source.version };
};
