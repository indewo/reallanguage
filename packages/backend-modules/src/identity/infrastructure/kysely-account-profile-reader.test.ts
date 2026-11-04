import { PGlite } from "@electric-sql/pglite";
import { migrateToLatest } from "@reallanguage/database";
import { Kysely, PGliteDialect, sql } from "kysely";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { AesGcmAccountSensitiveFieldsProtector } from "./aes-gcm-account-sensitive-fields-protector.js";
import {
  KyselyAccountProfileReader,
  type AccountProfileDatabase,
} from "./kysely-account-profile-reader.js";

const accountId = "30000000-0000-4000-8000-000000000001";
let database: Kysely<AccountProfileDatabase>;
const fields = new AesGcmAccountSensitiveFieldsProtector({
  activeEncryptionKey: {
    key: Uint8Array.from({ length: 32 }, () => 7),
    version: 1,
  },
  decryptionKeys: [],
});
beforeEach(async () => {
  database = new Kysely({
    dialect: new PGliteDialect({ pglite: new PGlite() }),
  });
  await migrateToLatest(database);
  const email = await fields.protectEmail("learner@example.com");
  await sql`insert into identity.accounts (account_id,birth_year_month_ciphertext,birth_year_month_algorithm,birth_year_month_key_version,age_eligible,age_eligibility_confirmed_at,email_verified_at) values (${accountId},decode('01','hex'),'AES-256-GCM/v1',1,true,now(),now())`.execute(
    database,
  );
  await sql`insert into identity.auth_identities (account_id,normalized_email_index,normalized_email_index_key_version,email_ciphertext,email_algorithm,email_key_version,password_hash,password_hash_version,verified_at) values (${accountId},decode('02','hex'),1,${email.ciphertext},${email.algorithm},${email.keyVersion},'hash',1,now())`.execute(
    database,
  );
});
afterEach(async () => database.destroy());
describe("KyselyAccountProfileReader", () => {
  it("decrypts only the owned verified email", async () => {
    const reader = new KyselyAccountProfileReader(database, fields);
    await expect(reader.read(accountId)).resolves.toEqual({
      email: "learner@example.com",
    });
    await expect(
      reader.read("40000000-0000-4000-8000-000000000001"),
    ).resolves.toBeNull();
  });
});
