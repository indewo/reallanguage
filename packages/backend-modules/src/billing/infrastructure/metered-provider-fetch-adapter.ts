import type { RunMeteredProviderAttempt } from "../application/run-metered-provider-attempt.js";

export class MeteredProviderFetchAdapter {
  public constructor(
    private readonly attempts: Pick<RunMeteredProviderAttempt, "execute">,
    private readonly operationLabelsZh: Readonly<Record<string, string>>,
  ) {}

  public readonly fetch: typeof fetch = async (_input, init) => {
    const headers = new Headers(init?.headers);
    const accountId = headers.get("X-RealLanguage-Account-Id")?.trim();
    const commandId = headers.get("Idempotency-Key")?.trim();
    const callType = headers.get("X-RealLanguage-Call-Type") ?? "INITIAL_CALL";
    const body = parseBody(init?.body);
    const task = typeof body.task === "string" ? body.task : "";
    const operationLabelZh = this.operationLabelsZh[task];
    if (
      !accountId ||
      !commandId ||
      !task ||
      !operationLabelZh ||
      !isCallType(callType) ||
      !("input" in body)
    )
      throw new Error("Metered provider request metadata is incomplete");
    const output = await this.attempts.execute({
      accountId,
      operationId: commandId,
      operationLabelZh,
      commandId,
      task,
      callType,
      input: body.input,
    });
    return new Response(JSON.stringify(output), {
      status: 200,
      headers: { "Content-Type": "application/json" },
    });
  };
}

const parseBody = (body: unknown) => {
  if (typeof body !== "string")
    throw new Error("Metered provider request body must be JSON");
  const value: unknown = JSON.parse(body);
  if (!value || typeof value !== "object" || Array.isArray(value))
    throw new Error("Metered provider request body must be an object");
  return value as Record<string, unknown>;
};

const isCallType = (
  value: string,
): value is
  "INITIAL_CALL" | "AUTOMATIC_RETRY" | "USER_RETRY" | "PROVIDER_SWITCH" =>
  ["INITIAL_CALL", "AUTOMATIC_RETRY", "USER_RETRY", "PROVIDER_SWITCH"].includes(
    value,
  );
