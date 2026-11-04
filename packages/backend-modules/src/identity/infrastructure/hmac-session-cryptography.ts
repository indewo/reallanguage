import { createHmac, randomBytes } from "node:crypto";

import type {
  CryptographicEntropy,
  VersionedDigestKey,
} from "./hmac-verification-secret-factory.js";
import type { LoginEmailIndex } from "./kysely-credential-verifier.js";
import type {
  DeviceIdentifierDigester,
  SessionSecretFactory,
} from "./kysely-session-issuer.js";
import type { SessionBearerDigester } from "./kysely-session-lifecycle-repository.js";

const MINIMUM_KEY_BYTES = 32;
const SESSION_ENTROPY_BYTES = 64;
const TOKEN_BYTES = 32;
const REFRESH_TOKEN_PURPOSE = "reallanguage:identity:refresh-token:v1";
const ACCESS_TOKEN_PURPOSE = "reallanguage:identity:access-token:v1";
const systemEntropy: CryptographicEntropy = {
  bytes: (size) => randomBytes(size),
};

export class HmacSessionSecretFactory implements SessionSecretFactory {
  private readonly key: VersionedDigestKey;

  public constructor(
    source: VersionedDigestKey,
    private readonly entropy: CryptographicEntropy = systemEntropy,
  ) {
    this.key = validateKey(source, "Session digest key");
  }

  public create() {
    const entropy = this.entropy.bytes(SESSION_ENTROPY_BYTES);
    if (entropy.byteLength !== SESSION_ENTROPY_BYTES) {
      throw new Error("Session entropy source must return exactly 64 bytes");
    }
    const accessToken = Buffer.from(entropy.subarray(0, TOKEN_BYTES)).toString(
      "base64url",
    );
    const refreshToken = Buffer.from(entropy.subarray(TOKEN_BYTES)).toString(
      "base64url",
    );
    return {
      accessToken,
      accessTokenDigest: digest(
        this.key.key,
        ACCESS_TOKEN_PURPOSE,
        accessToken,
      ),
      accessTokenDigestKeyVersion: this.key.version,
      refreshToken,
      refreshTokenDigest: digest(
        this.key.key,
        REFRESH_TOKEN_PURPOSE,
        refreshToken,
      ),
      refreshTokenDigestKeyVersion: this.key.version,
    };
  }
}

export class HmacSessionBearerDigester implements SessionBearerDigester {
  private readonly key: VersionedDigestKey;

  public constructor(source: VersionedDigestKey) {
    this.key = validateKey(source, "Session digest key");
  }

  public digest(bearer: string) {
    return {
      digest: digest(this.key.key, REFRESH_TOKEN_PURPOSE, bearer),
      keyVersion: this.key.version,
    };
  }
}

export class HmacAccessTokenDigester {
  private readonly key: VersionedDigestKey;

  public constructor(source: VersionedDigestKey) {
    this.key = validateKey(source, "Session digest key");
  }

  public digest(bearer: string) {
    return {
      digest: digest(this.key.key, ACCESS_TOKEN_PURPOSE, bearer),
      keyVersion: this.key.version,
    };
  }
}

export class HmacEmailIndex implements LoginEmailIndex {
  private readonly key: VersionedDigestKey;

  public constructor(
    source: VersionedDigestKey,
    private readonly canonicalize: (email: string) => string,
  ) {
    this.key = validateKey(source, "Email index key");
  }

  public create(email: string) {
    return {
      digest: digest(
        this.key.key,
        "reallanguage:identity:email-index:v1",
        this.canonicalize(email),
      ),
      keyVersion: this.key.version,
    };
  }
}

export class HmacDeviceIdentifierDigester implements DeviceIdentifierDigester {
  private readonly key: VersionedDigestKey;

  public constructor(source: VersionedDigestKey) {
    this.key = validateKey(source, "Device digest key");
  }

  public digest(deviceId: string) {
    return {
      digest: digest(
        this.key.key,
        "reallanguage:identity:device-id:v1",
        deviceId,
      ),
      keyVersion: this.key.version,
    };
  }
}

const digest = (key: Uint8Array, purpose: string, value: string): Uint8Array =>
  new Uint8Array(
    createHmac("sha256", key)
      .update(purpose, "utf8")
      .update("\0", "utf8")
      .update(value, "utf8")
      .digest(),
  );

const validateKey = (
  source: VersionedDigestKey,
  label: string,
): VersionedDigestKey => {
  if (source.key.byteLength < MINIMUM_KEY_BYTES) {
    throw new Error(`${label} must be at least 32 bytes`);
  }
  if (!Number.isInteger(source.version) || source.version < 1) {
    throw new Error(`${label} version must be a positive integer`);
  }
  return { key: Uint8Array.from(source.key), version: source.version };
};
