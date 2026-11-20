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
import { configuredSentenceAudio } from "./local/sentence-audio-configuration.js";
import {
  LocalOpenAiMeteredProvider,
  OpenAiLocalClient,
} from "./local/openai-metered-provider.js";

const port = Number(process.env.PORT ?? 3000);
const projectRoot = fileURLToPath(new URL("../../../", import.meta.url));
await loadLocalEnvFile(projectRoot);
const localAddress = findLocalIpv4Address();
// Behind NAT the detected LAN address is not reachable by the APP. Allow the
// deployment to provide the public origin used in verification/reset links.
const publicApiBaseUrl = (
  process.env.REAL_LANGUAGE_PUBLIC_API_BASE_URL?.trim() ||
  `http://${localAddress}:${port}`
).replace(/\/+$/u, "");
const openAiApiKey = await loadOpenAiApiKey(projectRoot);
const localMailbox = new LocalMailboxRegistrationEmailSender(
  resolve(projectRoot, ".local-data/latest-identity-email.json"),
);
const trialRegistrationEnabled =
  process.env.REAL_LANGUAGE_LOCAL_TRIAL_REGISTRATION === "true";
const identityEmailSender =
  process.env.REAL_LANGUAGE_LOCAL_EMAIL_MODE === "capture"
    ? {
        async send(message: Parameters<typeof localMailbox.send>[0]) {
          await localMailbox.send(message);
          if (!trialRegistrationEnabled || !message.recipient.toLowerCase().endsWith("@example.com"))
            return;
          // Only reserved test addresses can activate without mailbox ownership.
          const confirmation = new URL(message.verificationUrl);
          const path = confirmation.pathname.replace(/\/confirm$/u, "");
          const response = await fetch(`http://127.0.0.1:${port}${path}`, {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ bearer: confirmation.searchParams.get("bearer") }),
            signal: AbortSignal.timeout(5_000),
          });
          const result = (await response.json()) as { status?: string };
          if (!response.ok || result.status !== "ACCOUNT_CREATED")
            throw new Error("Local trial registration could not be activated");
        },
        sendPasswordReset: localMailbox.sendPasswordReset.bind(localMailbox),
      }
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
  sentenceAudio: configuredSentenceAudio(projectRoot),
  registrationEmail: {
    sender: identityEmailSender,
    publicApiBaseUrl,
  },
  // Diagnostic metadata only: never log authorization, request bodies, or content.
  accessLogger: (entry) => console.info(`RealLanguage access ${JSON.stringify(entry)}`),
  // Diagnostic metadata for 5xx only: never logs bodies, tokens, or user content.
  errorLogger: (entry) => {
    console.error(
      `RealLanguage error ${JSON.stringify({
        method: entry.method,
        path: entry.path,
        statusCode: entry.statusCode,
        requestId: entry.requestId,
        errorName: entry.errorName,
        errorMessage: entry.errorMessage,
      })}`,
    );
    if (entry.stack) console.error(entry.stack);
  },
});
await app.listen(port, "0.0.0.0");
const serviceAdvertiser = advertiseLocalApi(port);

console.info(`RealLanguage local API is listening at ${publicApiBaseUrl}.`);

// 冷启动预热：本机经代理出网时，进程内第一次 HTTPS 请求会触发代理侧的节点
// 协商，实测可达 90 秒以上，远超 provider 的 45–60 秒超时，导致用户的第一个
// AI 请求直接失败。这里在监听之后立刻在后台打一次轻量请求把连接池焐热，
// 让真实请求落到已经建立的通道上。失败不阻塞服务，只记录告警。
void warmUpAiLink(openAiApiKey);

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

// 预热只求把出网通道建立起来，不关心业务结果：即便返回 401/403，也说明
// TLS 与代理隧道已经通了，目的达到。
async function warmUpAiLink(apiKey: string): Promise<void> {
  const baseUrl = (process.env.OPENAI_BASE_URL ?? "https://api.openai.com/v1")
    .replace(/\/+$/u, "");
  // 首次协商可能很慢，给足 180 秒；重试预算只影响日志，不阻塞服务。
  const attemptTimeoutMs = 180_000;
  for (let attempt = 1; attempt <= 3; attempt += 1) {
    const startedAt = Date.now();
    try {
      const response = await fetch(`${baseUrl}/models`, {
        headers: { Authorization: `Bearer ${apiKey}` },
        signal: AbortSignal.timeout(attemptTimeoutMs),
      });
      console.info(
        `RealLanguage AI warm-up ok: status=${response.status} attempt=${attempt} elapsedMs=${Date.now() - startedAt}`,
      );
      return;
    } catch (failure) {
      console.warn(
        `RealLanguage AI warm-up failed: attempt=${attempt} elapsedMs=${Date.now() - startedAt} ${(failure as Error).message}`,
      );
    }
  }
  console.warn(
    "RealLanguage AI warm-up gave up; the first AI request may be slow or time out.",
  );
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

// Optional .env.local loader for OpenAI-compatible endpoint settings
// (OPENAI_BASE_URL, OPENAI_MODEL, OPENAI_TRANSCRIBE_MODEL, ...). Real
// environment variables always win over file values.
async function loadLocalEnvFile(projectRoot: string): Promise<void> {
  const contents = await readFile(resolve(projectRoot, ".env.local"), "utf8")
    .catch(() => "");
  for (const line of contents.split(/\r?\n/u)) {
    const match = /^([A-Z][A-Z0-9_]*)=(.*)$/u.exec(line.trim());
    if (!match?.[2]) continue;
    const [, key, rawValue] = match as unknown as [string, string, string];
    const value = rawValue.trim().replace(/^["']|["']$/gu, "");
    if (process.env[key] === undefined && value) process.env[key] = value;
  }
}
