import { PGlite } from "@electric-sql/pglite";
import { Kysely, PGliteDialect, sql } from "kysely";
import { migrateToLatest } from "@reallanguage/database";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import type { ProductionDatabase } from "../production/production-api.module.js";
import { enableLocalDevelopmentCredit } from "./local-app.js";

let database: Kysely<ProductionDatabase>;
const accountId = "71000000-0000-4000-8000-000000000001";
beforeEach(async () => {
  database = new Kysely<ProductionDatabase>({
    dialect: new PGliteDialect({ pglite: new PGlite() }),
  });
  await migrateToLatest(database);
});
afterEach(async () => {
  await database.destroy();
});

const createAccount = async (id = accountId) => {
  await sql`insert into identity.accounts
    (account_id,birth_year_month_ciphertext,birth_year_month_algorithm,birth_year_month_key_version,age_eligible,age_eligibility_confirmed_at,email_verified_at)
    values (${id},decode('01','hex'),'AES-256-GCM/v1',1,true,now(),now())`.execute(
    database,
  );
};

describe("local development credit integrity", () => {
  it("never refills an existing exhausted wallet when startup runs again", async () => {
    await enableLocalDevelopmentCredit(database, 10_000);
    await createAccount();
    await sql`update billing.wallets set posted_balance_fen=0`.execute(
      database,
    );
    await enableLocalDevelopmentCredit(database, 10_000);
    const result = await sql<{
      balance: string;
    }>`select posted_balance_fen as balance from billing.wallets`.execute(
      database,
    );
    expect(Number(result.rows[0]!.balance)).toBe(0);
  });

  it("records the new account's one-time test credit as an auditable grant", async () => {
    await enableLocalDevelopmentCredit(database, 10_000);
    await createAccount();
    await enableLocalDevelopmentCredit(database, 10_000);
    const result = await sql<{
      amount: string;
      entry_type: string;
    }>`select amount_fen as amount,entry_type from billing.ledger_entries`.execute(
      database,
    );
    expect(
      result.rows.map((row) => ({
        amount: Number(row.amount),
        type: row.entry_type,
      })),
    ).toEqual([{ amount: 10_000, type: "GRANT" }]);
  });

  it("does not create a positive grant when the configured test credit is zero", async () => {
    await enableLocalDevelopmentCredit(database, 0);
    await createAccount();
    const result = await sql<{
      count: string;
    }>`select count(*) as count from billing.ledger_entries`.execute(database);
    expect(Number(result.rows[0]!.count)).toBe(0);
  });

  it("applies changed credit only to subsequently created accounts", async () => {
    await enableLocalDevelopmentCredit(database, 10_000);
    await createAccount();
    await sql`update billing.wallets set posted_balance_fen=42, reserved_balance_fen=2`.execute(
      database,
    );
    await enableLocalDevelopmentCredit(database, 200);
    await createAccount("71000000-0000-4000-8000-000000000002");
    const result = await sql<{
      balance: string;
      reserved: string;
      grant: string;
    }>`
      select w.posted_balance_fen as balance, w.reserved_balance_fen as reserved, l.amount_fen as grant
      from billing.wallets w join billing.ledger_entries l using (wallet_id)
      join billing.billing_subjects b on b.billing_subject_id=w.billing_subject_id
      order by b.user_id`.execute(database);
    expect(
      result.rows.map((row) => [
        Number(row.balance),
        Number(row.reserved),
        Number(row.grant),
      ]),
    ).toEqual([
      [42, 2, 10_000],
      [200, 0, 200],
    ]);
  });

  it("rolls account and wallet creation back if its grant cannot be recorded", async () => {
    await enableLocalDevelopmentCredit(database, 10_000);
    await sql`create function billing.reject_test_grant() returns trigger language plpgsql as $$
      begin raise exception 'isolated ledger write failure'; end $$`.execute(
      database,
    );
    await sql`create trigger reject_test_grant before insert on billing.ledger_entries
      for each row execute function billing.reject_test_grant()`.execute(
      database,
    );
    await expect(createAccount()).rejects.toThrow(
      "isolated ledger write failure",
    );
    const result = await sql<{
      accounts: string;
      wallets: string;
      subjects: string;
    }>`select
      (select count(*) from identity.accounts) as accounts,
      (select count(*) from billing.wallets) as wallets,
      (select count(*) from billing.billing_subjects) as subjects`.execute(
      database,
    );
    expect(Object.values(result.rows[0]!).map(Number)).toEqual([0, 0, 0]);
  });
});
