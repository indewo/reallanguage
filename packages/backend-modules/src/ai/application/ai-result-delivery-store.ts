export type AiDeliveryIdentity = Readonly<{
  accountId: string;
  commandId: string;
  task: string;
  providerAttemptId: string;
  request: unknown;
}>;

export type AiDeliveryClaim =
  | { status: "CLAIMED" }
  | { status: "COMPLETED"; result: unknown }
  | { status: "PROCESSING" | "CONFLICT" | "EXPIRED" | "FAILED" };

export interface AiResultDeliveryStore {
  claim(identity: AiDeliveryIdentity): Promise<AiDeliveryClaim>;
  complete(identity: AiDeliveryIdentity, result: unknown): Promise<boolean>;
  fail(identity: AiDeliveryIdentity): Promise<void>;
}
