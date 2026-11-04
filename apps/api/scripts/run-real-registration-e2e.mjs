import { randomBytes, randomUUID } from "node:crypto";
import { readFile, writeFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const projectRoot = path.resolve(scriptDir, "../../..");
const statePath = path.join(
  projectRoot,
  ".local-data/real-registration-e2e.json",
);

const parseEnv = (contents) =>
  Object.fromEntries(
    contents
      .split(/\r?\n/u)
      .map((line) => line.trim())
      .filter((line) => line && !line.startsWith("#"))
      .map((line) => {
        const separator = line.indexOf("=");
        return [line.slice(0, separator), line.slice(separator + 1)];
      }),
  );

const settings = parseEnv(
  await readFile(
    path.join(projectRoot, ".local-data/directmail-test.env"),
    "utf8",
  ),
);
const configuredRecipient = settings.ALIYUN_DIRECTMAIL_TEST_RECIPIENT?.trim();
if (!configuredRecipient) throw new Error("Test recipient is not configured");

const [localPart, domain] = configuredRecipient.split("@");
if (!localPart || !domain) throw new Error("Test recipient is invalid");
const testId = Date.now().toString(36);
const email = `${localPart}+reallanguage-${testId}@${domain}`;
const password = `RL-${randomBytes(18).toString("base64url")}`;

const response = await fetch(
  "http://127.0.0.1:3000/api/v1/auth/registrations",
  {
    method: "POST",
    headers: {
      "content-type": "application/json",
      "x-request-id": randomUUID(),
    },
    body: JSON.stringify({
      email,
      password,
      birthYearMonth: "2000-01",
      acceptedTerms: true,
      acceptedPrivacyPolicy: true,
    }),
  },
);
const result = await response.json();
if (!response.ok || result.status !== "EMAIL_VERIFICATION_REQUIRED") {
  throw new Error(
    `Registration failed (${response.status}): ${JSON.stringify(result)}`,
  );
}

await writeFile(
  statePath,
  JSON.stringify({
    email,
    password,
    registrationId: result.registrationId,
    createdAt: new Date().toISOString(),
  }),
  { encoding: "utf8", mode: 0o600 },
);

process.stdout.write(
  `${JSON.stringify({
    ok: true,
    status: result.status,
    registrationId: result.registrationId,
    recipient: `${localPart.slice(0, 1)}***+测试别名@${domain}`,
  })}\n`,
);
