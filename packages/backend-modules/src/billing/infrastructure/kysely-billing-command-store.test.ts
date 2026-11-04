import { PGlite } from "@electric-sql/pglite";
import { migrateToLatest } from "@reallanguage/database";
import { Kysely, PGliteDialect, sql } from "kysely";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ExecuteApprovedRefund } from "../application/process-refund.js";
import {
  KyselyBillingCommandStore,
  type BillingCommandDatabase,
} from "./kysely-billing-command-store.js";

const accountId = "50000000-0000-4000-8000-000000000001";
let database: Kysely<BillingCommandDatabase>;
let nextId = 10;
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
});
afterEach(async () => database.destroy());

describe("KyselyBillingCommandStore", () => {
  it("prepares recharge idempotently and calculates only refundable paid balance", async () => {
    const store = new KyselyBillingCommandStore(database, {
      next: () =>
        `50000000-0000-4000-8000-${String(nextId++).padStart(12, "0")}`,
    });
    const first = await store.prepare({
      accountId,
      commandId: "pay-1",
      amountFen: 5_000,
      paymentMethod: "WECHAT_PAY",
    });
    expect(first).toMatchObject({ status: "CREATED", existing: false });
    const replay = await store.prepare({
      accountId,
      commandId: "pay-1",
      amountFen: 5_000,
      paymentMethod: "WECHAT_PAY",
    });
    expect(replay).toMatchObject({ orderId: first!.orderId, existing: true });
    expect(
      await store.prepare({
        accountId,
        commandId: "pay-1",
        amountFen: 5_001,
        paymentMethod: "WECHAT_PAY",
      }),
    ).toBeNull();
    await store.markPending({
      orderId: first!.orderId,
      providerOrderId: "provider-1",
      paymentLaunch: "launch",
    });
    await expect(store.listPendingPayments(25)).resolves.toEqual([
      {
        merchantOrderId: first!.orderId,
        providerOrderId: "provider-1",
        paymentMethod: "WECHAT_PAY",
        amountFen: 5_000,
      },
    ]);
    const event = {
      providerEventId: "event-1",
      providerOrderId: "provider-1",
      merchantOrderId: first!.orderId,
      paymentMethod: "WECHAT_PAY" as const,
      amountFen: 5_000,
      currency: "CNY" as const,
      status: "PAID" as const,
    };
    await expect(store.apply(event)).resolves.toBe(true);
    await expect(store.apply(event)).resolves.toBe(true);
    await expect(store.listPendingPayments(25)).resolves.toEqual([]);
    const support = await store.requestMinorRechargeSupport({
      accountId,
      commandId: "minor-support-1",
    });
    await expect(
      store.requestMinorRechargeSupport({
        accountId,
        commandId: "minor-support-1",
      }),
    ).resolves.toEqual(support);
    expect(support).toMatchObject({ status: "REQUESTED" });
    const wallet = await sql<{
      posted_balance_fen: string;
    }>`select posted_balance_fen from billing.wallets
      where billing_subject_id=(select billing_subject_id from billing.billing_subjects where user_id=${accountId})`.execute(
      database,
    );
    expect(Number(wallet.rows[0]!.posted_balance_fen)).toBe(5_000);
    await expect(store.read(accountId)).resolves.toMatchObject({
      refundableAmountFen: 5_000,
      refundMethodLabelZh: "原路退回至微信支付",
    });
    await expect(
      store.request({ accountId, amountFen: 5_000, commandId: "refund-1" }),
    ).resolves.toMatchObject({ status: "REQUESTED" });
    await expect(store.read(accountId)).resolves.toMatchObject({
      refundableAmountFen: 0,
      requestStatus: "REQUESTED",
    });
    await expect(
      store.review({
        requestId: (await store.read(accountId))!.requestId!,
        decision: "APPROVE",
        reviewerId: "reviewer-1",
      }),
    ).resolves.toMatchObject({ status: "APPROVED" });
    const providerRefund = vi
      .fn()
      .mockResolvedValue({ providerRefundId: "provider-refund-1" });
    const execution = new ExecuteApprovedRefund(store, {
      refund: providerRefund,
    });
    const requestId = (await store.read(accountId))!.requestId!;
    await expect(store.listApproved(25)).resolves.toEqual([requestId]);
    await expect(execution.execute({ requestId })).resolves.toBe(true);
    await expect(execution.execute({ requestId })).resolves.toBe(true);
    await expect(store.listApproved(25)).resolves.toEqual([]);
    expect(providerRefund).toHaveBeenCalledTimes(1);
    const settledWallet = await sql<{
      posted_balance_fen: string;
      reserved_balance_fen: string;
    }>`select posted_balance_fen, reserved_balance_fen from billing.wallets
      where billing_subject_id=(select billing_subject_id from billing.billing_subjects where user_id=${accountId})`.execute(
      database,
    );
    expect(Number(settledWallet.rows[0]!.posted_balance_fen)).toBe(0);
    expect(Number(settledWallet.rows[0]!.reserved_balance_fen)).toBe(0);
    await expect(store.read(accountId)).resolves.toMatchObject({
      refundableAmountFen: 0,
      requestStatus: "REFUNDED",
    });
  });
});
