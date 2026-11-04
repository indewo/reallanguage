// One-off recovery: logical copy into a NEW database; never overwrite the source.
import { lstat } from "node:fs/promises";
import { PGlite } from "../apps/api/node_modules/@electric-sql/pglite/dist/index.js";
import {
  Kysely,
  PGliteDialect,
} from "../apps/api/node_modules/kysely/dist/index.js";
import { migrateToLatest } from "../database/src/migrate.ts";

const sourcePath = ".local-data/db-recovery-kcfI5u/database";
const destinationPath = ".local-data/db-recovery-kcfI5u/rebuilt";
try {
  await lstat(destinationPath);
  throw new Error("Destination already exists; refusing overwrite");
} catch (error) {
  if ((error as NodeJS.ErrnoException).code !== "ENOENT") throw error;
}
const source = new PGlite(sourcePath);
const destination = new PGlite(destinationPath);
const schema = new Kysely({
  dialect: new PGliteDialect({ pglite: destination }),
});
const quote = (value: string) => '"' + value.replaceAll('"', '""') + '"';
try {
  await source.exec("SET default_transaction_read_only=on");
  await migrateToLatest(schema);
  const { rows: tables } = await source.query<{
    schemaname: string;
    tablename: string;
  }>(
    "SELECT schemaname,tablename FROM pg_tables WHERE schemaname NOT IN ('pg_catalog','information_schema','public') ORDER BY schemaname,tablename",
  );
  await destination.transaction(async (tx) => {
    // Disable side-effect triggers on the NEW DB during logical restore only.
    await tx.exec("SET LOCAL session_replication_role=replica");
    for (const table of tables) {
      const name = quote(table.schemaname) + "." + quote(table.tablename);
      const data = await source.query<{ payload: string }>(
        `SELECT coalesce(jsonb_agg(to_jsonb(t)), '[]'::jsonb)::text AS payload FROM ${name} t`,
      );
      await tx.exec(`DELETE FROM ${name}`);
      await tx.query(
        `INSERT INTO ${name} SELECT * FROM jsonb_populate_recordset(NULL::${name}, $1::jsonb)`,
        [data.rows[0]!.payload],
      );
      const restored = await tx.query<{ payload: string }>(
        `SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text), '[]'::jsonb)::text AS payload FROM ${name} t`,
      );
      const original = await source.query<{ payload: string }>(
        `SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text), '[]'::jsonb)::text AS payload FROM ${name} t`,
      );
      if (restored.rows[0]!.payload !== original.rows[0]!.payload)
        throw new Error(`Round trip mismatch: ${name}`);
    }
    const { rows: constraints } = await tx.query<{
      name: string;
      child: string;
      parent: string;
      columns: string[];
      references: string[];
    }>(`
      SELECT c.conname AS name, c.conrelid::regclass::text AS child, c.confrelid::regclass::text AS parent,
      ARRAY(SELECT a.attname FROM unnest(c.conkey) WITH ORDINALITY k(num,ord) JOIN pg_attribute a ON a.attrelid=c.conrelid AND a.attnum=k.num ORDER BY k.ord) AS columns,
      ARRAY(SELECT a.attname FROM unnest(c.confkey) WITH ORDINALITY k(num,ord) JOIN pg_attribute a ON a.attrelid=c.confrelid AND a.attnum=k.num ORDER BY k.ord) AS references
      FROM pg_constraint c WHERE c.contype='f'
    `);
    for (const c of constraints) {
      const nonNull = c.columns
        .map((column) => `child.${quote(column)} IS NOT NULL`)
        .join(" AND ");
      const join = c.columns
        .map(
          (column, i) =>
            `child.${quote(column)}=parent.${quote(c.references[i]!)}`,
        )
        .join(" AND ");
      const result = await tx.query<{ count: number }>(
        `SELECT count(*)::int AS count FROM ${c.child} child WHERE ${nonNull} AND NOT EXISTS (SELECT 1 FROM ${c.parent} parent WHERE ${join})`,
      );
      if (result.rows[0]!.count !== 0)
        throw new Error(`Foreign key inconsistency: ${c.name}`);
    }
    console.log(
      JSON.stringify({
        tablesRestoredAndCompared: tables.length,
        foreignKeysChecked: constraints.length,
        violations: 0,
      }),
    );
  });
  const admin = await destination.query(
    "SELECT count(*)::int AS admins, count(*) FILTER (WHERE enabled AND mfa_enrollment_confirmed_at IS NOT NULL)::int AS enabled_mfa_accounts FROM admin.accounts",
  );
  console.log(JSON.stringify(admin.rows[0]));
  await destination.exec("CHECKPOINT");
} finally {
  await source.close();
  await schema.destroy();
}
