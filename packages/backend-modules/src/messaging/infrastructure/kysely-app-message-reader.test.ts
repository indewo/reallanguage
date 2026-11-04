import { PGlite } from "@electric-sql/pglite";
import { migrateToLatest } from "@reallanguage/database";
import { Kysely, PGliteDialect, sql } from "kysely";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import {
  KyselyAppMessageReader,
  type MessagingDatabase,
} from "./kysely-app-message-reader.js";

const accountId = "30000000-0000-4000-8000-000000000001";
const otherId = "30000000-0000-4000-8000-000000000002";
let database: Kysely<MessagingDatabase>;

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

describe("KyselyAppMessageReader", () => {
  it("isolates ownership, omits expired rows, and allows only controlled targets", async () => {
    await sql`insert into messaging.app_messages
      (message_id,user_id,kind,summary_zh,related_content_label_zh,target_route,target_resource_id,expires_at,created_at)
      values
      ('10000000-0000-4000-8000-000000000001',${accountId},'MANUAL_REVIEW_RESULT','通过','日记','MANUAL_REVIEW_RESULT','review-1',null,'2026-09-02'),
      ('10000000-0000-4000-8000-000000000002',${accountId},'SYSTEM','过期',null,null,null,'2026-09-01','2026-08-31'),
      ('10000000-0000-4000-8000-000000000003',${otherId},'SYSTEM','他人',null,null,null,null,'2026-09-03')`.execute(
      database,
    );
    const result = await new KyselyAppMessageReader(database, {
      now: () => new Date("2026-09-02T12:00:00Z"),
    }).list(accountId);
    expect(result).toHaveLength(1);
    expect(result[0]).toMatchObject({
      summaryZh: "通过",
      target: { route: "MANUAL_REVIEW_RESULT", resourceId: "review-1" },
    });
  });
  it("marks only unread rows owned by the requested account", async () => {
    await sql`insert into messaging.app_messages
      (message_id,user_id,kind,summary_zh,read_at)
      values
      ('10000000-0000-4000-8000-000000000011',${accountId},'SYSTEM','未读',null),
      ('10000000-0000-4000-8000-000000000012',${accountId},'SYSTEM','已读','2026-09-01'),
      ('10000000-0000-4000-8000-000000000013',${otherId},'SYSTEM','他人未读',null)`.execute(
      database,
    );
    const store = new KyselyAppMessageReader(database, {
      now: () => new Date(),
    });

    await expect(
      store.markAllRead(accountId, new Date("2026-09-06T03:00:00Z")),
    ).resolves.toBe(1);
    const rows = await sql<{ user_id: string; read_at: Date | null }>`
      select user_id, read_at from messaging.app_messages order by message_id
    `.execute(database);
    expect(rows.rows[0]?.read_at).not.toBeNull();
    expect(rows.rows[1]?.read_at).not.toBeNull();
    expect(rows.rows[2]?.read_at).toBeNull();
  });
});
