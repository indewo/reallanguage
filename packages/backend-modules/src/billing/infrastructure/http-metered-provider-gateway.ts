import type {
  MeteredProviderCommand,
  MeteredProviderGateway,
  ProviderAttemptQuote,
  ProviderCostReceipt,
} from "../application/run-metered-provider-attempt.js";

export type MeteredProviderGatewayConfig = Readonly<{
  endpoint: string;
  apiKey: string;
}>;
export interface MeteredProviderHttpResponse {
  ok: boolean;
  json(): Promise<unknown>;
}
export type MeteredProviderFetch = (
  input: string,
  init: {
    method: "POST";
    headers: Record<string, string>;
    body: string;
  },
) => Promise<MeteredProviderHttpResponse>;

export class HttpMeteredProviderGateway implements MeteredProviderGateway {
  public constructor(
    private readonly config: MeteredProviderGatewayConfig,
    private readonly fetcher: MeteredProviderFetch = fetch,
  ) {}

  public async quote(
    command: MeteredProviderCommand,
  ): Promise<ProviderAttemptQuote> {
    const value = await this.post(`${command.commandId}:quote`, {
      phase: "QUOTE",
      task: command.task,
      operationId: command.operationId,
      commandId: command.commandId,
      callType: command.callType,
      input: command.input,
    });
    if (!isQuote(value)) throw new Error("Invalid provider quote");
    return value;
  }

  public async execute(
    command: MeteredProviderCommand & { providerAttemptId: string },
  ) {
    const value = await this.post(command.providerAttemptId, {
      phase: "EXECUTE",
      providerAttemptId: command.providerAttemptId,
      task: command.task,
      operationId: command.operationId,
      commandId: command.commandId,
      callType: command.callType,
      input: command.input,
    });
    if (!isExecution(value))
      throw new Error("Invalid provider execution receipt");
    return value;
  }

  private async post(idempotencyKey: string, body: unknown) {
    const response = await this.fetcher(this.config.endpoint, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${this.config.apiKey}`,
        "Content-Type": "application/json",
        "Idempotency-Key": idempotencyKey,
      },
      body: JSON.stringify(body),
    });
    if (!response.ok) throw new Error("Metered provider request failed");
    return response.json();
  }
}

const isQuote = (value: unknown): value is ProviderAttemptQuote =>
  record(value) &&
  typeof value.providerAttemptId === "string" &&
  value.providerAttemptId.length > 0 &&
  Number.isSafeInteger(value.maximumChargeFen) &&
  Number(value.maximumChargeFen) > 0;

const isReceipt = (value: unknown): value is ProviderCostReceipt =>
  record(value) &&
  typeof value.providerAttemptId === "string" &&
  value.providerAttemptId.length > 0 &&
  typeof value.modelSource === "string" &&
  value.modelSource.length > 0 &&
  Number.isSafeInteger(value.providerCostMicroCny) &&
  Number(value.providerCostMicroCny) > 0;

const isExecution = (
  value: unknown,
): value is { output: unknown; receipt: ProviderCostReceipt } =>
  record(value) && "output" in value && isReceipt(value.receipt);

const record = (value: unknown): value is Record<string, unknown> =>
  typeof value === "object" && value !== null;
