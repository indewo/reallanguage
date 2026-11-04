import { createHmac } from "node:crypto";
import { describe, expect, it } from "vitest";

import { HmacVerificationSecretFactory } from "./hmac-verification-secret-factory.js";

describe("HmacVerificationSecretFactory", () => {
  const digestKey = Uint8Array.from({ length: 32 }, (_, index) => index + 1);

  it("creates a 256-bit bearer and stores only its keyed digest", () => {
    const entropy = Uint8Array.from({ length: 32 }, (_, index) => index);
    const factory = new HmacVerificationSecretFactory(
      { key: digestKey, version: 4 },
      { bytes: () => entropy },
    );

    const result = factory.create();
    const expectedDigest = createHmac("sha256", digestKey)
      .update(result.bearer, "utf8")
      .digest();

    expect(Buffer.from(result.bearer, "base64url")).toEqual(
      Buffer.from(entropy),
    );
    expect(result.digest).toEqual(new Uint8Array(expectedDigest));
    expect(result.digestKeyVersion).toBe(4);
    expect(Buffer.from(result.digest).toString("utf8")).not.toContain(
      result.bearer,
    );
  });

  it("rejects digest keys shorter than 256 bits", () => {
    expect(
      () =>
        new HmacVerificationSecretFactory({
          key: Uint8Array.of(1, 2, 3),
          version: 1,
        }),
    ).toThrow("at least 32 bytes");
  });

  it("rejects invalid key versions and entropy sources", () => {
    expect(
      () => new HmacVerificationSecretFactory({ key: digestKey, version: 0 }),
    ).toThrow("positive integer");

    const factory = new HmacVerificationSecretFactory(
      { key: digestKey, version: 1 },
      { bytes: () => Uint8Array.of(1) },
    );
    expect(() => factory.create()).toThrow("32 bytes");
  });
});
