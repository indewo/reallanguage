import type {
  SemanticUnitCandidate,
  SemanticUnitSplitProvider,
  SplitSemanticUnitsCommand,
} from "../application/split-semantic-units.js";

export class HttpSemanticUnitSplitProvider implements SemanticUnitSplitProvider {
  public constructor(
    private readonly config: Readonly<{ endpoint: string; apiKey: string }>,
    private readonly fetchImplementation: typeof fetch = fetch,
  ) {
    if (!config.endpoint || !config.apiKey)
      throw new Error("Content formation provider configuration is required");
  }

  public async split(
    command: SplitSemanticUnitsCommand,
  ): Promise<readonly SemanticUnitCandidate[]> {
    // The provider caches completed attempts by idempotency key. Version the
    // key when the semantic contract changes so an old coarse-grained result
    // cannot be replayed forever, while retries of this contract stay stable.
    const providerCommandId = `${command.commandId}:minimum-v2`;
    const response = await this.fetchImplementation(this.config.endpoint, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${this.config.apiKey}`,
        "Content-Type": "application/json",
        "Idempotency-Key": providerCommandId,
        "X-RealLanguage-Account-Id": command.accountId,
      },
      body: JSON.stringify({
        task: "SEMANTIC_UNIT_SPLIT_V1",
        input: { text: command.text, contentShape: command.contentShape },
      }),
    });
    if (!response.ok) throw new Error("Semantic split provider request failed");
    const value: unknown = await response.json();
    if (!isRecord(value) || !Array.isArray(value.units))
      throw new Error("Invalid semantic split provider response");
    return value.units.map(parseUnit);
  }
}

const parseUnit = (value: unknown): SemanticUnitCandidate => {
  if (!isRecord(value) || !Array.isArray(value.sourceSpans))
    throw new Error("Invalid semantic unit");
  return {
    unitId: requiredString(value.unitId),
    order: requiredInteger(value.order),
    sourceSpans: value.sourceSpans.map((span) => {
      if (!isRecord(span)) throw new Error("Invalid semantic source span");
      return {
        startCodePoint: requiredInteger(span.startCodePoint),
        endCodePoint: requiredInteger(span.endCodePoint),
      };
    }),
    textZh: requiredString(value.textZh),
    speakerRole:
      value.speakerRole === null ? null : requiredString(value.speakerRole),
    semanticPurposeZh: requiredString(value.semanticPurposeZh),
    recommendationReasonZh:
      value.recommendationReasonZh === null ||
      value.recommendationReasonZh === undefined
        ? null
        : requiredString(value.recommendationReasonZh),
  };
};
const requiredString = (value: unknown): string => {
  if (typeof value !== "string" || !value)
    throw new Error("Missing semantic unit string");
  return value;
};
const requiredInteger = (value: unknown): number => {
  if (!Number.isInteger(value) || Number(value) < 0)
    throw new Error("Invalid semantic unit integer");
  return Number(value);
};
const isRecord = (value: unknown): value is Record<string, unknown> =>
  typeof value === "object" && value !== null;
