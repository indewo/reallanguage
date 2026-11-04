import { describe, expect, it } from "vitest";

import { HmacVerificationSecretFactory } from "./hmac-verification-secret-factory.js";
import { HmacVerificationSecretVerifier } from "./hmac-verification-secret-verifier.js";

const key = Uint8Array.from({ length: 32 }, (_, index) => index + 1);

describe("HmacVerificationSecretVerifier", () => {
  it("verifies a bearer using the digest key version", () => {
    const factory = new HmacVerificationSecretFactory(
      { key, version: 4 },
      {
        bytes: () => Uint8Array.from({ length: 32 }, (_, index) => 100 + index),
      },
    );
    const secret = factory.create();
    const verifier = new HmacVerificationSecretVerifier([{ key, version: 4 }]);

    expect(verifier.verify(secret)).toBe(true);
    expect(verifier.verify({ ...secret, bearer: `${secret.bearer}x` })).toBe(
      false,
    );
  });

  it("fails closed for unknown versions and malformed digests", () => {
    const verifier = new HmacVerificationSecretVerifier([{ key, version: 4 }]);

    expect(
      verifier.verify({
        bearer: "secret",
        digest: Uint8Array.of(1),
        digestKeyVersion: 4,
      }),
    ).toBe(false);
    expect(
      verifier.verify({
        bearer: "secret",
        digest: new Uint8Array(32),
        digestKeyVersion: 99,
      }),
    ).toBe(false);
  });

  it("rejects duplicate, short, or invalid-version keys", () => {
    expect(
      () =>
        new HmacVerificationSecretVerifier([
          { key, version: 1 },
          { key, version: 1 },
        ]),
    ).toThrow("unique");
    expect(
      () =>
        new HmacVerificationSecretVerifier([
          { key: Uint8Array.of(1), version: 1 },
        ]),
    ).toThrow("at least 32 bytes");
    expect(
      () => new HmacVerificationSecretVerifier([{ key, version: 0 }]),
    ).toThrow("positive integer");
  });
});
