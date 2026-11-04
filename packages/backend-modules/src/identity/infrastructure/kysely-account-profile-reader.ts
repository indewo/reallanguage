import type { Kysely } from "kysely";
import type { AccountProfileReader } from "../application/get-account-profile.js";
import type { ProtectedAccountField } from "./kysely-verified-account-creator.js";

interface AuthIdentitiesTable {
  account_id: string;
  email_ciphertext: Uint8Array;
  email_algorithm: string;
  email_key_version: number;
}
export interface AccountProfileDatabase {
  "identity.auth_identities": AuthIdentitiesTable;
}
export interface AccountEmailUnprotector {
  unprotectEmail(field: ProtectedAccountField): Promise<string>;
}

export class KyselyAccountProfileReader implements AccountProfileReader {
  public constructor(
    private readonly database: Kysely<AccountProfileDatabase>,
    private readonly fields: AccountEmailUnprotector,
  ) {}
  public async read(accountId: string) {
    const row = await this.database
      .selectFrom("identity.auth_identities")
      .select(["email_ciphertext", "email_algorithm", "email_key_version"])
      .where("account_id", "=", accountId)
      .executeTakeFirst();
    if (!row) return null;
    return {
      email: await this.fields.unprotectEmail({
        ciphertext: row.email_ciphertext,
        algorithm: row.email_algorithm,
        keyVersion: row.email_key_version,
      }),
    };
  }
}
