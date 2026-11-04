import { describe, expect, it } from "vitest";

import { createPostgresDatabase, createPostgresPool } from "./postgres.js";

const settings = {
  connectionString: "postgresql://app:secret@localhost:5432/reallanguage",
  applicationName: "reallanguage-api-test",
  maxConnections: 8,
  connectionTimeoutMs: 3_000,
  idleTimeoutMs: 30_000,
  ssl: { rejectUnauthorized: true },
} as const;

describe("PostgreSQL connection factory", () => {
  it("uses only explicit pool, timeout, identity, and TLS settings", async () => {
    const pool = createPostgresPool(settings);

    expect(pool.options).toMatchObject({
      connectionString: settings.connectionString,
      application_name: settings.applicationName,
      max: settings.maxConnections,
      connectionTimeoutMillis: settings.connectionTimeoutMs,
      idleTimeoutMillis: settings.idleTimeoutMs,
      ssl: settings.ssl,
    });
    await pool.end();
  });

  it("creates a lazy Kysely handle without opening a connection", async () => {
    const pool = createPostgresPool({ ...settings, ssl: false });
    const database = createPostgresDatabase<unknown>(pool);

    await database.destroy();

    expect(pool.totalCount).toBe(0);
    await pool.end();
  });

  it.each([
    ["connection string", { ...settings, connectionString: "" }],
    ["application name", { ...settings, applicationName: "" }],
    ["pool size", { ...settings, maxConnections: 0 }],
    ["connection timeout", { ...settings, connectionTimeoutMs: 0 }],
    ["idle timeout", { ...settings, idleTimeoutMs: 0 }],
  ])("rejects an invalid %s", (_, invalid) => {
    expect(() => createPostgresPool(invalid)).toThrow();
  });
});
