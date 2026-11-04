import { createHmac, randomBytes } from "node:crypto";

import type { VerificationSecretFactory } from "./kysely-eligible-registration-starter.js";

export interface CryptographicEntropy {
  bytes(size: number): Uint8Array;
}

export type VersionedDigestKey = Readonly<{
  key: Uint8Array;
  version: number;
}>;

const SECRET_BYTES = 32;
const MINIMUM_DIGEST_KEY_BYTES = 32;

const systemEntropy: CryptographicEntropy = {
  bytes: (size) => randomBytes(size),
};

export class HmacVerificationSecretFactory implements VerificationSecretFactory {
  private readonly digestKey: Uint8Array;

  public constructor(
    digestKey: VersionedDigestKey,
    private readonly entropy: CryptographicEntropy = systemEntropy,
  ) {
    if (digestKey.key.byteLength < MINIMUM_DIGEST_KEY_BYTES) {
      throw new Error("Verification digest key must be at least 32 bytes");
    }
    if (!Number.isInteger(digestKey.version) || digestKey.version < 1) {
      throw new Error(
        "Verification digest key version must be a positive integer",
      );
    }

    this.digestKey = Uint8Array.from(digestKey.key);
    this.digestKeyVersion = digestKey.version;
  }

  private readonly digestKeyVersion: number;

  public create(): Readonly<{
    bearer: string;
    digest: Uint8Array;
    digestKeyVersion: number;
  }> {
    const secretBytes = this.entropy.bytes(SECRET_BYTES);
    if (secretBytes.byteLength !== SECRET_BYTES) {
      throw new Error(
        "Verification entropy source must return exactly 32 bytes",
      );
    }

    const bearer = Buffer.from(secretBytes).toString("base64url");
    const digest = createHmac("sha256", this.digestKey)
      .update(bearer, "utf8")
      .digest();

    return {
      bearer,
      digest: new Uint8Array(digest),
      digestKeyVersion: this.digestKeyVersion,
    };
  }
}
