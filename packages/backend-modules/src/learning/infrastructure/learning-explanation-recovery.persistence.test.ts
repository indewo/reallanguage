import { PGlite } from "@electric-sql/pglite";
import { migrateToLatest } from "@reallanguage/database";
import { Kysely, PGliteDialect, sql } from "kysely";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { KyselyAiResultDeliveryStore } from "../../ai/public.js";
import {
  KyselyAiChargeStore,
  KyselyExistingAiChargeReader,
  type AiChargeDatabase,
} from "../../billing/public.js";
import { RecoverLearningExplanation } from "../application/recover-learning-explanation.js";

const accountId = "79000000-0000-4000-8000-000000000001";
const otherAccount = "79000000-0000-4000-8000-000000000002";
const commandId = "original-paid-explanation";
const providerAttemptId = "original-paid-attempt";
const key = { key: new Uint8Array(32).fill(19), version: 1 };
const identity = {
  accountId,
  commandId,
  providerAttemptId,
  task: "WORD_EXPLANATION_V1" as const,
  request: {
    input: {
      settings: "original-only",
      requestedExpression: { expressionId: "word-2", display: "tea" },
    },
    operationId: commandId,
    callType: "INITIAL_CALL",
  },
};
const receipt = {
  providerAttemptId,
  modelSource: "isolated/model",
  providerCostMicroCny: 112000,
};
const output = {
  kind: "EXPRESSION",
  expressionId: "word-2",
  explanationZh: "旧完整解释。",
  phonetic: null,
};
let database: Kysely<unknown>;
let now: Date;
const delivery = (source = key) =>
  new KyselyAiResultDeliveryStore(database, source, { now: () => now });
const billing = () =>
  new KyselyAiChargeStore(
    database as Kysely<AiChargeDatabase>,
    { next: () => crypto.randomUUID() },
    { now: () => now },
  );
const recover = (
  account = accountId,
  kind: "EXPRESSION" | "SENTENCE" = "EXPRESSION",
) =>
  new RecoverLearningExplanation(
    delivery(),
    new KyselyExistingAiChargeReader(database),
  ).execute({ accountId: account, commandId, kind });
const snapshot = async () => {
  const result = await sql<{ value: unknown }>`select jsonb_build_object(
    'wallets',(select jsonb_agg(to_jsonb(t) order by wallet_id) from billing.wallets t),
    'reservations',(select jsonb_agg(to_jsonb(t) order by provider_attempt_id) from billing.ai_charge_reservations t),
    'usage',(select jsonb_agg(to_jsonb(t) order by provider_attempt_id) from billing.ai_usage_charges t),
    'ledger',(select jsonb_agg(to_jsonb(t) order by ledger_entry_id) from billing.ledger_entries t),
    'deliveries',(select jsonb_agg(to_jsonb(t) order by command_id) from ai.result_deliveries t)) as value`.execute(
    database,
  );
  return result.rows[0]!.value;
};
const reserve = async () =>
  billing().reserve({
    accountId,
    providerAttemptId,
    operationId: commandId,
    operationLabelZh: "生成表达讲解",
    callType: "INITIAL_CALL",
    maximumChargeFen: 80,
  });
const save = async (
  result: unknown = output,
  settled = true,
  savedReceipt: unknown = receipt,
) => {
  await reserve();
  await delivery().claim(identity);
  await delivery().complete(identity, {
    output: result,
    receipt: savedReceipt,
    providerHeaders: { mustNeverBeDelivered: "isolated-private-header" },
  });
  if (settled) await billing().settle(receipt);
};
beforeEach(async () => {
  now = new Date("2026-09-09T00:00:00Z");
  database = new Kysely({
    dialect: new PGliteDialect({ pglite: new PGlite() }),
  });
  await migrateToLatest(database);
  for (const id of [accountId, otherAccount])
    await sql`insert into identity.accounts(account_id,birth_year_month_ciphertext,birth_year_month_algorithm,birth_year_month_key_version,age_eligible,age_eligibility_confirmed_at,email_verified_at) values (${id},decode('01','hex'),'AES-256-GCM/v1',1,true,now(),now())`.execute(
      database,
    );
  await sql`insert into billing.pricing_rules(pricing_rule_id,multiplier_numerator,multiplier_denominator,rounding_mode,minimum_charge_fen,effective_at) values ('79000000-0000-4000-8000-000000000003',2,1,'CEILING_TO_FEN',1,'2026-01-01')`.execute(
    database,
  );
  await sql`update billing.wallets set posted_balance_fen=1000`.execute(
    database,
  );
});
afterEach(async () => database.destroy());
describe("read-only historical explanation recovery using real isolated SQL and ciphertext", () => {
  it("recovers the settled legacy result in a fresh instance without settings or any write", async () => {
    await save();
    const before = await snapshot();
    expect(await recover()).toEqual({
      status: "COMPLETED",
      commandId,
      provenance: "EXISTING_DELIVERY",
      result: output,
    });
    expect(await recover()).toEqual(await recover());
    expect(await snapshot()).toEqual(before);
  });
  it("does not claim, settle or invoke anything when no delivery exists", async () => {
    const before = await snapshot();
    const claim = vi.spyOn(KyselyAiResultDeliveryStore.prototype, "claim");
    const reserveAttempt = vi.spyOn(KyselyAiChargeStore.prototype, "reserve");
    const settleAttempt = vi.spyOn(KyselyAiChargeStore.prototype, "settle");
    expect(await recover()).toEqual({ status: "NO_DELIVERY" });
    expect(await snapshot()).toEqual(before);
    expect(claim).not.toHaveBeenCalled();
    expect(reserveAttempt).not.toHaveBeenCalled();
    expect(settleAttempt).not.toHaveBeenCalled();
    vi.restoreAllMocks();
  });
  it("distinguishes missing delivery with a previously charged operation", async () => {
    await reserve();
    await billing().settle(receipt);
    const before = await snapshot();
    expect(await recover()).toEqual({ status: "NO_DELIVERY_WITH_ATTEMPT" });
    expect(await snapshot()).toEqual(before);
  });
  it("finds old migration tombstones through the original owned billing operation", async () => {
    await reserve();
    await billing().settle(receipt);
    await sql`insert into ai.result_deliveries(user_id,task,command_id,provider_attempt_id,request_digest,status,created_at,expires_at) values (${accountId},'LEGACY_UNAVAILABLE',${providerAttemptId},${providerAttemptId},'legacy-unavailable','FAILED',${now},${new Date(now.getTime() + 86400000)})`.execute(
      database,
    );
    const before = await snapshot();
    expect(await recover()).toEqual({ status: "LEGACY_UNAVAILABLE" });
    expect(await snapshot()).toEqual(before);
  });
  it.each(["PROCESSING", "FAILED", "EXPIRED"] as const)(
    "returns %s without creating a replacement",
    async (status) => {
      await reserve();
      await delivery().claim(identity);
      if (status === "FAILED") await delivery().fail(identity);
      if (status === "EXPIRED") now = new Date(now.getTime() + 86400000);
      const before = await snapshot();
      expect(await recover()).toEqual({ status });
      expect(await snapshot()).toEqual(before);
    },
  );
  it("never decrypts expired ciphertext even before the scheduled purge", async () => {
    await save();
    now = new Date(now.getTime() + 86400000);
    await sql`update ai.result_deliveries set result_ciphertext=decode('00','hex')`.execute(
      database,
    );
    const before = await snapshot();
    expect(await recover()).toEqual({ status: "EXPIRED" });
    expect(await snapshot()).toEqual(before);
  });
  it("never settles a completed but reserved receipt", async () => {
    await save(output, false);
    const before = await snapshot();
    expect(await recover()).toEqual({ status: "PENDING_SETTLEMENT" });
    expect(await snapshot()).toEqual(before);
  });
  it.each(["RELEASED", "RECONCILIATION_REQUIRED"] as const)(
    "does not bypass %s billing",
    async (status) => {
      await save(output, false);
      if (status === "RELEASED") await billing().release(providerAttemptId);
      else await billing().requireReconciliation(providerAttemptId);
      const before = await snapshot();
      expect(await recover()).toEqual({ status });
      expect(await snapshot()).toEqual(before);
    },
  );
  it.each([
    { ...receipt, providerAttemptId: "different" },
    { ...receipt, providerCostMicroCny: 1 },
    { ...receipt, modelSource: "different" },
  ])(
    "rejects a settled receipt that does not match the authentic envelope %j",
    async (savedReceipt) => {
      await save(output, true, savedReceipt);
      const before = await snapshot();
      expect(await recover()).toEqual({ status: "BILLING_UNVERIFIED" });
      expect(await snapshot()).toEqual(before);
    },
  );
  it("does not disclose another account or reinterpret the other explanation task", async () => {
    await save();
    const before = await snapshot();
    expect(await recover(otherAccount)).toEqual({ status: "NO_DELIVERY" });
    expect(await recover(accountId, "SENTENCE")).toEqual({
      status: "NO_DELIVERY_WITH_ATTEMPT",
    });
    expect(await snapshot()).toEqual(before);
  });
  it.each([
    {},
    { ...output, kind: "SENTENCE" },
    { ...output, contextualMeaningZh: "茶" },
    { ...output, explanationZh: " " },
  ])(
    "rejects invalid business output despite a paid receipt %j",
    async (invalid) => {
      await save(invalid);
      const before = await snapshot();
      expect(await recover()).toEqual({ status: "INVALID_RESULT" });
      expect(await snapshot()).toEqual(before);
    },
  );
  it("authenticates ciphertext against original command metadata, not the current input", async () => {
    await save();
    await sql`update ai.result_deliveries set request_digest='tampered'`.execute(
      database,
    );
    const before = await snapshot();
    expect(await recover()).toEqual({ status: "INVALID_DELIVERY" });
    expect(await snapshot()).toEqual(before);
  });
  it("does not reveal ciphertext under a wrong key version", async () => {
    await save();
    const before = await snapshot();
    expect(
      await new RecoverLearningExplanation(
        delivery({ ...key, version: 2 }),
        new KyselyExistingAiChargeReader(database),
      ).execute({ accountId, commandId, kind: "EXPRESSION" }),
    ).toEqual({ status: "INVALID_DELIVERY" });
    expect(await snapshot()).toEqual(before);
  });
  it("rejects an empty phonetic rather than returning an unusable successful result", async () => {
    await save({ ...output, phonetic: "" });
    const before = await snapshot();
    expect(await recover()).toEqual({ status: "INVALID_RESULT" });
    expect(await snapshot()).toEqual(before);
  });
  it("refuses a settled reservation without the matching posted usage evidence", async () => {
    await save();
    await sql`update billing.ai_usage_charges set provider_cost_micro_cny=1`.execute(
      database,
    );
    const before = await snapshot();
    expect(await recover()).toEqual({ status: "BILLING_UNVERIFIED" });
    expect(await snapshot()).toEqual(before);
  });
  it("does not call any charging operation when returning an already paid delivery", async () => {
    await save();
    const before = await snapshot();
    const claim = vi.spyOn(KyselyAiResultDeliveryStore.prototype, "claim");
    const reserveAttempt = vi.spyOn(KyselyAiChargeStore.prototype, "reserve");
    const settleAttempt = vi.spyOn(KyselyAiChargeStore.prototype, "settle");
    expect((await recover()).status).toBe("COMPLETED");
    expect(claim).not.toHaveBeenCalled();
    expect(reserveAttempt).not.toHaveBeenCalled();
    expect(settleAttempt).not.toHaveBeenCalled();
    expect(await snapshot()).toEqual(before);
    vi.restoreAllMocks();
  });
});
