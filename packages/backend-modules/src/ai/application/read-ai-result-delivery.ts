export type ExistingAiDeliveryQuery = Readonly<{
  accountId: string;
  task: "SENTENCE_EXPLANATION_V1" | "WORD_EXPLANATION_V1";
  commandId: string;
  historicalAttemptIds: readonly string[];
}>;
export type ExistingAiDelivery =
  | { status: "COMPLETED"; providerAttemptId: string; result: unknown }
  | {
      status:
        | "NO_DELIVERY"
        | "LEGACY_UNAVAILABLE"
        | "PROCESSING"
        | "FAILED"
        | "EXPIRED"
        | "INVALID_DELIVERY";
    };
/** Read-only capability: deliberately cannot claim, extend, execute or settle. */
export interface AiResultDeliveryReader {
  readExisting(query: ExistingAiDeliveryQuery): Promise<ExistingAiDelivery>;
}
