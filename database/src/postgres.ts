import { Kysely, PostgresDialect } from "kysely";
import { Pool, type PoolConfig } from "pg";

export type PostgresConnectionSettings = Readonly<{
  connectionString: string;
  applicationName: string;
  maxConnections: number;
  connectionTimeoutMs: number;
  idleTimeoutMs: number;
  ssl: PoolConfig["ssl"];
}>;

export const createPostgresPool = (
  settings: PostgresConnectionSettings,
): Pool => {
  if (!settings.connectionString.trim()) {
    throw new Error("PostgreSQL connection string is required");
  }
  if (!settings.applicationName.trim()) {
    throw new Error("PostgreSQL application name is required");
  }
  assertPositiveInteger(settings.maxConnections, "maximum connections");
  assertPositiveInteger(
    settings.connectionTimeoutMs,
    "connection timeout milliseconds",
  );
  assertPositiveInteger(settings.idleTimeoutMs, "idle timeout milliseconds");

  return new Pool({
    connectionString: settings.connectionString,
    application_name: settings.applicationName,
    max: settings.maxConnections,
    connectionTimeoutMillis: settings.connectionTimeoutMs,
    idleTimeoutMillis: settings.idleTimeoutMs,
    ssl: settings.ssl,
  });
};

export const createPostgresDatabase = <Database>(
  pool: Pool,
): Kysely<Database> =>
  new Kysely<Database>({
    dialect: new PostgresDialect({ pool }),
  });

const assertPositiveInteger = (value: number, label: string): void => {
  if (!Number.isInteger(value) || value < 1) {
    throw new Error(`PostgreSQL ${label} must be a positive integer`);
  }
};
