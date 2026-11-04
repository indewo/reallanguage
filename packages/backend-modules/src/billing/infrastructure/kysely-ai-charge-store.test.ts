import { PGlite } from "@electric-sql/pglite";
import { migrateToLatest } from "@reallanguage/database";
import { Kysely, PGliteDialect, sql } from "kysely";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { KyselyAiResultDeliveryStore } from "../../ai/public.js";
import {
  RunMeteredProviderAttempt,
  type MeteredProviderGateway,
} from "../application/run-metered-provider-attempt.js";

import {
  KyselyAiChargeStore,
  type AiChargeDatabase,
} from "./kysely-ai-charge-store.js";

const accountId = "60000000-0000-4000-8000-000000000001";
let database: Kysely<AiChargeDatabase>;
let id = 10;
beforeEach(async () => {
  database = new Kysely({
    dialect: new PGliteDialect({ pglite: new PGlite() }),
  });
  await migrateToLatest(database);
  await sql`insert into identity.accounts
    (account_id,birth_year_month_ciphertext,birth_year_month_algorithm,birth_year_month_key_version,age_eligible,age_eligibility_confirmed_at,email_verified_at)
    values (${accountId},decode('01','hex'),'AES-256-GCM/v1',1,true,now(),now())`.execute(
    database,
  );
  await sql`insert into billing.pricing_rules
    (pricing_rule_id,multiplier_numerator,multiplier_denominator,rounding_mode,minimum_charge_fen,effective_at)
    values ('60000000-0000-4000-8000-000000000002',2,1,'CEILING_TO_FEN',1,'2026-01-01T00:00:00Z')`.execute(
    database,
  );
  await sql`update billing.wallets set posted_balance_fen=1000
    where billing_subject_id=(select billing_subject_id from billing.billing_subjects where user_id=${accountId})`.execute(
    database,
  );
});
afterEach(async () => database.destroy());

describe("KyselyAiChargeStore", () => {
  const request = {
    accountId,
    commandId: "ai-command",
    operationId: "ai-operation",
    operationLabelZh: "生成讲解",
    task: "SENTENCE_EXPLANATION_V1",
    callType: "INITIAL_CALL" as const,
    input: { text: "I felt calm." },
  };
  const runner = (execute: MeteredProviderGateway["execute"]) =>
    new RunMeteredProviderAttempt(
      new KyselyAiChargeStore(database, { next: () => crypto.randomUUID() }),
      {
        quote: async () => ({
          providerAttemptId: "real-database-attempt",
          maximumChargeFen: 80,
        }),
        execute,
      },
      new KyselyAiResultDeliveryStore(database as Kysely<unknown>, {
        key: new Uint8Array(32).fill(9),
        version: 1,
      }),
    );

  it("replays from the actual result and ledger stores after application reconstruction", async () => {
    const execute = vi.fn().mockResolvedValue({
      output: { explanationZh: "这里表示平静。" },
      receipt: {
        providerAttemptId: "real-database-attempt",
        modelSource: "isolated-test-supplier/model",
        providerCostMicroCny: 112_000,
      },
    });
    expect(await runner(execute).execute(request)).toEqual({
      explanationZh: "这里表示平静。",
    });
    expect(await runner(execute).execute(request)).toEqual({
      explanationZh: "这里表示平静。",
    });
    await expect(
      runner(execute).execute({ ...request, input: { text: "different" } }),
    ).rejects.toMatchObject({ code: "IDEMPOTENCY_KEY_REUSED" });
    expect(execute).toHaveBeenCalledTimes(1);
    const wallet = await walletSnapshot();
    expect(Number(wallet.posted_balance_fen)).toBe(977);
    expect(Number(wallet.reserved_balance_fen)).toBe(0);
    const count = await sql<{
      count: string;
    }>`select count(*) as count from billing.ai_usage_charges`.execute(
      database,
    );
    expect(Number(count.rows[0]!.count)).toBe(1);
  });

  it("reserves atomically, settles actual cost once, and releases the difference", async () => {
    const store = new KyselyAiChargeStore(database, {
      next: () => `60000000-0000-4000-8000-${String(id++).padStart(12, "0")}`,
    });
    const command = {
      accountId,
      providerAttemptId: "attempt-1",
      operationId: "operation-1",
      operationLabelZh: "生成英语表达",
      callType: "INITIAL_CALL" as const,
      maximumChargeFen: 80,
    };
    await expect(store.reserve(command)).resolves.toEqual({
      status: "RESERVED",
      reservedFen: 80,
    });
    await expect(store.reserve(command)).resolves.toEqual({
      status: "RESERVED",
      reservedFen: 80,
    });
    await expect(
      store.settle({
        providerAttemptId: "attempt-1",
        modelSource: "provider/model-1",
        providerCostMicroCny: 112_000,
      }),
    ).resolves.toEqual({ status: "SETTLED", chargedFen: 23 });
    await expect(
      store.settle({
        providerAttemptId: "attempt-1",
        modelSource: "provider/model-1",
        providerCostMicroCny: 112_000,
      }),
    ).resolves.toEqual({ status: "SETTLED", chargedFen: 23 });
    const wallet = await walletSnapshot();
    expect(Number(wallet.posted_balance_fen)).toBe(977);
    expect(Number(wallet.reserved_balance_fen)).toBe(0);
    const entries = await sql<{ count: string }>`select count(*) as count
      from billing.ledger_entries where entry_type='AI_CHARGE'`.execute(
      database,
    );
    expect(Number(entries.rows[0]!.count)).toBe(1);
  });

  it("refuses insufficient balance and keeps an overrun reserved for reconciliation", async () => {
    const store = new KyselyAiChargeStore(database, {
      next: () => crypto.randomUUID(),
    });
    await expect(
      store.reserve({
        accountId,
        providerAttemptId: "too-large",
        operationId: "operation-1",
        operationLabelZh: "生成英语表达",
        callType: "INITIAL_CALL",
        maximumChargeFen: 1_001,
      }),
    ).resolves.toBeNull();
    await store.reserve({
      accountId,
      providerAttemptId: "overrun",
      operationId: "operation-2",
      operationLabelZh: "生成英语表达",
      callType: "AUTOMATIC_RETRY",
      maximumChargeFen: 10,
    });
    await expect(
      store.settle({
        providerAttemptId: "overrun",
        modelSource: "provider/model-1",
        providerCostMicroCny: 100_000,
      }),
    ).resolves.toEqual({
      status: "RECONCILIATION_REQUIRED",
      chargedFen: null,
    });
    const wallet = await walletSnapshot();
    expect(Number(wallet.posted_balance_fen)).toBe(1_000);
    expect(Number(wallet.reserved_balance_fen)).toBe(10);
  });

  it("releases only an attempt that was never sent", async () => {
    const store = new KyselyAiChargeStore(database, {
      next: () => crypto.randomUUID(),
    });
    await store.reserve({
      accountId,
      providerAttemptId: "unsent",
      operationId: "operation-3",
      operationLabelZh: "生成英语表达",
      callType: "INITIAL_CALL",
      maximumChargeFen: 50,
    });
    await expect(store.release("unsent")).resolves.toBe(true);
    await expect(store.release("unsent")).resolves.toBe(true);
    const wallet = await walletSnapshot();
    expect(Number(wallet.reserved_balance_fen)).toBe(0);
  });
});

const walletSnapshot = async () => {
  const result = await sql<{
    posted_balance_fen: string;
    reserved_balance_fen: string;
  }>`select posted_balance_fen,reserved_balance_fen from billing.wallets
    where billing_subject_id=(select billing_subject_id from billing.billing_subjects where user_id=${accountId})`.execute(
    database,
  );
  return result.rows[0]!;
};
