import { PGlite } from "@electric-sql/pglite";
import { migrateToLatest } from "@reallanguage/database";
import { Kysely, PGliteDialect, sql } from "kysely";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import {
  KyselyBillingSummaryReader,
  type BillingSummaryDatabase,
} from "./kysely-billing-summary-reader.js";

const accountId = "40000000-0000-4000-8000-000000000001";
const otherId = "40000000-0000-4000-8000-000000000002";
let database: Kysely<BillingSummaryDatabase>;
beforeEach(async () => {
  database = new Kysely({
    dialect: new PGliteDialect({ pglite: new PGlite() }),
  });
  await migrateToLatest(database);
  for (const id of [accountId, otherId])
    await sql`insert into identity.accounts
      (account_id,birth_year_month_ciphertext,birth_year_month_algorithm,birth_year_month_key_version,age_eligible,age_eligibility_confirmed_at,email_verified_at)
      values (${id},decode('01','hex'),'AES-256-GCM/v1',1,true,now(),now())`.execute(
      database,
    );
});
afterEach(async () => database.destroy());

describe("KyselyBillingSummaryReader", () => {
  it("aggregates complete days before limiting groups and respects the requested zone", async () => {
    await sql`insert into billing.pricing_rules values
      ('40000000-0000-4000-8000-000000000010',2,1,'CEILING',0,'2026-01-01',now())`.execute(
      database,
    );
    await sql`insert into billing.ledger_entries
      select gen_random_uuid(),s.billing_subject_id,w.wallet_id,'AI_CHARGE',-1,'CNY',
        'PROVIDER_ATTEMPT','daily-'||n,'daily-'||n,
        case when n=503 then '2026-11-23T15:59:00Z'::timestamptz else '2026-11-23T16:00:00Z'::timestamptz end
      from billing.billing_subjects s join billing.wallets w using(billing_subject_id),generate_series(1,503) n
      where s.user_id=${accountId}`.execute(database);
    await sql`insert into billing.ai_usage_charges
      select business_ref_id,billing_subject_id,wallet_id,ledger_entry_id,'operation',
        case when business_ref_id='daily-502' then '英文发音' else '提取学习目标' end,
        'provider/model','INITIAL_CALL',125000,1,'40000000-0000-4000-8000-000000000010',posted_at
      from billing.ledger_entries where business_ref_id like 'daily-%'`.execute(
      database,
    );
    const reader = new KyselyBillingSummaryReader(database);
    const shanghai = await reader.readDaily(accountId, "Asia/Shanghai");
    expect(shanghai?.dailyCharges).toEqual(
      expect.arrayContaining([
        {
          date: "2026-11-24",
          operationLabelZh: "提取学习目标",
          count: 501,
          amountFen: 501,
        },
        {
          date: "2026-11-24",
          operationLabelZh: "英文发音",
          count: 1,
          amountFen: 1,
        },
        {
          date: "2026-11-23",
          operationLabelZh: "提取学习目标",
          count: 1,
          amountFen: 1,
        },
      ]),
    );
    expect(shanghai?.dailyCharges).toHaveLength(3);
    const utc = await reader.readDaily(accountId, "UTC");
    expect(
      utc?.dailyCharges.find((row) => row.operationLabelZh === "提取学习目标"),
    ).toMatchObject({ date: "2026-11-23", count: 502, amountFen: 502 });
    expect(
      (await reader.readDaily(otherId, "Asia/Shanghai"))?.dailyCharges,
    ).toEqual([]);
  });
  it("returns available CNY and newest actual charges for one account only", async () => {
    await sql`insert into billing.pricing_rules values
      ('40000000-0000-4000-8000-000000000010',2,1,'CEILING',0,'2026-01-01',now())`.execute(
      database,
    );
    const owned = await sql<{ billing_subject_id: string; wallet_id: string }>`
      select s.billing_subject_id,w.wallet_id from billing.billing_subjects s join billing.wallets w using (billing_subject_id) where s.user_id=${accountId}`.execute(
      database,
    );
    const other = await sql<{ billing_subject_id: string; wallet_id: string }>`
      select s.billing_subject_id,w.wallet_id from billing.billing_subjects s join billing.wallets w using (billing_subject_id) where s.user_id=${otherId}`.execute(
      database,
    );
    await sql`update billing.wallets set posted_balance_fen=1000,reserved_balance_fen=200 where wallet_id=${owned.rows[0]!.wallet_id}`.execute(
      database,
    );
    for (const [attempt, subject, wallet, time] of [
      [
        "owned",
        owned.rows[0]!.billing_subject_id,
        owned.rows[0]!.wallet_id,
        "2026-09-02",
      ],
      [
        "other",
        other.rows[0]!.billing_subject_id,
        other.rows[0]!.wallet_id,
        "2026-09-03",
      ],
    ]) {
      await sql`insert into billing.ledger_entries values
        (gen_random_uuid(),${subject},${wallet},'AI_CHARGE',-25,'CNY','PROVIDER_ATTEMPT',${attempt},${attempt},${time})`.execute(
        database,
      );
      const ledger = await sql<{
        ledger_entry_id: string;
      }>`select ledger_entry_id from billing.ledger_entries where business_ref_id=${attempt}`.execute(
        database,
      );
      await sql`insert into billing.ai_usage_charges values
        (${attempt},${subject},${wallet},${ledger.rows[0]!.ledger_entry_id},'operation','生成英语表达','provider/model','USER_RETRY',125000,25,'40000000-0000-4000-8000-000000000010',${time})`.execute(
        database,
      );
    }
    await expect(
      new KyselyBillingSummaryReader(database).read(accountId),
    ).resolves.toMatchObject({
      balanceAmountFen: 800,
      charges: [
        {
          chargeId: "owned",
          amountFen: 25,
          modelSource: "provider/model",
          callType: "USER_RETRY",
        },
      ],
    });
  });
});
