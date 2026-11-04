import { randomUUID } from "node:crypto";
import {
  ExecuteApprovedRefund,
  HttpPaymentStatusProvider,
  HttpRefundProvider,
  KyselyBillingCommandStore,
  ReconcileApprovedRefunds,
  ReconcilePendingPayments,
  type BillingCommandDatabase,
} from "@reallanguage/backend-modules/billing";
import { GetHealth } from "@reallanguage/backend-modules/platform-health";
import {
  createPostgresDatabase,
  createPostgresPool,
} from "@reallanguage/database";

export const schedulerHealth = () => new GetHealth().execute("scheduler");

export type RefundSchedule = Readonly<{
  stop(): Promise<void>;
}>;

export const startRefundSchedule = <Summary>(input: {
  intervalMilliseconds: number;
  run(): Promise<Summary>;
  onResult?(summary: Summary): void;
  onError?(error: unknown): void;
}): RefundSchedule => {
  if (
    !Number.isSafeInteger(input.intervalMilliseconds) ||
    input.intervalMilliseconds < 1_000
  )
    throw new Error("Refund schedule interval must be at least one second");
  let active: Promise<void> | null = null;
  let stopped = false;
  const tick = () => {
    if (stopped || active) return;
    active = input
      .run()
      .then((summary) => input.onResult?.(summary))
      .catch((error: unknown) => input.onError?.(error))
      .finally(() => {
        active = null;
      });
  };
  tick();
  const timer = setInterval(tick, input.intervalMilliseconds);
  timer.unref();
  return {
    async stop() {
      stopped = true;
      clearInterval(timer);
      await active;
    },
  };
};

export const startProductionScheduler = (
  environment: NodeJS.ProcessEnv = process.env,
) => {
  const databaseUrl = required(environment, "DATABASE_URL");
  const providerUrl = httpsUrl(environment, "PAYMENT_REFUND_PROVIDER_URL");
  const paymentStatusProviderUrl = httpsUrl(
    environment,
    "PAYMENT_STATUS_PROVIDER_URL",
  );
  const providerApiKey = required(environment, "PAYMENT_PROVIDER_API_KEY");
  const intervalMilliseconds = positiveInteger(
    environment.REFUND_RECONCILIATION_INTERVAL_MS,
    60_000,
  );
  const batchSize = positiveInteger(environment.REFUND_BATCH_SIZE, 25);
  const pool = createPostgresPool({
    connectionString: databaseUrl,
    applicationName: "reallanguage-scheduler",
    maxConnections: 3,
    connectionTimeoutMs: 5_000,
    idleTimeoutMs: 30_000,
    ssl: environment.DATABASE_SSL !== "false",
  });
  const database = createPostgresDatabase<BillingCommandDatabase>(pool);
  const store = new KyselyBillingCommandStore(database, { next: randomUUID });
  const reconciliation = new ReconcileApprovedRefunds(
    store,
    new ExecuteApprovedRefund(
      store,
      new HttpRefundProvider({ endpoint: providerUrl, apiKey: providerApiKey }),
    ),
  );
  const paymentReconciliation = new ReconcilePendingPayments(
    store,
    new HttpPaymentStatusProvider({
      endpoint: paymentStatusProviderUrl,
      apiKey: providerApiKey,
    }),
  );
  const schedule = startRefundSchedule({
    intervalMilliseconds,
    run: async () => ({
      refunds: await reconciliation.execute({ limit: batchSize }),
      payments: await paymentReconciliation.execute({ limit: batchSize }),
    }),
    onResult: (summary) =>
      console.info(JSON.stringify({ ...schedulerHealth(), summary })),
    onError: (error) =>
      console.error(
        error instanceof Error ? error.message : "Refund schedule failed",
      ),
  });
  return {
    async stop() {
      await schedule.stop();
      await database.destroy();
    },
  };
};

const required = (environment: NodeJS.ProcessEnv, name: string) => {
  const value = environment[name]?.trim();
  if (!value) throw new Error(`${name} is required`);
  return value;
};
const httpsUrl = (environment: NodeJS.ProcessEnv, name: string) => {
  const value = new URL(required(environment, name));
  if (value.protocol !== "https:") throw new Error(`${name} must use HTTPS`);
  return value.toString();
};
const positiveInteger = (value: string | undefined, fallback: number) => {
  if (value === undefined) return fallback;
  const parsed = Number(value);
  if (!Number.isSafeInteger(parsed) || parsed < 1)
    throw new Error("Scheduler numeric configuration must be positive");
  return parsed;
};

if (process.env.NODE_ENV !== "test") {
  const runtime = startProductionScheduler();
  const stop = () => {
    void runtime.stop().finally(() => process.exit(0));
  };
  process.once("SIGTERM", stop);
  process.once("SIGINT", stop);
}
