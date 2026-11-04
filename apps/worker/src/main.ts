import { randomUUID } from "node:crypto";
import {
  ExecuteApprovedRefund,
  HttpPaymentStatusProvider,
  HttpRefundProvider,
  KyselyBillingCommandStore,
  ReconcilePendingPayments,
  ReconcileApprovedRefunds,
  type BillingCommandDatabase,
  type PaymentReconciliationSummary,
  type RefundReconciliationSummary,
} from "@reallanguage/backend-modules/billing";
import { GetHealth } from "@reallanguage/backend-modules/platform-health";
import {
  createPostgresDatabase,
  createPostgresPool,
} from "@reallanguage/database";

export const workerHealth = () => new GetHealth().execute("worker");

export type RefundWorkerApplication = Readonly<{
  execute(command: { limit: number }): Promise<RefundReconciliationSummary>;
}>;
export type PaymentWorkerApplication = Readonly<{
  execute(command: { limit: number }): Promise<PaymentReconciliationSummary>;
}>;
export type BillingWorkerSummary = Readonly<{
  refunds: RefundReconciliationSummary;
  payments: PaymentReconciliationSummary;
}>;

export const runRefundWorkerOnce = (
  application: RefundWorkerApplication,
  batchSize: number,
) => application.execute({ limit: batchSize });

export const runBillingWorkerOnce = async (
  refunds: RefundWorkerApplication,
  payments: PaymentWorkerApplication,
  batchSize: number,
): Promise<BillingWorkerSummary> => ({
  refunds: await refunds.execute({ limit: batchSize }),
  payments: await payments.execute({ limit: batchSize }),
});

export const runProductionRefundWorkerOnce = async (
  environment: NodeJS.ProcessEnv = process.env,
): Promise<BillingWorkerSummary> => {
  const config = parseRefundWorkerConfig(environment);
  const pool = createPostgresPool({
    connectionString: config.databaseUrl,
    applicationName: "reallanguage-refund-worker",
    maxConnections: 3,
    connectionTimeoutMs: 5_000,
    idleTimeoutMs: 30_000,
    ssl: config.databaseSsl,
  });
  const database = createPostgresDatabase<BillingCommandDatabase>(pool);
  try {
    const store = new KyselyBillingCommandStore(database, {
      next: randomUUID,
    });
    const refunds = new ReconcileApprovedRefunds(
      store,
      new ExecuteApprovedRefund(
        store,
        new HttpRefundProvider({
          endpoint: config.refundProviderUrl,
          apiKey: config.paymentProviderApiKey,
        }),
      ),
    );
    const payments = new ReconcilePendingPayments(
      store,
      new HttpPaymentStatusProvider({
        endpoint: config.paymentStatusProviderUrl,
        apiKey: config.paymentProviderApiKey,
      }),
    );
    return await runBillingWorkerOnce(refunds, payments, config.batchSize);
  } finally {
    await database.destroy();
  }
};

export const parseRefundWorkerConfig = (environment: NodeJS.ProcessEnv) => {
  const databaseUrl = required(environment, "DATABASE_URL");
  const refundProviderUrl = requiredHttpsUrl(
    environment,
    "PAYMENT_REFUND_PROVIDER_URL",
  );
  const paymentStatusProviderUrl = requiredHttpsUrl(
    environment,
    "PAYMENT_STATUS_PROVIDER_URL",
  );
  const paymentProviderApiKey = required(
    environment,
    "PAYMENT_PROVIDER_API_KEY",
  );
  const batchSize = positiveInteger(environment.REFUND_BATCH_SIZE, 25);
  return {
    databaseUrl,
    databaseSsl: environment.DATABASE_SSL !== "false",
    refundProviderUrl,
    paymentStatusProviderUrl,
    paymentProviderApiKey,
    batchSize,
  } as const;
};

const required = (environment: NodeJS.ProcessEnv, name: string): string => {
  const value = environment[name]?.trim();
  if (!value) throw new Error(`${name} is required`);
  return value;
};

const requiredHttpsUrl = (
  environment: NodeJS.ProcessEnv,
  name: string,
): string => {
  const value = required(environment, name);
  const url = new URL(value);
  if (url.protocol !== "https:") throw new Error(`${name} must use HTTPS`);
  return url.toString();
};

const positiveInteger = (value: string | undefined, fallback: number) => {
  if (value === undefined) return fallback;
  const parsed = Number(value);
  if (!Number.isSafeInteger(parsed) || parsed < 1)
    throw new Error("REFUND_BATCH_SIZE must be a positive integer");
  return parsed;
};

if (process.env.NODE_ENV !== "test") {
  void runProductionRefundWorkerOnce().then(
    (summary) => console.info(JSON.stringify({ ...workerHealth(), summary })),
    (error: unknown) => {
      console.error(error instanceof Error ? error.message : "Worker failed");
      process.exitCode = 1;
    },
  );
}
