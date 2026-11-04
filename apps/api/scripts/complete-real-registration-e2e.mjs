import { randomUUID } from "node:crypto";
import { readFile, writeFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const projectRoot = path.resolve(scriptDir, "../../..");
const statePath = path.join(
  projectRoot,
  ".local-data/real-registration-e2e.json",
);
const state = JSON.parse(await readFile(statePath, "utf8"));

const verifyResponse = await fetch(
  `http://127.0.0.1:3000/api/v1/auth/registrations/${encodeURIComponent(state.registrationId)}/verification`,
  {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ bearer: state.verificationBearer }),
  },
);
const verification = await verifyResponse.json();
if (!verifyResponse.ok || verification.status !== "ACCOUNT_CREATED") {
  throw new Error(`Verification failed (${verifyResponse.status})`);
}

const loginResponse = await fetch(
  "http://127.0.0.1:3000/api/v1/auth/sessions",
  {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({
      email: state.email,
      password: state.password,
      deviceId: `e2e-${randomUUID()}`,
    }),
  },
);
const login = await loginResponse.json();
if (!loginResponse.ok || login.status !== "AUTHENTICATED") {
  throw new Error(
    `Login failed (${loginResponse.status}, ${String(login.status ?? "UNKNOWN")})`,
  );
}

await writeFile(
  statePath,
  JSON.stringify({
    registrationId: state.registrationId,
    registrationStatus: "EMAIL_VERIFICATION_REQUIRED",
    emailDeliveryStatus: "RECEIVED_IN_JUNK",
    verificationStatus: verification.status,
    loginStatus: login.status,
    settingsCompleted: login.settingsCompleted,
    completedAt: new Date().toISOString(),
  }),
  { encoding: "utf8", mode: 0o600 },
);

process.stdout.write(
  `${JSON.stringify({
    ok: true,
    registration: "EMAIL_VERIFICATION_REQUIRED",
    email: "RECEIVED_IN_JUNK",
    verification: verification.status,
    login: login.status,
    settingsCompleted: login.settingsCompleted,
  })}\n`,
);
