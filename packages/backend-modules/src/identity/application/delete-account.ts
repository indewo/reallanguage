export interface AccountDeletionStore {
  delete(accountId: string, commandId: string): Promise<boolean>;
}
export class DeleteAccount {
  public constructor(private readonly accounts: AccountDeletionStore) {}
  public async execute(command: { accountId: string; commandId: string }) {
    if (!command.commandId.trim())
      throw new Error("Deletion command identity required");
    return (await this.accounts.delete(command.accountId, command.commandId))
      ? { status: "DELETED" as const }
      : null;
  }
}
