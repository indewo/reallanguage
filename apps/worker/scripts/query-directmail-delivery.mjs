import { readFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

import DmSdk from "@alicloud/dm20151123";

const { default: DmClient, SenderStatisticsDetailByParamRequest } = DmSdk;

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

const required = (values, name) => {
  const value = values[name]?.trim();
  if (!value) throw new Error(`Missing required local setting: ${name}`);
  return value;
};

const maskAddress = (address) => {
  const [local = "", domain = ""] = String(address ?? "").split("@");
  return `${local.slice(0, 1)}***@${domain}`;
};

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const projectRoot = path.resolve(scriptDir, "../../..");
const settings = parseEnv(
  await readFile(path.join(projectRoot, ".local-data/directmail.env"), "utf8"),
);

const client = new DmClient({
  accessKeyId: required(settings, "ALIYUN_DIRECTMAIL_ACCESS_KEY_ID"),
  accessKeySecret: required(settings, "ALIYUN_DIRECTMAIL_ACCESS_KEY_SECRET"),
  endpoint: "dm.aliyuncs.com",
  regionId: "cn-hangzhou",
});

const now = new Date();
const date = new Intl.DateTimeFormat("en-CA", {
  timeZone: "Asia/Shanghai",
  year: "numeric",
  month: "2-digit",
  day: "2-digit",
}).format(now);
const request = new SenderStatisticsDetailByParamRequest({
  accountName: "verify@mail.reallanguage.me",
  startTime: `${date} 00:00`,
  endTime: `${date} 23:59`,
  length: 100,
});

try {
  const response = await client.senderStatisticsDetailByParam(request);
  const details = response?.body?.data?.mailDetail ?? [];
  process.stdout.write(
    `${JSON.stringify({
      ok: true,
      records: details.map((detail) => ({
        recipient: maskAddress(detail.toAddress),
        subject: detail.subject,
        status: detail.status,
        classification: detail.errorClassification ?? null,
        message: detail.message ?? null,
        updatedAt: detail.lastUpdateTime ?? null,
      })),
    })}\n`,
  );
} catch (error) {
  process.stderr.write(
    `${JSON.stringify({
      ok: false,
      code: error?.code ?? error?.data?.Code ?? "UNKNOWN",
      message: error?.message ?? "DirectMail query failed",
    })}\n`,
  );
  process.exitCode = 1;
}
