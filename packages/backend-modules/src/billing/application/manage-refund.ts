export type RefundStatus =
  "REQUESTED" | "REVIEWING" | "APPROVED" | "REFUNDED" | "REJECTED" | "FAILED";
export type RefundSnapshot = Readonly<{
  refundableAmountFen: number;
  refundMethodLabelZh: string;
  requestId: string | null;
  requestStatus: RefundStatus | null;
}>;
export type RefundResult = Readonly<{
  requestId: string;
  status: RefundStatus;
}>;
export interface RefundStore {
  read(accountId: string): Promise<RefundSnapshot | null>;
  request(input: {
    accountId: string;
    amountFen: number;
    commandId: string;
  }): Promise<RefundResult | null>;
}
export class GetRefundSnapshot {
  public constructor(private readonly refunds: RefundStore) {}
  public execute(query: { accountId: string }) {
    return this.refunds.read(query.accountId);
  }
}
export class RequestRefund {
  public constructor(private readonly refunds: RefundStore) {}
  public async execute(command: {
    accountId: string;
    amountFen: number;
    commandId: string;
  }) {
    const current = await this.refunds.read(command.accountId);
    if (
      !current ||
      command.amountFen <= 0 ||
      command.amountFen !== current.refundableAmountFen
    )
      return null;
    return this.refunds.request(command);
  }
}
