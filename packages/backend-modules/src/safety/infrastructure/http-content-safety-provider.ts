import type {
  ContentSafetyProvider,
  ContentSafetyProviderOutcome,
  SafetyRiskSpan,
} from "./kysely-content-safety-gateway.js";

type Fetch = typeof fetch;

export type HttpContentSafetyProviderConfig = Readonly<{
  endpoint: string;
  apiKey: string;
}>;

export class HttpContentSafetyProvider implements ContentSafetyProvider {
  public constructor(
    private readonly config: HttpContentSafetyProviderConfig,
    private readonly fetchImplementation: Fetch = fetch,
  ) {
    if (!config.endpoint || !config.apiKey) {
      throw new Error("Safety provider endpoint and API key are required");
    }
  }

  public async check(input: {
    accountId: string;
    operationId: string;
    text: string;
    sourceType: "DIARY" | "RECORDING" | "AI_SCENE";
    contentShape: "ARTICLE" | "DIALOGUE";
  }): Promise<ContentSafetyProviderOutcome> {
    const response = await this.fetchImplementation(this.config.endpoint, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${this.config.apiKey}`,
        "Content-Type": "application/json",
        "Idempotency-Key": input.operationId,
        "X-RealLanguage-Account-Id": input.accountId,
      },
      body: JSON.stringify({
        task: "CONTENT_SAFETY_CHECK_V1",
        input: {
          text: input.text,
          sourceType: input.sourceType,
          contentShape: input.contentShape,
          contextConsidered: true,
        },
      }),
    });
    if (!response.ok) throw new Error("Safety provider request failed");
    return parseOutcome(await response.json(), input.text);
  }
}

const parseOutcome = (
  value: unknown,
  sourceText: string,
): ContentSafetyProviderOutcome => {
  if (!isRecord(value)) throw new Error("Invalid safety provider response");
  const policyVersion = requiredString(value.policyVersion);
  const providerOperationId = requiredString(value.providerOperationId);
  if (value.contextConsidered !== true) {
    throw new Error("Safety provider did not consider full context");
  }
  if (value.decision === "PASS") {
    return {
      decision: "PASS",
      policyVersion,
      providerOperationId,
      contextConsidered: true,
    };
  }
  if (value.decision !== "BLOCK" || !Array.isArray(value.riskSpans)) {
    throw new Error("Invalid safety provider decision");
  }
  const confidence = Number(value.confidence);
  if (!Number.isFinite(confidence) || confidence < 0 || confidence > 1) {
    throw new Error("Invalid safety provider confidence");
  }
  const codePoints = Array.from(sourceText);
  const riskSpans = value.riskSpans.map((candidate) =>
    parseRiskSpan(candidate, codePoints),
  );
  if (riskSpans.length === 0)
    throw new Error("Blocked safety result requires risk spans");
  return {
    decision: "BLOCK",
    policyVersion,
    providerOperationId,
    reasonCode: requiredString(value.reasonCode),
    userReasonZh: requiredString(value.userReasonZh),
    riskSpans,
    confidence,
    contextConsidered: true,
  };
};

const parseRiskSpan = (
  value: unknown,
  source: readonly string[],
): SafetyRiskSpan => {
  if (!isRecord(value)) throw new Error("Invalid safety risk span");
  const startCodePoint = Number(value.startCodePoint);
  const endCodePoint = Number(value.endCodePoint);
  const text = requiredString(value.text);
  if (
    !Number.isInteger(startCodePoint) ||
    !Number.isInteger(endCodePoint) ||
    startCodePoint < 0 ||
    endCodePoint <= startCodePoint ||
    endCodePoint > source.length ||
    source.slice(startCodePoint, endCodePoint).join("") !== text
  ) {
    throw new Error("Safety risk span does not match source text");
  }
  return {
    startCodePoint,
    endCodePoint,
    text,
    reasonCode: requiredString(value.reasonCode),
  };
};

const requiredString = (value: unknown): string => {
  if (typeof value !== "string" || value.length === 0) {
    throw new Error("Required safety provider value is missing");
  }
  return value;
};

const isRecord = (value: unknown): value is Record<string, unknown> =>
  typeof value === "object" && value !== null;
