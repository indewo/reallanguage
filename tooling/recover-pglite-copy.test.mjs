import { test } from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, readFile } from "node:fs/promises";
import { PGlite } from "../node_modules/.pnpm/@electric-sql+pglite@0.5.8/node_modules/@electric-sql/pglite/dist/index.js";
import { resetCopyWal } from "./recover-pglite-copy.mjs";

test("PG18 reset preserves a checkpointed row and database can reopen", async () => {
  const root = await mkdtemp(".local-data/db-recovery-test-");
  const path = `${root}/database`;
  const db = new PGlite(path);
  await db.exec(
    "CREATE TABLE recovery_probe(id integer primary key); INSERT INTO recovery_probe VALUES (42); CHECKPOINT;",
  );
  await db.close();
  const before = await readFile(`${path}/global/pg_control`);
  assert.equal(before.readUInt32LE(8), 1800);
  await resetCopyWal(path);
  const reopened = new PGlite(path);
  try {
    assert.deepEqual(
      (await reopened.query("SELECT id FROM recovery_probe")).rows,
      [{ id: 42 }],
    );
  } finally {
    await reopened.close();
  }
});

test("refuses the live database path", async () => {
  await assert.rejects(
    resetCopyWal(".local-data/reallanguage-db"),
    /recovery copy/,
  );
});
