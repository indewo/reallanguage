// Opt-in socket verification only: fresh in-memory database, generated keys, no email/admin/AI operations.
import "reflect-metadata";
import { randomBytes, randomUUID } from "node:crypto";
import { chmod, mkdtemp, unlink, writeFile } from "node:fs/promises";
import type { Server } from "node:http";
import type { AddressInfo } from "node:net";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { PGlite } from "@electric-sql/pglite";
import {
  HmacDeviceIdentifierDigester,
  HmacSessionSecretFactory,
  KyselySessionIssuer,
} from "@reallanguage/backend-modules/identity";
import type { MeteredProviderGateway } from "@reallanguage/backend-modules/billing";
import { migrateToLatest } from "@reallanguage/database";
import { Kysely, PGliteDialect, sql } from "kysely";
import { createHttpApp } from "../src/create-app.js";
import {
  createProductionApiModule,
  type ProductionDatabase,
} from "../src/production/production-api.module.js";
import type { ProductionConfig } from "../src/production/production-config.js";

const outputDirectory = await mkdtemp(
  join(tmpdir(), "reallanguage-messages-http-"),
);
await chmod(outputDirectory, 0o700);
const sessionPath = join(outputDirectory, "private-session.json");
const metaPath = join(outputDirectory, "meta.json");
const reportPath = join(outputDirectory, "server-result.json");
const key = () => ({ key: randomBytes(32), version: 1 });
const config: ProductionConfig = {
  http: { allowedOrigins: [] },
  database: {
    connectionString: "unused-in-memory-only",
    applicationName: "isolated-messages-http",
    maxConnections: 1,
    connectionTimeoutMs: 5_000,
    idleTimeoutMs: 30_000,
    ssl: false,
  },
  legalDocuments: {
    userAgreementPath: "/unused-isolated-messages/user-agreement.json",
    privacyPolicyPath: "/unused-isolated-messages/privacy-policy.json",
  },
  safety: {
    providerEndpoint: "https://unused.invalid/safety",
    providerApiKey: randomUUID(),
    payloadEncryptionKey: key(),
    payloadTtlMilliseconds: 1_800_000,
  },
  contentFormation: {
    providerEndpoint: "https://unused.invalid/content",
    providerApiKey: randomUUID(),
  },
  billing: {
    paymentProviderEndpoint: "https://unused.invalid/payment",
    paymentProviderApiKey: randomUUID(),
    paymentWebhookSecret: randomBytes(32),
  },
  admin: {
    canonicalEmail: "unused-isolated-admin@example.invalid",
    encryptionKey: key(),
    digestKey: key(),
    allowLoopbackBootstrap: false,
  },
  identity: {
    registrationEncryptionKey: key(),
    accountEncryptionKey: key(),
    emailIndexKey: key(),
    verificationDigestKey: key(),
    sessionDigestKey: key(),
    deviceDigestKey: key(),
    dummyPasswordHash: "$argon2id$v=19$unused-isolated-messages",
    passwordBlocklist: new Set(["unused-blocked-password"]),
    verificationEmailCooldownSeconds: 60,
  },
};
let providerCalls = 0;
const forbiddenProvider: MeteredProviderGateway = {
  quote: async () => {
    providerCalls++;
    throw new Error("Provider calls prohibited in message verification");
  },
  execute: async () => {
    providerCalls++;
    throw new Error("Provider calls prohibited in message verification");
  },
};
const database = new Kysely<ProductionDatabase>({
  dialect: new PGliteDialect({ pglite: new PGlite() }),
});
let app: Awaited<ReturnType<typeof createHttpApp>> | undefined;
let stage = "migrate";
type HttpEvidence = {
  owner: "A" | "B" | "UNKNOWN";
  method: string;
  status: number;
};
const requests: HttpEvidence[] = [];
let report: Record<string, unknown> = {
  status: "FAILED",
  stage: "not-started",
  providerCalls: 0,
};

try {
  await migrateToLatest(database);
  stage = "seed-isolated-accounts";
  const accountA = randomUUID();
  const accountB = randomUUID();
  for (const accountId of [accountA, accountB]) {
    await sql`insert into identity.accounts
      (account_id,birth_year_month_ciphertext,birth_year_month_algorithm,birth_year_month_key_version,
       age_eligible,age_eligibility_confirmed_at,email_verified_at)
      values (${accountId},decode('01','hex'),'AES-256-GCM/v1',1,true,now(),now())`.execute(
      database,
    );
  }
  const unreadA = randomUUID();
  const alreadyReadA = randomUUID();
  const unreadB = randomUUID();
  const reviewTarget = randomUUID();
  const earlierReadAt = new Date(Date.now() - 60_000);
  await sql`insert into messaging.app_messages
    (message_id,user_id,kind,summary_zh,related_content_label_zh,target_route,target_resource_id,read_at)
    values
    (${unreadA},${accountA},'MANUAL_REVIEW_RESULT','隔离复核消息','隔离测试日记','MANUAL_REVIEW_RESULT',${reviewTarget},null),
    (${alreadyReadA},${accountA},'SYSTEM','隔离已读消息',null,null,null,${earlierReadAt}),
    (${unreadB},${accountB},'SYSTEM','另一隔离账号消息',null,null,null,null)`.execute(
    database,
  );

  stage = "issue-isolated-production-sessions";
  const issuer = new KyselySessionIssuer(database, {
    clock: { now: () => new Date() },
    identifiers: { next: () => randomUUID() },
    secrets: new HmacSessionSecretFactory(config.identity.sessionDigestKey),
    devices: new HmacDeviceIdentifierDigester(config.identity.deviceDigestKey),
  });
  const issuedA = await issuer.issue({
    accountId: accountA,
    deviceId: `message-fixture-a-${randomUUID()}`,
  });
  const issuedB = await issuer.issue({
    accountId: accountB,
    deviceId: `message-fixture-b-${randomUUID()}`,
  });
  stage = "start-production-http";
  app = await createHttpApp(
    createProductionApiModule(database, config, {
      content: forbiddenProvider,
      safety: forbiddenProvider,
    }),
  );
  const server = app.getHttpServer() as Server;
  server.on("request", (request, response) => {
    if (
      request.url !== "/api/v1/messages" &&
      request.url !== "/api/v1/messages/read-all"
    )
      return;
    const owner =
      request.headers.authorization === `Bearer ${issuedA.accessToken}`
        ? "A"
        : request.headers.authorization === `Bearer ${issuedB.accessToken}`
          ? "B"
          : "UNKNOWN";
    response.once("finish", () => {
      requests.push({
        owner,
        method: request.method ?? "",
        status: response.statusCode,
      });
    });
  });
  await app.listen(0, "127.0.0.1");
  const port = (server.address() as AddressInfo).port;
  await writeFile(
    sessionPath,
    JSON.stringify({
      fixture: "REAL_MESSAGES_HTTP_V1",
      baseUrl: `http://10.0.2.2:${port}`,
      accountA: {
        accountId: accountA,
        accessToken: issuedA.accessToken,
        unreadMessageId: unreadA,
        alreadyReadMessageId: alreadyReadA,
        reviewTarget,
      },
      accountB: {
        accountId: accountB,
        accessToken: issuedB.accessToken,
        unreadMessageId: unreadB,
      },
    }),
    { mode: 0o600, flag: "wx" },
  );
  const metadata = {
    pid: process.pid,
    port,
    metaPath,
    sessionPath,
    reportPath,
  };
  await writeFile(metaPath, JSON.stringify(metadata), {
    mode: 0o600,
    flag: "wx",
  });
  process.stdout.write(`${JSON.stringify(metadata)}\n`);

  stage = "wait-for-native-check";
  const completed = await new Promise<boolean>((resolve) => {
    const timer = setTimeout(() => done(false), 600_000);
    const done = (success: boolean) => {
      clearTimeout(timer);
      process.off("SIGUSR2", finish);
      process.off("SIGINT", abort);
      process.off("SIGTERM", abort);
      resolve(success);
    };
    const finish = () => done(true);
    const abort = () => done(false);
    process.once("SIGUSR2", finish);
    process.once("SIGINT", abort);
    process.once("SIGTERM", abort);
  });
  if (!completed)
    throw new Error("Isolated verification was aborted or timed out");

  stage = "independent-sql-readback";
  const rows = await sql<{
    message_id: string;
    user_id: string;
    read_at: Date | null;
  }>`
    select message_id,user_id,read_at from messaging.app_messages order by message_id
  `.execute(database);
  const a = rows.rows.find((row) => row.message_id === unreadA);
  const existing = rows.rows.find((row) => row.message_id === alreadyReadA);
  const b = rows.rows.find((row) => row.message_id === unreadB);
  const expected = [
    "A:GET:200",
    "A:POST:200",
    "A:GET:200",
    "A:POST:200",
    "B:GET:200",
  ];
  const existingReadAt = existing?.read_at;
  const passed =
    rows.rows.length === 3 &&
    a?.user_id === accountA &&
    a.read_at !== null &&
    b?.user_id === accountB &&
    b.read_at === null &&
    existingReadAt != null &&
    new Date(existingReadAt).getTime() === earlierReadAt.getTime() &&
    providerCalls === 0 &&
    JSON.stringify(
      requests.map(
        (request) => `${request.owner}:${request.method}:${request.status}`,
      ),
    ) === JSON.stringify(expected);
  if (!passed)
    throw new Error("Message persistence or real HTTP evidence did not match");
  report = {
    status: "PASSED",
    transport: "REAL_HTTP_SOCKET",
    database: "ISOLATED_IN_MEMORY_PGLITE",
    productionAuthentication: true,
    actualHttpRequests: requests.length,
    accountIsolation: true,
    unreadPersisted: true,
    alreadyReadTimestampPreserved: true,
    providerCalls,
    scope:
      "Message retrieval and read-all persistence only; no human review, login, email, push or AI acceptance",
  };
} catch (error) {
  report = {
    status: "FAILED",
    stage,
    errorType: error instanceof Error ? error.name : "Unknown",
    providerCalls,
  };
  process.exitCode = 1;
} finally {
  let privateSessionRemoved = true;
  let isolatedServerClosed = true;
  try {
    await unlink(sessionPath);
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code !== "ENOENT")
      privateSessionRemoved = false;
  }
  try {
    if (app) await app.close();
    else await database.destroy();
  } catch {
    isolatedServerClosed = false;
  }
  if (!privateSessionRemoved || !isolatedServerClosed) {
    report = { status: "FAILED", stage: "cleanup", providerCalls };
    process.exitCode = 1;
  }
  await writeFile(
    reportPath,
    JSON.stringify({ ...report, privateSessionRemoved, isolatedServerClosed }),
    { mode: 0o600, flag: "wx" },
  ).catch(() => {
    process.exitCode = 1;
  });
  process.stdout.write(`${JSON.stringify({ pid: process.pid, reportPath })}\n`);
}
