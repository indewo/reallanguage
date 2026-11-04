import type { BillingChargeCallType } from "./get-billing-summary.js";
import type { AiChargeStore } from "./manage-ai-charge.js";
import type { AiResultDeliveryStore } from "../../ai/public.js";

export type ProviderAttemptQuote = Readonly<{
  providerAttemptId: string;
  maximumChargeFen: number;
}>;
export type ProviderCostReceipt = Readonly<{
  providerAttemptId: string;
  modelSource: string;
  providerCostMicroCny: number;
}>;
export type MeteredProviderCommand = Readonly<{
  accountId: string;
  operationId: string;
  operationLabelZh: string;
  commandId: string;
  task: string;
  callType: BillingChargeCallType;
  input: unknown;
}>;
export interface MeteredProviderGateway {
  quote(command: MeteredProviderCommand): Promise<ProviderAttemptQuote>;
  execute(
    command: MeteredProviderCommand & { providerAttemptId: string },
  ): Promise<{ output: unknown; receipt: ProviderCostReceipt }>;
}
export interface MeteredAiChargeStore extends AiChargeStore {
  requireReconciliation(providerAttemptId: string): Promise<boolean>;
}

export class AiBalanceInsufficientError extends Error {
  public readonly code = "AI_BALANCE_INSUFFICIENT";
  public constructor() {
    super("AI balance is insufficient for the supplier attempt");
  }
}
export class AiChargeReconciliationRequiredError extends Error {
  public readonly code = "AI_CHARGE_RECONCILIATION_REQUIRED";
  public constructor() {
    super("AI supplier attempt requires billing reconciliation");
  }
}

export class AiDeliveryUnavailableError extends Error {
  public readonly code: string;
  public constructor(status: "PROCESSING" | "CONFLICT" | "EXPIRED" | "FAILED") {
    super("AI result delivery is not available");
    this.code =
      status === "CONFLICT"
        ? "IDEMPOTENCY_KEY_REUSED"
        : `AI_OPERATION_${status}`;
  }
}

export class RunMeteredProviderAttempt {
  public constructor(
    private readonly charges: MeteredAiChargeStore,
    private readonly gateway: MeteredProviderGateway,
    private readonly deliveries: AiResultDeliveryStore,
  ) {}

  public async execute(command: MeteredProviderCommand): Promise<unknown> {
    const quote = await this.gateway.quote(command);
    if (
      !quote.providerAttemptId ||
      !Number.isSafeInteger(quote.maximumChargeFen) ||
      quote.maximumChargeFen <= 0
    )
      throw new AiChargeReconciliationRequiredError();
    const reservation = await this.charges.reserve({
      accountId: command.accountId,
      providerAttemptId: quote.providerAttemptId,
      operationId: command.operationId,
      operationLabelZh: command.operationLabelZh,
      callType: command.callType,
      maximumChargeFen: quote.maximumChargeFen,
    });
    if (!reservation) throw new AiBalanceInsufficientError();
    if (
      reservation.status === "RELEASED" ||
      reservation.status === "RECONCILIATION_REQUIRED"
    )
      throw new AiChargeReconciliationRequiredError();
    let result: Awaited<ReturnType<MeteredProviderGateway["execute"]>>;
    const identity = {
      accountId: command.accountId,
      commandId: command.commandId,
      task: command.task,
      providerAttemptId: quote.providerAttemptId,
      request: {
        input: command.input,
        operationId: command.operationId,
        callType: command.callType,
      },
    };
    const delivery = await this.deliveries.claim(identity);
    if (delivery.status === "COMPLETED") {
      // Only authenticated, account-scoped stored envelopes enter this path.
      result = delivery.result as typeof result;
    } else if (delivery.status !== "CLAIMED") {
      throw new AiDeliveryUnavailableError(delivery.status);
    } else {
      // A historical settlement without a stored response is not permission to spend again.
      if (reservation.status === "SETTLED") {
        await this.deliveries.fail(identity);
        throw new AiChargeReconciliationRequiredError();
      }
      try {
        result = await this.gateway.execute({
          ...command,
          providerAttemptId: quote.providerAttemptId,
        });
      } catch (error) {
        await this.charges.requireReconciliation(quote.providerAttemptId);
        await this.deliveries.fail(identity);
        throw error;
      }
    }
    if (
      !result ||
      !result.receipt ||
      result.receipt.providerAttemptId !== quote.providerAttemptId ||
      !result.receipt.modelSource ||
      !Number.isSafeInteger(result.receipt.providerCostMicroCny) ||
      result.receipt.providerCostMicroCny <= 0
    ) {
      await this.charges.requireReconciliation(quote.providerAttemptId);
      await this.deliveries.fail(identity);
      throw new AiChargeReconciliationRequiredError();
    }
    // Save before settlement: a lost settlement response can be retried without another AI call.
    if (
      delivery.status === "CLAIMED" &&
      !(await this.deliveries.complete(identity, result))
    )
      throw new AiChargeReconciliationRequiredError();
    const settlement = await this.charges.settle(result.receipt);
    if (!settlement || settlement.status !== "SETTLED")
      throw new AiChargeReconciliationRequiredError();
    return result.output;
  }
}
