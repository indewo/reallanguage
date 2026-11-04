import { PGlite } from "@electric-sql/pglite";
import { migrateToLatest } from "@reallanguage/database";
import type { NestFastifyApplication } from "@nestjs/platform-fastify";
import { Kysely, PGliteDialect, sql } from "kysely";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { createHttpApp } from "../create-app.js";
import {
  createProductionApiModule,
  type ProductionDatabase,
} from "./production-api.module.js";
import type { ProductionConfig } from "./production-config.js";
import { COMPLETE_INITIAL_SETTINGS_APPLICATION } from "../presentation/initial-settings.controller.js";
import {
  READ_VERSIONED_SETTINGS_APPLICATION,
  UPDATE_CURRENT_SETTINGS_APPLICATION,
} from "../presentation/current-settings-update.controller.js";
import type {
  CompleteInitialSettings,
  ReadVersionedSettings,
  UpdateCurrentSettings,
} from "@reallanguage/backend-modules/settings";
import { KyselyContentGenerationSettingsReader } from "@reallanguage/backend-modules/content-formation";
import { RECOVER_LEARNING_EXPLANATION_APPLICATION } from "../presentation/learning-explanation-recovery.controller.js";
import type { RecoverLearningExplanation } from "@reallanguage/backend-modules/learning";

let database: Kysely<ProductionDatabase>;
let app: NestFastifyApplication | undefined;

beforeEach(async () => {
  database = new Kysely({
    dialect: new PGliteDialect({ pglite: new PGlite() }),
  });
  await migrateToLatest(database);
});

afterEach(async () => {
  await app?.close();
  app = undefined;
  await database.destroy();
  vi.useRealTimers();
});

describe("production API module", () => {
  it("binds a read-only explanation recovery path without supplying the provider any execution", async () => {
    const quote = vi
      .fn()
      .mockRejectedValue(new Error("Recovery must not quote"));
    const execute = vi
      .fn()
      .mockRejectedValue(new Error("Recovery must not execute"));
    app = await createHttpApp(
      createProductionApiModule(database, config, {
        content: { quote, execute },
      }),
    );
    const response = await app.inject({
      method: "GET",
      url: "/api/v1/learning/explanations/deliveries/unknown?kind=SENTENCE",
    });
    expect(response.statusCode).toBe(401);
    const reader = app.get<RecoverLearningExplanation>(
      RECOVER_LEARNING_EXPLANATION_APPLICATION,
    );
    expect(
      await reader.execute({
        accountId: "30000000-0000-4000-8000-000000000023",
        commandId: "unknown",
        kind: "SENTENCE",
      }),
    ).toEqual({ status: "NO_DELIVERY" });
    expect(quote).not.toHaveBeenCalled();
    expect(execute).not.toHaveBeenCalled();
  });
  it("binds subsequent updates to the versioned store while retaining the initial one-time writer", async () => {
    app = await createHttpApp(createProductionApiModule(database, config));
    const accountId = "30000000-0000-4000-8000-000000000023";
    await sql`insert into identity.accounts (account_id,birth_year_month_ciphertext,birth_year_month_algorithm,birth_year_month_key_version,age_eligible,age_eligibility_confirmed_at,email_verified_at)
      values (${accountId},decode('01','hex'),'AES-256-GCM/v1',1,true,now(),now())`.execute(
      database,
    );
    const settings = {
      formalityLevel: 3,
      slangEnabled: false,
      basicVocabulary: "CET_6" as const,
      purposeVocabularies: [] as const,
      styleReference: "Clear",
      voiceEnabled: true,
      autoPlayEnabled: false,
      speechSpeedLevel: 3,
    };
    const initial = app.get<CompleteInitialSettings>(
      COMPLETE_INITIAL_SETTINGS_APPLICATION,
    );
    const updater = app.get<UpdateCurrentSettings>(
      UPDATE_CURRENT_SETTINGS_APPLICATION,
    );
    const reader = app.get<ReadVersionedSettings>(
      READ_VERSIONED_SETTINGS_APPLICATION,
    );
    await initial.execute({ accountId, settings });
    expect(
      await updater.execute({
        accountId,
        expectedRowVersion: 1,
        settings: { ...settings, formalityLevel: 5, speechSpeedLevel: 4 },
      }),
    ).toEqual({ status: "SAVED", rowVersion: 2 });
    await initial.execute({ accountId, settings });
    expect(await reader.execute({ accountId })).toEqual({
      settings: { ...settings, formalityLevel: 5, speechSpeedLevel: 4 },
      rowVersion: 2,
    });
    expect(
      await new KyselyContentGenerationSettingsReader(database).read(accountId),
    ).toEqual({
      formalityLevel: 5,
      slangEnabled: settings.slangEnabled,
      basicVocabulary: settings.basicVocabulary,
      purposeVocabularies: settings.purposeVocabularies,
      styleReference: settings.styleReference,
    });
    for (const method of ["GET", "PUT"] as const) {
      expect(
        (await app.inject({ method, url: "/api/v1/settings/current/revision" }))
          .statusCode,
      ).toBe(401);
    }
  });
  it("cleans newly expired safety payloads during idle API time without another user request", async () => {
    vi.useFakeTimers({ toFake: ["setInterval", "clearInterval"] });
    app = await createHttpApp(createProductionApiModule(database, config));
    const accountId = "30000000-0000-4000-8000-000000000002";
    const operationId = "10000000-0000-4000-8000-000000000002";
    await sql`insert into identity.accounts (account_id,birth_year_month_ciphertext,birth_year_month_algorithm,birth_year_month_key_version,age_eligible,age_eligibility_confirmed_at,email_verified_at)
      values (${accountId},decode('01','hex'),'AES-256-GCM/v1',1,true,now(),now())`.execute(
      database,
    );
    await sql`insert into safety.check_operations (operation_id,user_id,command_id,request_digest,source_type,content_shape,payload_ciphertext,payload_algorithm,payload_key_version,status,expires_at,created_at)
      values (${operationId},${accountId},'idle-expired-check',decode('01','hex'),'DIARY','ARTICLE',decode('0102','hex'),'AES-256-GCM/v1',1,'FAILED',now()-interval '1 hour',now()-interval '2 hours')`.execute(
      database,
    );
    await vi.advanceTimersByTimeAsync(60_000);
    await vi.waitFor(async () => {
      const result = await sql<{
        payload_ciphertext: Uint8Array | null;
      }>`select payload_ciphertext from safety.check_operations where operation_id=${operationId}`.execute(
        database,
      );
      expect(result.rows[0]?.payload_ciphertext).toBeNull();
    });
  });

  it("cleans expired non-review safety ciphertext on application startup without erasing command metadata", async () => {
    const accountId = "30000000-0000-4000-8000-000000000001";
    const operationId = "10000000-0000-4000-8000-000000000001";
    await sql`insert into identity.accounts (account_id,birth_year_month_ciphertext,birth_year_month_algorithm,birth_year_month_key_version,age_eligible,age_eligibility_confirmed_at,email_verified_at)
      values (${accountId},decode('01','hex'),'AES-256-GCM/v1',1,true,now(),now())`.execute(
      database,
    );
    await sql`insert into safety.check_operations (operation_id,user_id,command_id,request_digest,source_type,content_shape,payload_ciphertext,payload_algorithm,payload_key_version,status,expires_at,created_at)
      values (${operationId},${accountId},'expired-failed-check',decode('01','hex'),'DIARY','ARTICLE',decode('0102','hex'),'AES-256-GCM/v1',1,'FAILED',now()-interval '1 hour',now()-interval '2 hours')`.execute(
      database,
    );
    app = await createHttpApp(createProductionApiModule(database, config));
    const result = await sql<{
      payload_ciphertext: Uint8Array | null;
      command_id: string;
      status: string;
    }>`select payload_ciphertext,command_id,status from safety.check_operations where operation_id=${operationId}`.execute(
      database,
    );
    expect(result.rows).toEqual([
      {
        payload_ciphertext: null,
        command_id: "expired-failed-check",
        status: "FAILED",
      },
    ]);
  });

  it("registers health, identity, session, and settings controllers", async () => {
    app = await createHttpApp(createProductionApiModule(database, config));

    const health = await app.inject({ method: "GET", url: "/api/v1/health" });
    expect(health.statusCode).toBe(200);
    const settings = await app.inject({
      method: "PUT",
      url: "/api/v1/settings/current/initial",
      payload: {},
    });
    expect(settings.statusCode).toBe(401);
    const session = await app.inject({
      method: "POST",
      url: "/api/v1/auth/sessions",
      payload: {},
    });
    expect(session.statusCode).toBe(400);
    const passwordReset = await app.inject({
      method: "POST",
      url: "/api/v1/auth/password-resets",
      payload: {},
    });
    expect(passwordReset.statusCode).toBe(400);
    const safety = await app.inject({
      method: "POST",
      url: "/api/v1/safety/checks",
      payload: {},
    });
    expect(safety.statusCode).toBe(401);
    const recording = await app.inject({
      method: "POST",
      url: "/api/v1/media/recording-transcriptions",
      payload: {},
    });
    expect(recording.statusCode).toBe(401);
    const scene = await app.inject({
      method: "POST",
      url: "/api/v1/sources/scenes/next-question",
      payload: {},
    });
    expect(scene.statusCode).toBe(401);
    const admin = await app.inject({
      method: "GET",
      url: "/api/v1/admin/bootstrap/status",
    });
    expect(admin.statusCode).toBe(200);
    expect(admin.json()).toEqual({
      configured: false,
      enrollmentPending: false,
      email: "indewo@outlook.com",
    });
  });
});

const bytes = (value: number) => Uint8Array.from({ length: 32 }, () => value);
const config: ProductionConfig = {
  http: { allowedOrigins: [] },
  database: {
    connectionString: "unused",
    applicationName: "reallanguage-api",
    maxConnections: 5,
    connectionTimeoutMs: 5_000,
    idleTimeoutMs: 30_000,
    ssl: false,
  },
  legalDocuments: {
    userAgreementPath: "/unused/user-agreement.json",
    privacyPolicyPath: "/unused/privacy-policy.json",
  },
  safety: {
    providerEndpoint: "https://safety.example.test/check",
    providerApiKey: "safety-secret",
    payloadEncryptionKey: { key: bytes(7), version: 1 },
    payloadTtlMilliseconds: 1_800_000,
  },
  contentFormation: {
    providerEndpoint: "https://formation.example.test/tasks",
    providerApiKey: "formation-secret",
  },
  billing: {
    paymentProviderEndpoint: "https://payment.example.test/orders",
    paymentProviderApiKey: "payment-secret",
    paymentWebhookSecret: new Uint8Array(32).fill(8),
  },
  admin: {
    canonicalEmail: "indewo@outlook.com",
    encryptionKey: { key: bytes(9), version: 1 },
    digestKey: { key: bytes(10), version: 1 },
    allowLoopbackBootstrap: true,
  },
  identity: {
    registrationEncryptionKey: { key: bytes(1), version: 1 },
    accountEncryptionKey: { key: bytes(2), version: 1 },
    emailIndexKey: { key: bytes(3), version: 1 },
    verificationDigestKey: { key: bytes(4), version: 1 },
    sessionDigestKey: { key: bytes(5), version: 1 },
    deviceDigestKey: { key: bytes(6), version: 1 },
    dummyPasswordHash: "$argon2id$v=19$placeholder",
    passwordBlocklist: new Set(["password1234567"]),
    verificationEmailCooldownSeconds: 60,
  },
};
