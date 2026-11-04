import type { PostgresConnectionSettings } from "@reallanguage/database";
import type {
  VersionedEncryptionKey,
  VersionedDigestKey,
} from "@reallanguage/backend-modules/identity";

export type ProductionConfig = Readonly<{
  http: Readonly<{ allowedOrigins: readonly string[] }>;
  database: PostgresConnectionSettings;
  legalDocuments: Readonly<{
    userAgreementPath: string;
    privacyPolicyPath: string;
  }>;
  safety: Readonly<{
    providerEndpoint: string;
    providerApiKey: string;
    payloadEncryptionKey: VersionedEncryptionKey;
    payloadTtlMilliseconds: number;
  }>;
  contentFormation: Readonly<{
    providerEndpoint: string;
    providerApiKey: string;
  }>;
  billing: Readonly<{
    paymentProviderEndpoint: string;
    paymentProviderApiKey: string;
    paymentWebhookSecret: Uint8Array;
  }>;
  admin: Readonly<{
    canonicalEmail: string;
    encryptionKey: VersionedEncryptionKey;
    digestKey: VersionedDigestKey;
    allowLoopbackBootstrap: boolean;
  }>;
  identity: Readonly<{
    registrationEncryptionKey: VersionedEncryptionKey;
    accountEncryptionKey: VersionedEncryptionKey;
    emailIndexKey: VersionedDigestKey;
    verificationDigestKey: VersionedDigestKey;
    sessionDigestKey: VersionedDigestKey;
    deviceDigestKey: VersionedDigestKey;
    dummyPasswordHash: string;
    passwordBlocklist: ReadonlySet<string>;
    verificationEmailCooldownSeconds: number;
  }>;
}>;

export const parseProductionConfig = (
  environment: NodeJS.ProcessEnv,
): ProductionConfig => {
  const connectionString = required(environment, "DATABASE_URL");
  const keyInputs = [
    [
      "REGISTRATION_ENCRYPTION_KEY",
      environment.REGISTRATION_ENCRYPTION_KEY_BASE64,
    ],
    ["ACCOUNT_ENCRYPTION_KEY", environment.ACCOUNT_ENCRYPTION_KEY_BASE64],
    ["EMAIL_INDEX_KEY", environment.EMAIL_INDEX_KEY_BASE64],
    ["VERIFICATION_DIGEST_KEY", environment.VERIFICATION_DIGEST_KEY_BASE64],
    ["SESSION_DIGEST_KEY", environment.SESSION_DIGEST_KEY_BASE64],
    ["DEVICE_DIGEST_KEY", environment.DEVICE_DIGEST_KEY_BASE64],
    [
      "SAFETY_PAYLOAD_ENCRYPTION_KEY",
      environment.SAFETY_PAYLOAD_ENCRYPTION_KEY_BASE64,
    ],
    ["PAYMENT_WEBHOOK_SECRET", environment.PAYMENT_WEBHOOK_SECRET_BASE64],
    ["ADMIN_ENCRYPTION_KEY", environment.ADMIN_ENCRYPTION_KEY_BASE64],
    ["ADMIN_DIGEST_KEY", environment.ADMIN_DIGEST_KEY_BASE64],
  ] as const;
  const decoded = keyInputs.map(
    ([name, value]) => [name, decodeKey(name, value)] as const,
  );
  const fingerprints = decoded.map(([, value]) =>
    Buffer.from(value.key).toString("hex"),
  );
  if (new Set(fingerprints).size !== fingerprints.length) {
    throw new Error("Production cryptographic keys must be distinct");
  }
  const keys = Object.fromEntries(decoded) as Record<
    string,
    VersionedDigestKey
  >;
  const passwordBlocklist = new Set(
    required(environment, "PASSWORD_BLOCKLIST")
      .split(",")
      .map((value) => value.trim().normalize("NFC"))
      .filter(Boolean),
  );
  if (passwordBlocklist.size === 0)
    throw new Error("PASSWORD_BLOCKLIST must not be empty");

  const dummyPasswordHash = required(environment, "DUMMY_PASSWORD_HASH");
  if (!dummyPasswordHash.startsWith("$argon2id$")) {
    throw new Error("DUMMY_PASSWORD_HASH must be an Argon2id hash");
  }

  return {
    http: {
      allowedOrigins: parseAllowedOrigins(environment.ADMIN_ALLOWED_ORIGINS),
    },
    database: {
      connectionString,
      applicationName:
        environment.DATABASE_APPLICATION_NAME?.trim() || "reallanguage-api",
      maxConnections: positiveInteger(environment.DATABASE_MAX_CONNECTIONS, 10),
      connectionTimeoutMs: positiveInteger(
        environment.DATABASE_CONNECTION_TIMEOUT_MS,
        5_000,
      ),
      idleTimeoutMs: positiveInteger(
        environment.DATABASE_IDLE_TIMEOUT_MS,
        30_000,
      ),
      ssl: environment.DATABASE_SSL !== "false",
    },
    legalDocuments: {
      userAgreementPath: required(environment, "USER_AGREEMENT_FILE"),
      privacyPolicyPath: required(environment, "PRIVACY_POLICY_FILE"),
    },
    safety: {
      providerEndpoint: requiredHttpsUrl(environment, "SAFETY_PROVIDER_URL"),
      providerApiKey: required(environment, "SAFETY_PROVIDER_API_KEY"),
      payloadEncryptionKey: keys.SAFETY_PAYLOAD_ENCRYPTION_KEY!,
      payloadTtlMilliseconds:
        requiredPositiveInteger(environment, "SAFETY_PAYLOAD_TTL_SECONDS") *
        1_000,
    },
    contentFormation: {
      providerEndpoint: requiredHttpsUrl(
        environment,
        "CONTENT_FORMATION_PROVIDER_URL",
      ),
      providerApiKey: required(
        environment,
        "CONTENT_FORMATION_PROVIDER_API_KEY",
      ),
    },
    billing: {
      paymentProviderEndpoint: requiredHttpsUrl(
        environment,
        "PAYMENT_PROVIDER_URL",
      ),
      paymentProviderApiKey: required(environment, "PAYMENT_PROVIDER_API_KEY"),
      paymentWebhookSecret: keys.PAYMENT_WEBHOOK_SECRET!.key,
    },
    admin: {
      canonicalEmail: requiredEmail(environment, "ADMIN_EMAIL"),
      encryptionKey: keys.ADMIN_ENCRYPTION_KEY!,
      digestKey: keys.ADMIN_DIGEST_KEY!,
      allowLoopbackBootstrap:
        environment.ADMIN_ALLOW_LOOPBACK_BOOTSTRAP === "true",
    },
    identity: {
      registrationEncryptionKey: keys.REGISTRATION_ENCRYPTION_KEY!,
      accountEncryptionKey: keys.ACCOUNT_ENCRYPTION_KEY!,
      emailIndexKey: keys.EMAIL_INDEX_KEY!,
      verificationDigestKey: keys.VERIFICATION_DIGEST_KEY!,
      sessionDigestKey: keys.SESSION_DIGEST_KEY!,
      deviceDigestKey: keys.DEVICE_DIGEST_KEY!,
      dummyPasswordHash,
      passwordBlocklist,
      verificationEmailCooldownSeconds: positiveInteger(
        environment.VERIFICATION_EMAIL_COOLDOWN_SECONDS,
        60,
      ),
    },
  };
};

const requiredEmail = (
  environment: NodeJS.ProcessEnv,
  name: string,
): string => {
  const value = required(environment, name).toLowerCase();
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/u.test(value)) {
    throw new Error(`${name} must be an email address`);
  }
  return value;
};

const parseAllowedOrigins = (value: string | undefined): readonly string[] => {
  if (!value?.trim()) return [];
  const origins = value
    .split(",")
    .map((origin) => origin.trim())
    .filter(Boolean);
  for (const origin of origins) {
    const parsed = new URL(origin);
    if (parsed.protocol !== "https:" || parsed.origin !== origin) {
      throw new Error("ADMIN_ALLOWED_ORIGINS must contain HTTPS origins only");
    }
  }
  return [...new Set(origins)];
};

const requiredHttpsUrl = (
  environment: NodeJS.ProcessEnv,
  name: string,
): string => {
  const value = required(environment, name);
  const parsed = new URL(value);
  if (parsed.protocol !== "https:") throw new Error(`${name} must use HTTPS`);
  return parsed.toString();
};

const requiredPositiveInteger = (
  environment: NodeJS.ProcessEnv,
  name: string,
): number => {
  const parsed = Number(required(environment, name));
  if (!Number.isSafeInteger(parsed) || parsed <= 0) {
    throw new Error(`${name} must be a positive integer`);
  }
  return parsed;
};

const decodeKey = (
  name: string,
  value: string | undefined,
): VersionedDigestKey => {
  const encoded = value?.trim();
  if (!encoded) throw new Error(`${name}_BASE64 is required`);
  const key = Buffer.from(encoded, "base64");
  if (key.byteLength !== 32)
    throw new Error(`${name}_BASE64 must decode to 32 bytes`);
  return { key: new Uint8Array(key), version: 1 };
};

const required = (environment: NodeJS.ProcessEnv, name: string): string => {
  const value = environment[name]?.trim();
  if (!value) throw new Error(`${name} is required`);
  return value;
};

const positiveInteger = (
  value: string | undefined,
  fallback: number,
): number => {
  if (value === undefined) return fallback;
  const parsed = Number(value);
  if (!Number.isSafeInteger(parsed) || parsed <= 0) {
    throw new Error(
      "Numeric production configuration must be a positive integer",
    );
  }
  return parsed;
};
