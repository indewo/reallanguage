import { describe, expect, it } from "vitest";

import {
  HmacDeviceIdentifierDigester,
  HmacAccessTokenDigester,
  HmacEmailIndex,
  HmacSessionBearerDigester,
  HmacSessionSecretFactory,
} from "./hmac-session-cryptography.js";

const key = Uint8Array.from({ length: 32 }, (_, index) => index + 1);

describe("session cryptography", () => {
  it("creates 256-bit opaque bearers and stores only the refresh digest", () => {
    const factory = new HmacSessionSecretFactory(
      { key, version: 4 },
      { bytes: () => Uint8Array.from({ length: 64 }, (_, index) => index) },
    );

    const created = factory.create();

    expect(created.accessToken).toMatch(/^[A-Za-z0-9_-]{43}$/);
    expect(created.refreshToken).toMatch(/^[A-Za-z0-9_-]{43}$/);
    expect(created.refreshTokenDigestKeyVersion).toBe(4);
    expect(created.accessTokenDigestKeyVersion).toBe(4);
    expect(
      new HmacAccessTokenDigester({ key, version: 4 }).digest(
        created.accessToken,
      ),
    ).toEqual({ digest: created.accessTokenDigest, keyVersion: 4 });
    expect(
      new HmacSessionBearerDigester({ key, version: 4 }).digest(
        created.refreshToken,
      ),
    ).toEqual({ digest: created.refreshTokenDigest, keyVersion: 4 });
    expect(
      Buffer.from(created.refreshTokenDigest).toString("utf8"),
    ).not.toContain(created.refreshToken);
    expect(created.accessTokenDigest).not.toEqual(created.refreshTokenDigest);
  });

  it("canonicalizes email and digests installation identity with separate purposes", () => {
    const emailIndex = new HmacEmailIndex({ key, version: 2 }, (email) =>
      email.trim().toLowerCase(),
    );
    const devices = new HmacDeviceIdentifierDigester({ key, version: 3 });

    expect(emailIndex.create(" Learner@Example.com ")).toEqual(
      emailIndex.create("learner@example.com"),
    );
    expect(emailIndex.create("learner@example.com").digest).not.toEqual(
      devices.digest("learner@example.com").digest,
    );
  });

  it("rejects weak digest keys and incorrect entropy length", () => {
    expect(
      () => new HmacEmailIndex({ key: Uint8Array.of(1), version: 1 }, (v) => v),
    ).toThrow("32 bytes");
    const factory = new HmacSessionSecretFactory(
      { key, version: 1 },
      { bytes: () => Uint8Array.of(1) },
    );
    expect(() => factory.create()).toThrow("64 bytes");
  });
});
