export type MinorRechargeSupportStatus =
  "REQUESTED" | "REVIEWING" | "RESOLVED" | "REJECTED";

export type MinorRechargeSupportResult = Readonly<{
  supportCaseId: string;
  status: MinorRechargeSupportStatus;
}>;

export interface MinorRechargeSupportStore {
  requestMinorRechargeSupport(input: {
    accountId: string;
    commandId: string;
  }): Promise<MinorRechargeSupportResult | null>;
}

export class RequestMinorRechargeSupport {
  public constructor(private readonly support: MinorRechargeSupportStore) {}
  public execute(command: { accountId: string; commandId: string }) {
    if (!command.commandId.trim()) return null;
    return this.support.requestMinorRechargeSupport(command);
  }
}
