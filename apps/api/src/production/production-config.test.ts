import { describe, expect, it } from "vitest";

import { parseProductionConfig } from "./production-config.js";

const key = (byte: number) => Buffer.alloc(32, byte).toString("base64");

describe("production API configuration", () => {
  it("requires explicit database, cryptographic keys, dummy hash, and blocklist", () => {
    const config = parseProductionConfig({
      DATABASE_URL: "postgres://localhost/reallanguage",
      REGISTRATION_ENCRYPTION_KEY_BASE64: key(1),
      ACCOUNT_ENCRYPTION_KEY_BASE64: key(2),
      EMAIL_INDEX_KEY_BASE64: key(3),
      VERIFICATION_DIGEST_KEY_BASE64: key(4),
      SESSION_DIGEST_KEY_BASE64: key(5),
      DEVICE_DIGEST_KEY_BASE64: key(6),
      SAFETY_PAYLOAD_ENCRYPTION_KEY_BASE64: key(7),
      DUMMY_PASSWORD_HASH: "$argon2id$v=19$placeholder",
      PASSWORD_BLOCKLIST: "password1234567,123456789012345",
      USER_AGREEMENT_FILE: "/run/secrets/user-agreement.json",
      PRIVACY_POLICY_FILE: "/run/secrets/privacy-policy.json",
      SAFETY_PROVIDER_URL: "https://safety.example.test/check",
      SAFETY_PROVIDER_API_KEY: "safety-secret",
      SAFETY_PAYLOAD_TTL_SECONDS: "1800",
      CONTENT_FORMATION_PROVIDER_URL: "https://formation.example.test/tasks",
      CONTENT_FORMATION_PROVIDER_API_KEY: "formation-secret",
      PAYMENT_PROVIDER_URL: "https://payment.example.test/orders",
      PAYMENT_PROVIDER_API_KEY: "payment-secret",
      PAYMENT_WEBHOOK_SECRET_BASE64: key(8),
      ADMIN_ENCRYPTION_KEY_BASE64: key(9),
      ADMIN_DIGEST_KEY_BASE64: key(10),
      ADMIN_EMAIL: "indewo@outlook.com",
      ADMIN_ALLOWED_ORIGINS: "https://admin.reallanguage.me",
    });

    expect(config.database.connectionString).toBe(
      "postgres://localhost/reallanguage",
    );
    expect(config.identity.sessionDigestKey.key).toHaveLength(32);
    expect(config.identity.passwordBlocklist.has("password1234567")).toBe(true);
    expect(config.safety.payloadTtlMilliseconds).toBe(1_800_000);
    expect(config.contentFormation.providerEndpoint).toBe(
      "https://formation.example.test/tasks",
    );
    expect(config.billing.paymentProviderEndpoint).toBe(
      "https://payment.example.test/orders",
    );
    expect(config.http.allowedOrigins).toEqual([
      "https://admin.reallanguage.me",
    ]);
    expect(config.admin.canonicalEmail).toBe("indewo@outlook.com");
    expect(config.admin.allowLoopbackBootstrap).toBe(false);
  });

  it("fails closed for missing, short, or reused secrets", () => {
    expect(() => parseProductionConfig({})).toThrow("DATABASE_URL");
    const base = {
      DATABASE_URL: "postgres://localhost/reallanguage",
      REGISTRATION_ENCRYPTION_KEY_BASE64: key(1),
      ACCOUNT_ENCRYPTION_KEY_BASE64: key(2),
      EMAIL_INDEX_KEY_BASE64: key(3),
      VERIFICATION_DIGEST_KEY_BASE64: key(4),
      SESSION_DIGEST_KEY_BASE64: key(5),
      DEVICE_DIGEST_KEY_BASE64: key(6),
      SAFETY_PAYLOAD_ENCRYPTION_KEY_BASE64: key(7),
      DUMMY_PASSWORD_HASH: "$argon2id$v=19$placeholder",
      PASSWORD_BLOCKLIST: "password1234567",
      USER_AGREEMENT_FILE: "/run/secrets/user-agreement.json",
      PRIVACY_POLICY_FILE: "/run/secrets/privacy-policy.json",
      SAFETY_PROVIDER_URL: "https://safety.example.test/check",
      SAFETY_PROVIDER_API_KEY: "safety-secret",
      SAFETY_PAYLOAD_TTL_SECONDS: "1800",
      CONTENT_FORMATION_PROVIDER_URL: "https://formation.example.test/tasks",
      CONTENT_FORMATION_PROVIDER_API_KEY: "formation-secret",
      PAYMENT_PROVIDER_URL: "https://payment.example.test/orders",
      PAYMENT_PROVIDER_API_KEY: "payment-secret",
      PAYMENT_WEBHOOK_SECRET_BASE64: key(8),
      ADMIN_ENCRYPTION_KEY_BASE64: key(9),
      ADMIN_DIGEST_KEY_BASE64: key(10),
      ADMIN_EMAIL: "indewo@outlook.com",
    };
    expect(() =>
      parseProductionConfig({ ...base, SESSION_DIGEST_KEY_BASE64: key(1) }),
    ).toThrow("distinct");
    expect(() =>
      parseProductionConfig({
        ...base,
        DEVICE_DIGEST_KEY_BASE64: Buffer.alloc(8).toString("base64"),
      }),
    ).toThrow("32 bytes");
  });
});
