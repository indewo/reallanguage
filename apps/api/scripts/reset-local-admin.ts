import "reflect-metadata";

import { resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { PGlite } from "@electric-sql/pglite";
import { AdminCryptography } from "@reallanguage/backend-modules/admin";

const expectedEmail = "indewo@outlook.com";
const projectRoot = fileURLToPath(new URL("../../../", import.meta.url));
const database = new PGlite(
  resolve(projectRoot, ".local-data/reallanguage-db"),
);
const cryptography = new AdminCryptography({
  encryptionKey: { key: bytes(9), version: 1 },
  digestKey: { key: bytes(10), version: 1 },
});

try {
  const emailIndex = cryptography.indexEmail(expectedEmail);
  const accounts = await database.query<{ admin_id: string }>(
    `select admin_id from admin.accounts
       where email_index = $1 and email_index_key_version = $2`,
    [Buffer.from(emailIndex.digest), emailIndex.keyVersion],
  );
  if (accounts.rows.length !== 1) {
    throw new Error("Expected exactly one matching local administrator");
  }
  const adminId = accounts.rows[0]?.admin_id;
  if (!adminId) throw new Error("Matching local administrator has no id");
  const decisions = await database.query<{ count: string }>(
    `select count(*)::text as count from safety.review_decisions
       where reviewer_admin_id = $1`,
    [adminId],
  );
  if (decisions.rows[0]?.count !== "0") {
    throw new Error("Refusing to reset an administrator with review history");
  }
  const sessions = await database.query<{ count: string }>(
    `select count(*)::text as count from admin.sessions where admin_id = $1`,
    [adminId],
  );

  await database.exec("begin");
  try {
    const deleted = await database.query(
      "delete from admin.accounts where admin_id = $1",
      [adminId],
    );
    if (deleted.affectedRows !== 1) {
      throw new Error("Administrator reset did not delete exactly one account");
    }
    await database.exec("commit");
  } catch (error) {
    await database.exec("rollback");
    throw error;
  }
  console.info(
    `Reset one unused local administrator and ${sessions.rows[0]?.count ?? "0"} session(s).`,
  );
} finally {
  await database.close();
}

function bytes(value: number): Uint8Array {
  return Uint8Array.from({ length: 32 }, () => value);
}
