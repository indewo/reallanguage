import "reflect-metadata";

import { spawn } from "node:child_process";
import { readFile } from "node:fs/promises";
import { networkInterfaces } from "node:os";
import { resolve } from "node:path";
import { fileURLToPath } from "node:url";

import {
  CompositeRegistrationEmailSender,
  createLocalAliyunRegistrationEmailSender,
  LocalMailboxRegistrationEmailSender,
} from "./local/directmail-registration-email.js";
import { createLocalApp } from "./local/local-app.js";
import {
  LocalOpenAiMeteredProvider,
  OpenAiLocalClient,
} from "./local/openai-metered-provider.js";

const port = Number(process.env.PORT ?? 3000);
const projectRoot = fileURLToPath(new URL("../../../", import.meta.url));
const localAddress = findLocalIpv4Address();
const publicApiBaseUrl = `http://${localAddress}:${port}`;
const openAiApiKey = await loadOpenAiApiKey(projectRoot);
const localMailbox = new LocalMailboxRegistrationEmailSender(
  resolve(projectRoot, ".local-data/latest-identity-email.json"),
);
const identityEmailSender =
  process.env.REAL_LANGUAGE_LOCAL_EMAIL_MODE === "capture"
    ? localMailbox
    : new CompositeRegistrationEmailSender([
        await createLocalAliyunRegistrationEmailSender(projectRoot),
        localMailbox,
      ]);
const app = await createLocalApp({
  dataDirectory: resolve(
    projectRoot,
    process.env.REAL_LANGUAGE_LOCAL_DATA_DIRECTORY ??
      ".local-data/reallanguage-db",
  ),
  aiProvider: new LocalOpenAiMeteredProvider(
    new OpenAiLocalClient(openAiApiKey),
  ),
  developmentCreditFen: 10_000,
  registrationEmail: {
    sender: identityEmailSender,
    publicApiBaseUrl,
  },
  // Diagnostic metadata only: never log authorization, request bodies, or content.
  accessLogger: (entry) => console.info(`RealLanguage access ${JSON.stringify(entry)}`),
});
await app.listen(port, "0.0.0.0");
const serviceAdvertiser = advertiseLocalApi(port);

console.info(`RealLanguage local API is listening at ${publicApiBaseUrl}.`);

process.once("exit", () => serviceAdvertiser?.kill("SIGTERM"));
for (const signal of ["SIGINT", "SIGTERM"] as const) {
  process.once(signal, () => {
    serviceAdvertiser?.kill("SIGTERM");
    void app.close().finally(() => process.exit(0));
  });
}

function advertiseLocalApi(apiPort: number) {
  if (process.platform !== "darwin") return null;
  const child = spawn(
    "/usr/bin/dns-sd",
    ["-R", "RealLanguage", "_reallanguage._tcp", "local", String(apiPort)],
    { stdio: "ignore" },
  );
  child.on("error", (failure) => {
    console.warn(`RealLanguage DNS-SD advertisement failed: ${failure.message}`);
  });
  child.on("exit", (code, signal) => {
    if (code !== 0 && signal !== "SIGTERM") {
      console.warn(`RealLanguage DNS-SD advertisement stopped: code=${code} signal=${signal}`);
    }
  });
  return child;
}

function findLocalIpv4Address(): string {
  for (const addresses of Object.values(networkInterfaces())) {
    for (const address of addresses ?? []) {
      if (address.family === "IPv4" && !address.internal)
        return address.address;
    }
  }
  return "127.0.0.1";
}

async function loadOpenAiApiKey(projectRoot: string): Promise<string> {
  const existing = process.env.OPENAI_API_KEY?.trim();
  if (existing) return existing;
  const contents = await readFile(resolve(projectRoot, ".env.local"), "utf8");
  const line = contents
    .split(/\r?\n/u)
    .find((candidate) => candidate.startsWith("OPENAI_API_KEY="));
  const value = line?.slice("OPENAI_API_KEY=".length).trim();
  if (!value) throw new Error("OPENAI_API_KEY is required for the local API");
  return value;
}
