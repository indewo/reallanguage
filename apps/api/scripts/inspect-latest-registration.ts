import { readFile } from "node:fs/promises";
import { resolve } from "node:path";

import { PGlite } from "@electric-sql/pglite";

const projectRoot = process.cwd();
const captured = JSON.parse(
  await readFile(
    resolve(projectRoot, ".local-data/local-mailbox/latest.json"),
    "utf8",
  ),
) as { verificationUrl: string };
const verificationUrl = new URL(captured.verificationUrl);
const registrationId = verificationUrl.pathname.split("/").at(-3);
if (!registrationId) throw new Error("Captured registration ID is missing");

const database = new PGlite(
  resolve(projectRoot, ".local-data/reallanguage-db"),
);
try {
  const challenge = await database.query<{
    status: string;
    expires_at: string;
    consumed_at: string | null;
    superseded_at: string | null;
  }>(
    `select status, expires_at, consumed_at, superseded_at
       from identity.verification_challenges
      where challenge_id = $1`,
    [registrationId],
  );
  const accounts = await database.query<{ count: number }>(
    `select count(*)::int as count
       from identity.accounts
      where email_verified_at is not null`,
  );
  process.stdout.write(
    JSON.stringify({
      challenge: challenge.rows[0] ?? null,
      verifiedAccountCount: accounts.rows[0]?.count ?? 0,
      inspectedAt: new Date().toISOString(),
    }) + "\n",
  );
} finally {
  await database.close();
}
