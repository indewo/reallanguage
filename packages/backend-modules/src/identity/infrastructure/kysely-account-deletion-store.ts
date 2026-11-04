import type { Kysely } from "kysely";
import type { AccountDeletionStore } from "../application/delete-account.js";
interface AccountsTable {
  account_id: string;
}
interface MessagesTable {
  message_id: string;
  user_id: string;
  kind: string;
  summary_zh: string;
}
export interface AccountDeletionDatabase {
  "identity.accounts": AccountsTable;
  "messaging.app_messages": MessagesTable;
}
export class KyselyAccountDeletionStore implements AccountDeletionStore {
  public constructor(
    private readonly database: Kysely<AccountDeletionDatabase>,
  ) {}
  public async delete(accountId: string, commandId: string): Promise<boolean> {
    void commandId;
    const result = await this.database
      .deleteFrom("identity.accounts")
      .where("account_id", "=", accountId)
      .executeTakeFirst();
    return Number(result.numDeletedRows) === 1;
  }
}
