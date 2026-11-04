import { mkdir } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { PGlite } from "@electric-sql/pglite";
import {
  AesGcmAccountSensitiveFieldsProtector,
  AesGcmPasswordResetDeliverySecretProtector,
  AesGcmRegistrationPayloadProtector,
  Argon2PasswordHasher,
} from "@reallanguage/backend-modules/identity";
import type { MeteredProviderGateway } from "@reallanguage/backend-modules/billing";
import { migrateToLatest } from "@reallanguage/database";
import { Module, type Type } from "@nestjs/common";
import type { NestFastifyApplication } from "@nestjs/platform-fastify";
import { Kysely, PGliteDialect, sql } from "kysely";

import { createHttpApp } from "../create-app.js";
import {
  createProductionApiModule,
  type ProductionDatabase,
} from "../production/production-api.module.js";
import type { ProductionConfig } from "../production/production-config.js";
import {
  LocalRegistrationEmailPump,
  PasswordResetEmailOutboxDispatcher,
  RegistrationEmailOutboxDispatcher,
  type RegistrationEmailSender,
} from "./directmail-registration-email.js";

export type LocalAppOptions = Readonly<{
  dataDirectory?: string | undefined;
  aiProvider?: MeteredProviderGateway;
  developmentCreditFen?: number;
  registrationEmail?: Readonly<{
    sender: RegistrationEmailSender;
    publicApiBaseUrl: string;
  }>;
  accessLogger?: (entry: Readonly<{
    remoteAddress: string;
    method: string;
    path: string;
    statusCode: number;
    requestId: string;
  }>) => void;
}>;

export const createLocalApp = async (
  options: LocalAppOptions = {},
): Promise<NestFastifyApplication> => {
  const dataDirectory =
    options.dataDirectory === undefined && !("dataDirectory" in options)
      ? resolve(process.cwd(), ".local-data/reallanguage-db")
      : options.dataDirectory;
  if (dataDirectory !== undefined) {
    await mkdir(dirname(dataDirectory), { recursive: true });
  }
  const pglite = new PGlite(dataDirectory);
  const database = new Kysely<ProductionDatabase>({
    dialect: new PGliteDialect({ pglite }),
  });

  try {
    await migrateToLatest(database);
    if (options.developmentCreditFen !== undefined) {
      await enableLocalDevelopmentCredit(
        database,
        options.developmentCreditFen,
      );
    }
    const config = await createLocalConfig();
    const accountFields = new AesGcmAccountSensitiveFieldsProtector({
      activeEncryptionKey: config.identity.accountEncryptionKey,
      decryptionKeys: [],
    });
    const passwordResetDeliverySecrets =
      new AesGcmPasswordResetDeliverySecretProtector({
        activeEncryptionKey: config.identity.accountEncryptionKey,
        decryptionKeys: [],
      });
    const productionModule = createProductionApiModule(
      database,
      config,
      options.aiProvider
        ? { content: options.aiProvider, safety: options.aiProvider }
        : {},
    );
    const rootModule = options.registrationEmail
      ? createLocalEmailModule(
          productionModule,
          new RegistrationEmailOutboxDispatcher(
            database,
            new AesGcmRegistrationPayloadProtector({
              activeEncryptionKey: config.identity.registrationEncryptionKey,
              decryptionKeys: [],
              emailIndexKey: config.identity.emailIndexKey,
              canonicalizeEmail: (email) => email.trim().toLowerCase(),
            }),
            options.registrationEmail.sender,
            options.registrationEmail.publicApiBaseUrl,
          ),
          new PasswordResetEmailOutboxDispatcher(
            database,
            passwordResetDeliverySecrets,
            accountFields,
            options.registrationEmail.sender,
          ),
        )
      : productionModule;
    const app = await createHttpApp(rootModule, {
      allowedOrigins: ["http://127.0.0.1:5173", "http://localhost:5173"],
      ...(options.accessLogger ? { accessLogger: options.accessLogger } : {}),
    });
    app.enableShutdownHooks();
    return app;
  } catch (error) {
    await database.destroy();
    throw error;
  }
};

export const enableLocalDevelopmentCredit = async (
  database: Kysely<ProductionDatabase>,
  amountFen: number,
): Promise<void> => {
  if (!Number.isSafeInteger(amountFen) || amountFen < 0) {
    throw new Error("developmentCreditFen must be a non-negative safe integer");
  }
  await database.transaction().execute(async (transaction) => {
    await sql`alter table billing.wallets alter column posted_balance_fen set default 0`.execute(
      transaction,
    );
    await sql
      .raw(
        `create or replace function billing.create_wallet_for_account()
    returns trigger language plpgsql as $$
    declare
      subject_id uuid := gen_random_uuid();
      created_wallet_id uuid := gen_random_uuid();
    begin
      insert into billing.billing_subjects (billing_subject_id, user_id)
        values (subject_id, new.account_id);
      insert into billing.wallets (wallet_id, billing_subject_id, posted_balance_fen)
        values (created_wallet_id, subject_id, ${amountFen});
      if ${amountFen} > 0 then
        insert into billing.ledger_entries (
          ledger_entry_id, billing_subject_id, wallet_id, entry_type, amount_fen,
          business_ref_type, business_ref_id, idempotency_key
        ) values (
          gen_random_uuid(), subject_id, created_wallet_id, 'GRANT', ${amountFen},
          'LOCAL_DEVELOPMENT_CREDIT', new.account_id::text,
          'local-development-credit:' || new.account_id::text
        );
      end if;
      return new;
    end $$`,
      )
      .execute(transaction);
    await sql`insert into billing.pricing_rules (
      pricing_rule_id, multiplier_numerator, multiplier_denominator,
      rounding_mode, minimum_charge_fen, effective_at
    ) values (
      '00000000-0000-4000-8000-000000000001', 1, 1,
      'CEILING_TO_FEN', 1, '2020-01-01T00:00:00.000Z'
    ) on conflict (pricing_rule_id) do nothing`.execute(transaction);
  });
};

const createLocalEmailModule = (
  productionModule: Type<unknown>,
  dispatcher: RegistrationEmailOutboxDispatcher,
  passwordResetDispatcher: PasswordResetEmailOutboxDispatcher,
): Type<unknown> => {
  @Module({
    imports: [productionModule],
    providers: [
      {
        provide: LocalRegistrationEmailPump,
        useValue: new LocalRegistrationEmailPump(
          dispatcher,
          passwordResetDispatcher,
        ),
      },
    ],
  })
  class LocalEmailModule {}

  return LocalEmailModule;
};

const createLocalConfig = async (): Promise<ProductionConfig> => ({
  http: { allowedOrigins: [] },
  database: {
    connectionString: "embedded-pglite",
    applicationName: "reallanguage-local-api",
    maxConnections: 1,
    connectionTimeoutMs: 5_000,
    idleTimeoutMs: 30_000,
    ssl: false,
  },
  legalDocuments: {
    userAgreementPath: resolve(
      projectRoot,
      "apps/api/local/user-agreement.json",
    ),
    privacyPolicyPath: resolve(
      projectRoot,
      "apps/api/local/privacy-policy.json",
    ),
  },
  safety: {
    providerEndpoint: "https://local-provider.invalid/safety",
    providerApiKey: "local-provider-not-configured",
    payloadEncryptionKey: versionedKey(7),
    payloadTtlMilliseconds: 1_800_000,
  },
  contentFormation: {
    providerEndpoint: "https://local-provider.invalid/content",
    providerApiKey: "local-provider-not-configured",
  },
  billing: {
    paymentProviderEndpoint: "https://local-provider.invalid/payment",
    paymentProviderApiKey: "local-provider-not-configured",
    paymentWebhookSecret: bytes(8),
  },
  admin: {
    canonicalEmail: "indewo@outlook.com",
    encryptionKey: versionedKey(9),
    digestKey: versionedKey(10),
    allowLoopbackBootstrap: true,
  },
  identity: {
    registrationEncryptionKey: versionedKey(1),
    accountEncryptionKey: versionedKey(2),
    emailIndexKey: versionedKey(3),
    verificationDigestKey: versionedKey(4),
    sessionDigestKey: versionedKey(5),
    deviceDigestKey: versionedKey(6),
    dummyPasswordHash: await new Argon2PasswordHasher().hash(
      "RealLanguage local-only dummy credential",
    ),
    passwordBlocklist: new Set(["password1234567"]),
    verificationEmailCooldownSeconds: 60,
  },
});

const projectRoot = fileURLToPath(new URL("../../../../", import.meta.url));
const versionedKey = (value: number) => ({ key: bytes(value), version: 1 });
const bytes = (value: number): Uint8Array =>
  Uint8Array.from({ length: 32 }, () => value);
