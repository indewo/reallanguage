import type {
  BlockedSafetySource,
  ContentDeidentificationProvider,
  DeidentifiedCandidate,
  DeidentificationEdit,
} from "../application/handle-blocked-safety.js";

export class HttpContentDeidentificationProvider implements ContentDeidentificationProvider {
  public constructor(
    private readonly config: Readonly<{ endpoint: string; apiKey: string }>,
    private readonly fetchImplementation: typeof fetch = fetch,
  ) {}

  public async deidentify(
    source: BlockedSafetySource,
    accountId: string,
  ): Promise<DeidentifiedCandidate> {
    const response = await this.fetchImplementation(this.config.endpoint, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${this.config.apiKey}`,
        "Content-Type": "application/json",
        "Idempotency-Key": `deidentify:${source.safetyCaseId}`,
        "X-RealLanguage-Account-Id": accountId,
      },
      body: JSON.stringify({
        task: "CONTENT_DEIDENTIFICATION_V1",
        input: {
          text: source.text,
          contentShape: source.contentShape,
          sourceType: source.sourceType,
          riskSpans: source.riskSpans,
          reasonCode: source.reasonCode,
        },
      }),
    });
    if (!response.ok)
      throw new Error("Deidentification provider request failed");
    const value: unknown = await response.json();
    if (
      !isRecord(value) ||
      !Array.isArray(value.edits) ||
      value.internalSafetyValid !== true
    )
      throw new Error("Invalid deidentification provider response");
    return {
      sanitizedText: requiredString(value.sanitizedText),
      edits: value.edits.map(parseEdit),
      preservedIntentSummaryZh: requiredString(value.preservedIntentSummaryZh),
      internalSafetyValid: true,
      providerOperationId: requiredString(value.providerOperationId),
    };
  }
}

const parseEdit = (value: unknown): DeidentificationEdit => {
  if (!isRecord(value) || !isRecord(value.originalSpan))
    throw new Error("Invalid deidentification edit");
  return {
    originalSpan: {
      startCodePoint: integer(value.originalSpan.startCodePoint),
      endCodePoint: integer(value.originalSpan.endCodePoint),
    },
    originalText: requiredString(value.originalText),
    replacementText: requiredString(value.replacementText),
    reasonCode: requiredString(value.reasonCode),
    explanationZh: requiredString(value.explanationZh),
  };
};
const requiredString = (value: unknown): string => {
  if (typeof value !== "string" || !value)
    throw new Error("Missing deidentification value");
  return value;
};
const integer = (value: unknown): number => {
  if (!Number.isInteger(value))
    throw new Error("Invalid deidentification span");
  return Number(value);
};
const isRecord = (value: unknown): value is Record<string, unknown> =>
  typeof value === "object" && value !== null;
