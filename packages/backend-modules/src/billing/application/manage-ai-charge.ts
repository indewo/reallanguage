import type { BillingChargeCallType } from "./get-billing-summary.js";

export type AiReservationStatus =
  "RESERVED" | "SETTLED" | "RELEASED" | "RECONCILIATION_REQUIRED";
export type AiReservationResult = Readonly<{
  status: AiReservationStatus;
  reservedFen: number;
}>;
export type AiSettlementResult = Readonly<{
  status: "SETTLED" | "RECONCILIATION_REQUIRED";
  chargedFen: number | null;
}>;
export interface AiChargeStore {
  reserve(input: {
    accountId: string;
    providerAttemptId: string;
    operationId: string;
    operationLabelZh: string;
    callType: BillingChargeCallType;
    maximumChargeFen: number;
  }): Promise<AiReservationResult | null>;
  settle(input: {
    providerAttemptId: string;
    modelSource: string;
    providerCostMicroCny: number;
  }): Promise<AiSettlementResult | null>;
  release(providerAttemptId: string): Promise<boolean>;
  requireReconciliation(providerAttemptId: string): Promise<boolean>;
}

export class ReserveAiAttempt {
  public constructor(
    private readonly charges: Pick<AiChargeStore, "reserve">,
  ) {}
  public execute(command: Parameters<AiChargeStore["reserve"]>[0]) {
    if (
      !Number.isSafeInteger(command.maximumChargeFen) ||
      command.maximumChargeFen <= 0 ||
      !command.providerAttemptId ||
      !command.operationId ||
      !command.operationLabelZh
    )
      return Promise.resolve(null);
    return this.charges.reserve(command);
  }
}

export class SettleAiAttempt {
  public constructor(private readonly charges: Pick<AiChargeStore, "settle">) {}
  public execute(command: Parameters<AiChargeStore["settle"]>[0]) {
    if (
      !Number.isSafeInteger(command.providerCostMicroCny) ||
      command.providerCostMicroCny < 0 ||
      !command.modelSource ||
      !command.providerAttemptId
    )
      return Promise.resolve(null);
    return this.charges.settle(command);
  }
}

export class ReleaseUnsentAiAttempt {
  public constructor(
    private readonly charges: Pick<AiChargeStore, "release">,
  ) {}
  public execute(command: { providerAttemptId: string }) {
    return command.providerAttemptId
      ? this.charges.release(command.providerAttemptId)
      : Promise.resolve(false);
  }
}
