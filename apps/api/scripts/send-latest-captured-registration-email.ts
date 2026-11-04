import { readFile } from "node:fs/promises";
import { resolve } from "node:path";

import {
  createLocalAliyunRegistrationEmailSender,
  type RegistrationVerificationEmail,
} from "../src/local/directmail-registration-email.js";

if (!process.argv.includes("--confirm-send")) {
  throw new Error("Refusing to send without --confirm-send");
}

const projectRoot = process.cwd();
const captured = JSON.parse(
  await readFile(
    resolve(projectRoot, ".local-data/local-mailbox/latest.json"),
    "utf8",
  ),
) as RegistrationVerificationEmail & { capturedAt: string };

const capturedAt = new Date(captured.capturedAt);
if (
  !Number.isFinite(capturedAt.getTime()) ||
  Date.now() - capturedAt.getTime() >= 30 * 60 * 1_000
) {
  throw new Error("The captured registration verification has expired");
}

const sender = await createLocalAliyunRegistrationEmailSender(projectRoot);
await sender.send({
  recipient: captured.recipient,
  verificationUrl: captured.verificationUrl,
});

process.stdout.write(JSON.stringify({ ok: true }) + "\n");
