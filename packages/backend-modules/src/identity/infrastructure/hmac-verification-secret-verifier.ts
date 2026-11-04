import { createHmac, timingSafeEqual } from "node:crypto";

import type { VersionedDigestKey } from "./hmac-verification-secret-factory.js";

export type VerificationSecretCandidate = Readonly<{
  bearer: string;
  digest: Uint8Array;
  digestKeyVersion: number;
}>;

const MINIMUM_DIGEST_KEY_BYTES = 32;
const SHA256_DIGEST_BYTES = 32;

export class HmacVerificationSecretVerifier {
  private readonly keys = new Map<number, Uint8Array>();

  public constructor(keys: readonly VersionedDigestKey[]) {
    for (const candidate of keys) {
      if (candidate.key.byteLength < MINIMUM_DIGEST_KEY_BYTES) {
        throw new Error("Verification digest key must be at least 32 bytes");
      }
      if (!Number.isInteger(candidate.version) || candidate.version < 1) {
        throw new Error(
          "Verification digest key version must be a positive integer",
        );
      }
      if (this.keys.has(candidate.version)) {
        throw new Error("Verification digest key versions must be unique");
      }
      this.keys.set(candidate.version, Uint8Array.from(candidate.key));
    }
  }

  public verify(candidate: VerificationSecretCandidate): boolean {
    const key = this.keys.get(candidate.digestKeyVersion);
    if (!key || candidate.digest.byteLength !== SHA256_DIGEST_BYTES) {
      return false;
    }
    const expected = createHmac("sha256", key)
      .update(candidate.bearer, "utf8")
      .digest();

    return timingSafeEqual(expected, candidate.digest);
  }
}
