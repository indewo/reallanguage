export type ExistingAiChargeAttempt = Readonly<{
  providerAttemptId: string;
  status:
    "RESERVED" | "SETTLED" | "RELEASED" | "RECONCILIATION_REQUIRED" | "INVALID";
  modelSource: string | null;
  providerCostMicroCny: number | null;
}>;
export interface ExistingAiChargeReader {
  readOperation(
    accountId: string,
    operationId: string,
  ): Promise<readonly ExistingAiChargeAttempt[]>;
}
