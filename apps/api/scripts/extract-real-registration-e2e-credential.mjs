import { createDecipheriv } from "node:crypto";
import { readFile, writeFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

import { PGlite } from "@electric-sql/pglite";

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const projectRoot = path.resolve(scriptDir, "../../..");
const statePath = path.join(
  projectRoot,
  ".local-data/real-registration-e2e.json",
);
const state = JSON.parse(await readFile(statePath, "utf8"));
const database = new PGlite(
  path.join(projectRoot, ".local-data/reallanguage-db"),
);

try {
  const result = await database.query(
    `select pending_registration_ciphertext, payload_algorithm, payload_key_version, status
       from identity.verification_challenges
      where challenge_id = $1`,
    [state.registrationId],
  );
  const row = result.rows[0];
  if (!row) throw new Error("Registration challenge was not found");
  if (
    row.payload_algorithm !== "AES-256-GCM/v1" ||
    row.payload_key_version !== 1
  ) {
    throw new Error(
      "Registration challenge uses an unexpected encryption configuration",
    );
  }

  const ciphertext = Buffer.from(row.pending_registration_ciphertext);
  const decipher = createDecipheriv(
    "aes-256-gcm",
    Buffer.alloc(32, 1),
    ciphertext.subarray(0, 12),
  );
  decipher.setAAD(
    Buffer.from("reallanguage:identity:pending-registration:v1", "utf8"),
  );
  decipher.setAuthTag(ciphertext.subarray(12, 28));
  const plaintext = Buffer.concat([
    decipher.update(ciphertext.subarray(28)),
    decipher.final(),
  ]);
  const payload = JSON.parse(plaintext.toString("utf8"));
  if (payload.email !== state.email)
    throw new Error("Registration recipient mismatch");

  await writeFile(
    statePath,
    JSON.stringify({
      ...state,
      verificationBearer: payload.verificationBearer,
    }),
    { encoding: "utf8", mode: 0o600 },
  );
  process.stdout.write(
    `${JSON.stringify({ ok: true, challengeStatus: row.status })}\n`,
  );
} finally {
  await database.close();
}
