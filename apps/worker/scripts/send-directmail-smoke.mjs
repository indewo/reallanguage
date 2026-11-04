import { readFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

import DmSdk from "@alicloud/dm20151123";

const { default: DmClient, SingleSendMailRequest } = DmSdk;

const CONFIRMATION_FLAG = "--confirm-send";
const SENDER = "verify@mail.reallanguage.me";

function parseEnv(contents) {
  return Object.fromEntries(
    contents
      .split(/\r?\n/u)
      .map((line) => line.trim())
      .filter((line) => line && !line.startsWith("#"))
      .map((line) => {
        const separator = line.indexOf("=");
        return [line.slice(0, separator), line.slice(separator + 1)];
      }),
  );
}

function requireValue(values, name) {
  const value = values[name]?.trim();
  if (!value) {
    throw new Error(`Missing required local setting: ${name}`);
  }
  return value;
}

if (!process.argv.includes(CONFIRMATION_FLAG)) {
  throw new Error(`Refusing to send without ${CONFIRMATION_FLAG}`);
}

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const projectRoot = path.resolve(scriptDir, "../../..");
const [credentialText, recipientText] = await Promise.all([
  readFile(path.join(projectRoot, ".local-data/directmail.env"), "utf8"),
  readFile(path.join(projectRoot, ".local-data/directmail-test.env"), "utf8"),
]);
const settings = { ...parseEnv(credentialText), ...parseEnv(recipientText) };

const client = new DmClient({
  accessKeyId: requireValue(settings, "ALIYUN_DIRECTMAIL_ACCESS_KEY_ID"),
  accessKeySecret: requireValue(
    settings,
    "ALIYUN_DIRECTMAIL_ACCESS_KEY_SECRET",
  ),
  endpoint: "dm.aliyuncs.com",
  regionId: "cn-hangzhou",
});

const request = new SingleSendMailRequest({
  accountName: SENDER,
  addressType: 1,
  replyToAddress: false,
  toAddress: requireValue(settings, "ALIYUN_DIRECTMAIL_TEST_RECIPIENT"),
  fromAlias: "RealLanguage",
  subject: "RealLanguage 邮件服务测试",
  htmlBody: [
    '<div style="font-family:-apple-system,BlinkMacSystemFont,Segoe UI,sans-serif;color:#3f4541;line-height:1.7">',
    "<h2>RealLanguage 邮件服务测试成功</h2>",
    "<p>如果你看到这封邮件，说明发件域名、DNS 验证和阿里云邮件发送权限已经连通。</p>",
    "<p>这是一封一次性测试邮件，无需回复。</p>",
    "</div>",
  ].join(""),
});

try {
  const response = await client.singleSendMail(request);
  const requestId = response?.body?.requestId;
  process.stdout.write(
    JSON.stringify({ ok: true, requestId: requestId ?? null }) + "\n",
  );
} catch (error) {
  const code = error?.code ?? error?.data?.Code ?? "UNKNOWN";
  const message = error?.message ?? "DirectMail request failed";
  process.stderr.write(JSON.stringify({ ok: false, code, message }) + "\n");
  process.exitCode = 1;
}
