export type BillingChargeCallType =
  "INITIAL_CALL" | "AUTOMATIC_RETRY" | "USER_RETRY" | "PROVIDER_SWITCH";

export type BillingChargeSnapshot = Readonly<{
  chargeId: string;
  amountFen: number;
  occurredAt: Date;
  modelSource: string;
  callType: BillingChargeCallType;
  operationLabelZh: string;
}>;
export type BillingSummarySnapshot = Readonly<{
  balanceAmountFen: number;
  charges: readonly BillingChargeSnapshot[];
}>;
export interface BillingSummaryReader {
  read(accountId: string): Promise<BillingSummarySnapshot | null>;
}
export class GetBillingSummary {
  public constructor(private readonly billing: BillingSummaryReader) {}
  public execute(query: { accountId: string }) {
    return this.billing.read(query.accountId);
  }
}
