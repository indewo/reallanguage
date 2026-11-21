export type ExistingAiChargeAttempt = Readonly<{
  providerAttemptId: string;
  status:
    "RESERVED" | "SETTLED" | "RELEASED" | "RECONCILIATION_REQUIRED" | "INVALID";
  modelSource: string | null;
  providerCostMicroCny: number | null;
}>;
export interface ExistingAiChargeReader {
  /** Account-scoped lookup for deliveries grouped under a parent operation. */
  readAttempt?(
    accountId: string,
    providerAttemptId: string,
  ): Promise<readonly ExistingAiChargeAttempt[]>;
  readOperation(
    accountId: string,
    operationId: string,
  ): Promise<readonly ExistingAiChargeAttempt[]>;
}
