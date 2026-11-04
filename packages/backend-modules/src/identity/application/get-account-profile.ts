export type AccountProfile = Readonly<{ email: string }>;
export interface AccountProfileReader {
  read(accountId: string): Promise<AccountProfile | null>;
}
export class GetAccountProfile {
  public constructor(private readonly profiles: AccountProfileReader) {}
  public execute(query: { accountId: string }) {
    return this.profiles.read(query.accountId);
  }
}
