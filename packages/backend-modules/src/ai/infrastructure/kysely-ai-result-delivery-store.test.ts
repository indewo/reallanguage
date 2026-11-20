import { PGlite } from "@electric-sql/pglite";
import { migrateToLatest } from "@reallanguage/database";
import { Kysely, PGliteDialect, sql } from "kysely";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import {
  KyselyAiResultDeliveryStore,
  purgeExpiredAiResults,
} from "./kysely-ai-result-delivery-store.js";

const accountId = "61000000-0000-4000-8000-000000000001";
const otherAccountId = "61000000-0000-4000-8000-000000000002";
const key = { key: new Uint8Array(32).fill(7), version: 1 };
const identity = {
  accountId,
  commandId: "command-1",
  task: "TASK_V1",
  providerAttemptId: "attempt-1",
  request: { text: "private diary", order: 1 },
};
let database: Kysely<unknown>;
let now: Date;
const store = () =>
  new KyselyAiResultDeliveryStore(database, key, { now: () => now });
beforeEach(async () => {
  now = new Date("2026-09-07T00:00:00Z");
  database = new Kysely({
    dialect: new PGliteDialect({ pglite: new PGlite() }),
  });
  await migrateToLatest(database);
  for (const id of [accountId, otherAccountId])
    await sql`insert into identity.accounts
      (account_id,birth_year_month_ciphertext,birth_year_month_algorithm,birth_year_month_key_version,age_eligible,age_eligibility_confirmed_at,email_verified_at)
      values (${id},decode('01','hex'),'AES-256-GCM/v1',1,true,now(),now())`.execute(
      database,
    );
});
afterEach(async () => database.destroy());

describe("encrypted temporary AI result delivery", () => {
  it("permits speech regeneration only after exact completed audio expires, or exact failure, without reopening deliveries", async () => {
    const speech = { ...identity, task: "TTS_RENDER_V1" };
    expect(await store().canRegenerateSpeech(speech)).toBe(false);
    await store().claim(speech);
    expect(await store().canRegenerateSpeech(speech)).toBe(false);
    const expiresAtMs = now.getTime() + 3600000;
    await store().complete(speech, { output: { audioObjectRef: "tts-private", expiresAtMs }, receipt: {} });
    expect(await store().canRegenerateSpeech(speech)).toBe(false);
    now = new Date(expiresAtMs);
    expect(await store().canRegenerateSpeech(speech)).toBe(true);
    expect(await store().canRegenerateSpeech({ ...speech, accountId: otherAccountId })).toBe(false);
    expect(await store().canRegenerateSpeech({ ...speech, request: { text: "different" } })).toBe(false);
    expect(await store().canRegenerateSpeech({ ...speech, providerAttemptId: "other" })).toBe(false);
    expect((await store().claim(speech)).status).toBe("COMPLETED");
    now = new Date(now.getTime() + 86400000);
    await purgeExpiredAiResults(database, now);
    expect(await store().canRegenerateSpeech(speech)).toBe(true);
    expect(await store().claim(speech)).toEqual({ status: "EXPIRED" });
    const failed = { ...speech, commandId: "failed-speech", providerAttemptId: "failed-attempt" };
    await store().claim(failed);
    await store().fail(failed);
    expect(await store().canRegenerateSpeech(failed)).toBe(true);
    expect(await store().canRegenerateSpeech(identity)).toBe(false);
  }, 30000);
  it("authorizes explicit retry only for the exact failed owner request without reopening it", async () => {
    expect(await store().isFailed(identity)).toBe(false);
    await store().claim(identity);
    expect(await store().isFailed(identity)).toBe(false);
    await store().fail(identity);
    expect(await store().isFailed(identity)).toBe(true);
    expect(await store().isFailed({ ...identity, accountId: otherAccountId })).toBe(false);
    expect(await store().isFailed({ ...identity, request: { text: "different" } })).toBe(false);
    expect(await store().isFailed({ ...identity, providerAttemptId: "other" })).toBe(false);
    expect(await store().claim(identity)).toEqual({ status: "FAILED" });
  }, 30_000);
  it("removes only the deleted account's payloads and rejects late saves", async () => {
    await store().claim(identity);
    await store().complete(identity, { text: "private" });
    const second = {
      ...identity,
      accountId: otherAccountId,
      providerAttemptId: "attempt-2",
    };
    await store().claim(second);
    await sql`delete from identity.accounts where account_id=${accountId}`.execute(
      database,
    );
    const rows = await sql<{
      user_id: string;
    }>`select user_id from ai.result_deliveries`.execute(database);
    expect(rows.rows).toEqual([{ user_id: otherAccountId }]);
    expect(await store().complete(identity, { text: "late" })).toBe(false);
  });
  it("elects exactly one executor across separate store instances", async () => {
    const claims = await Promise.all([
      store().claim(identity),
      store().claim(identity),
    ]);
    expect(claims.map((value) => value.status).sort()).toEqual([
      "CLAIMED",
      "PROCESSING",
    ]);
  });

  it("reads the first encrypted result in a fresh instance and rejects changed input", async () => {
    await store().claim(identity);
    const result = {
      output: { text: "private English result" },
      receipt: { cost: 2 },
    };
    expect(await store().complete(identity, result)).toBe(true);
    expect(
      await store().claim({
        ...identity,
        request: { order: 1, text: "private diary" },
      }),
    ).toEqual({ status: "COMPLETED", result });
    expect(
      await store().claim({
        ...identity,
        request: { text: "different input" },
      }),
    ).toEqual({ status: "CONFLICT" });
    expect(await store().complete(identity, { output: "replacement" })).toBe(
      false,
    );
    const rows = await sql<{
      result_ciphertext: Uint8Array;
      request_digest: string;
    }>`select result_ciphertext,request_digest from ai.result_deliveries`.execute(
      database,
    );
    expect(
      Buffer.from(rows.rows[0]!.result_ciphertext).toString("utf8"),
    ).not.toContain("private English result");
    expect(JSON.stringify(rows.rows)).not.toContain("private diary");
  });

  it("isolates accounts and authenticates ciphertext against the command identity", async () => {
    await store().claim(identity);
    await store().complete(identity, { text: "account one's private output" });
    expect(
      await store().claim({ ...identity, accountId: otherAccountId }),
    ).toEqual({ status: "CONFLICT" });
    const second = {
      ...identity,
      accountId: otherAccountId,
      providerAttemptId: "attempt-2",
    };
    expect(await store().claim(second)).toEqual({ status: "CLAIMED" });
    await sql`update ai.result_deliveries set
      result_ciphertext=(select result_ciphertext from ai.result_deliveries where provider_attempt_id='attempt-1'),
      algorithm='AES-256-GCM/HKDF-SHA256/v1',key_version=1,status='COMPLETED'
      where provider_attempt_id='attempt-2'`.execute(database);
    await expect(store().claim(second)).rejects.toThrow();
  });

  it("clears expired bodies without making the paid command executable again", async () => {
    await store().claim(identity);
    await store().complete(identity, { text: "temporary" });
    now = new Date(now.getTime() + 24 * 60 * 60 * 1000);
    expect(await purgeExpiredAiResults(database, now)).toBe(1);
    expect(await store().claim(identity)).toEqual({ status: "EXPIRED" });
    const result = await sql<{
      result_ciphertext: unknown;
    }>`select result_ciphertext from ai.result_deliveries`.execute(database);
    expect(result.rows[0]!.result_ciphertext).toBeNull();
    expect(await store().complete(identity, { text: "late result" })).toBe(
      false,
    );
  });

  it("retains unknown execution failure and refuses a late result after expiry", async () => {
    await store().claim(identity);
    await store().fail(identity);
    expect(await store().claim(identity)).toEqual({ status: "FAILED" });
    expect(await store().complete(identity, {})).toBe(false);
    const second = {
      ...identity,
      commandId: "command-2",
      providerAttemptId: "attempt-2",
    };
    await store().claim(second);
    now = new Date(now.getTime() + 24 * 60 * 60 * 1000);
    expect(await store().complete(second, {})).toBe(false);
    expect(await store().claim(second)).toEqual({ status: "EXPIRED" });
  });
});
