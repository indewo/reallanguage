import { describe, expect, it } from "vitest";

import {
  HmacPasswordResetTokenDigester,
  HmacPasswordResetTokenFactory,
} from "./hmac-password-reset-token.js";

describe("password reset token cryptography", () => {
  it("returns only a 43-character bearer and its versioned digest", () => {
    const key = { key: Uint8Array.from({ length: 32 }, () => 7), version: 3 };
    const factory = new HmacPasswordResetTokenFactory(key, {
      bytes: () => Uint8Array.from({ length: 32 }, (_, index) => index),
    });
    const issued = factory.create();

    expect(issued.bearer).toMatch(/^[A-Za-z0-9_-]{43}$/);
    expect(issued.digest).toHaveLength(32);
    expect(issued.digestKeyVersion).toBe(3);
    expect(
      new HmacPasswordResetTokenDigester(key).digest(issued.bearer),
    ).toEqual({
      digest: issued.digest,
      keyVersion: 3,
    });
  });

  it("separates keys and rejects weak key material", () => {
    expect(
      new HmacPasswordResetTokenDigester({
        key: Uint8Array.from({ length: 32 }, () => 8),
        version: 1,
      }).digest("same-bearer").digest,
    ).not.toEqual(
      new HmacPasswordResetTokenDigester({
        key: Uint8Array.from({ length: 32 }, () => 9),
        version: 1,
      }).digest("same-bearer").digest,
    );
    expect(
      () =>
        new HmacPasswordResetTokenFactory({
          key: Uint8Array.of(1),
          version: 1,
        }),
    ).toThrow();
  });
});
