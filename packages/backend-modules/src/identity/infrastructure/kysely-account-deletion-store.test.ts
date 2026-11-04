import { PGlite } from "@electric-sql/pglite";
import { migrateToLatest } from "@reallanguage/database";
import { Kysely, PGliteDialect, sql } from "kysely";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import {
  KyselyAccountDeletionStore,
  type AccountDeletionDatabase,
} from "./kysely-account-deletion-store.js";
const accountId = "30000000-0000-4000-8000-000000000001";
let database: Kysely<AccountDeletionDatabase>;
beforeEach(async () => {
  database = new Kysely({
    dialect: new PGliteDialect({ pglite: new PGlite() }),
  });
  await migrateToLatest(database);
  await sql`insert into identity.accounts (account_id,birth_year_month_ciphertext,birth_year_month_algorithm,birth_year_month_key_version,age_eligible,age_eligibility_confirmed_at,email_verified_at) values (${accountId},decode('01','hex'),'AES-256-GCM/v1',1,true,now(),now())`.execute(
    database,
  );
  await sql`insert into messaging.app_messages (message_id,user_id,kind,summary_zh) values ('10000000-0000-4000-8000-000000000001',${accountId},'SYSTEM','通知')`.execute(
    database,
  );
});
afterEach(async () => database.destroy());
describe("KyselyAccountDeletionStore", () => {
  it("atomically removes the owned account and cascading personal data", async () => {
    const store = new KyselyAccountDeletionStore(database);
    await expect(store.delete(accountId, "delete-1")).resolves.toBe(true);
    expect(
      (
        await sql`select * from identity.accounts where account_id=${accountId}`.execute(
          database,
        )
      ).rows,
    ).toHaveLength(0);
    expect(
      (
        await sql`select * from messaging.app_messages where user_id=${accountId}`.execute(
          database,
        )
      ).rows,
    ).toHaveLength(0);
  });
});
