import { randomBytes, randomUUID } from "node:crypto";
import { readFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const projectRoot = path.resolve(scriptDir, "../../..");
const apiBase = "http://127.0.0.1:3000/api/v1";
const testSettings = parseEnv(
  await readFile(
    path.join(projectRoot, ".local-data/directmail-test.env"),
    "utf8",
  ),
);
const recipient = testSettings.ALIYUN_DIRECTMAIL_TEST_RECIPIENT?.trim();
if (!recipient) throw new Error("Test recipient is not configured");
const [localPart, domain] = recipient.split("@");
if (!localPart || !domain) throw new Error("Test recipient is invalid");

const suffix = Date.now().toString(36);
const email = `${localPart}+full-${suffix}@${domain}`;
const password = `RL-${randomBytes(18).toString("base64url")}`;
const testStartedAt = Date.now();
const registration = await request("auth/registrations", {
  email,
  password,
  birthYearMonth: "2000-01",
  acceptedTerms: true,
  acceptedPrivacyPolicy: true,
});
assertStatus(registration, "EMAIL_VERIFICATION_REQUIRED", "registration");

const registrationMessage = await waitForIdentityEmail(
  "EMAIL_VERIFICATION",
  email,
  testStartedAt,
);
const verificationBearer = new URL(
  registrationMessage.verificationUrl,
).searchParams.get("bearer");
const verification = await request(
  `auth/registrations/${encodeURIComponent(registration.registrationId)}/verification`,
  { bearer: verificationBearer },
);
assertStatus(verification, "ACCOUNT_CREATED", "verification");

const login = await request("auth/sessions", {
  email,
  password,
  deviceId: `full-e2e-${randomUUID()}`,
});
assertStatus(login, "AUTHENTICATED", "login");
const bearer = login.accessToken;

const settings = {
  formalityLevel: 3,
  slangEnabled: false,
  basicVocabulary: "CET_4",
  purposeVocabularies: ["BUSINESS_ENGLISH"],
  styleReference: "natural and clear",
  voiceEnabled: true,
  autoPlayEnabled: false,
  speechSpeedLevel: 3,
};
assertStatus(
  await request("settings/current/initial", settings, bearer, "PUT"),
  "COMPLETED",
  "settings",
);
const currentSettings = await request(
  "settings/current",
  undefined,
  bearer,
  "GET",
);
if (currentSettings.basicVocabulary !== settings.basicVocabulary) {
  throw new Error("Current settings do not match the saved snapshot");
}

const source =
  "我把车停在了公司。但是我人在家。所以今天上班我就需要通过其他的方式去公司。有时候住得远也是一个大问题。";
assertStatus(
  await request(
    "safety/checks",
    {
      text: source,
      sourceType: "DIARY",
      contentShape: "ARTICLE",
      commandId: randomUUID(),
    },
    bearer,
  ),
  "PASSED",
  "safety",
);
const split = await request(
  "content-formation/semantic-units/split",
  {
    text: source,
    contentShape: "ARTICLE",
    commandId: randomUUID(),
  },
  bearer,
);
if (
  split.coverage?.complete !== true ||
  split.units.map((unit) => unit.textZh).join("") !== source
) {
  throw new Error("Semantic split did not preserve complete source coverage");
}
const units = split.units.map(({ unitId, order, textZh }) => ({
  unitId,
  order,
  textZh,
}));
const selectedUnitIds = [units.at(-1).unitId];
const chapterSelection = await request(
  "content-formation/chapters/context-selection",
  {
    units,
    selectedUnitIds,
    commandId: randomUUID(),
  },
  bearer,
);
if (
  chapterSelection.chapters?.length !== 1 ||
  chapterSelection.chapters[0].unitIds.length !== units.length ||
  chapterSelection.chapters[0].unitIds.some(
    (unitId, index) => unitId !== units[index].unitId,
  )
) {
  throw new Error(
    "Chapter selection did not preserve all source units in order",
  );
}

const english = await request(
  "content-formation/english-candidates",
  {
    sourceType: "DIARY",
    units,
    chapters: chapterSelection.chapters,
    selectedUnitIds,
    commandId: randomUUID(),
  },
  bearer,
);
const confirmedChapters = english.chapters.map((chapter) => ({
  chapterId: chapter.chapterId,
  sentences: chapter.sentences.map((sentence) => ({
    unitId: sentence.sourceUnitId,
    textZh: sentence.textZh,
    textEn: sentence.candidates[0].english,
    isFocus: selectedUnitIds.includes(sentence.sourceUnitId),
  })),
}));
if (
  confirmedChapters.some((chapter) =>
    chapter.sentences.some((sentence) => !sentence.textEn),
  )
) {
  throw new Error("English generation returned an empty candidate");
}
if (
  confirmedChapters.length !== 1 ||
  confirmedChapters[0].sentences.length !== units.length ||
  confirmedChapters[0].sentences.some(
    (sentence, index) =>
      sentence.unitId !== units[index].unitId ||
      sentence.textZh !== units[index].textZh,
  )
) {
  throw new Error(
    "English generation did not preserve all source units in order",
  );
}
const learningTargets = await request(
  "content-formation/learning-targets",
  {
    chapters: confirmedChapters,
    commandId: randomUUID(),
  },
  bearer,
);
if (
  !Array.isArray(learningTargets.targets) ||
  !Array.isArray(learningTargets.queryableExpressions)
) {
  throw new Error("Learning targets response is malformed");
}
const billing = await request("billing/summary", undefined, bearer, "GET");

const passwordReset = await request("auth/password-resets", {
  email,
  commandId: randomUUID(),
});
assertStatus(passwordReset, "SENT", "password-reset-request");
const passwordResetMessage = await waitForIdentityEmail(
  "PASSWORD_RESET",
  email,
  testStartedAt,
);
const passwordResetBearer = new URL(
  passwordResetMessage.verificationUrl,
).searchParams.get("bearer");
const passwordResetVerification = await request(
  `auth/password-resets/${encodeURIComponent(passwordReset.challengeId)}/verification`,
  { bearer: passwordResetBearer },
);
assertStatus(
  passwordResetVerification,
  "VERIFIED",
  "password-reset-verification",
);
const newPassword = `RL-new-${randomBytes(18).toString("base64url")}`;
const passwordResetCompletion = await request(
  "auth/password-resets/completion",
  {
    resetToken: passwordResetVerification.resetToken,
    newPassword,
    commandId: randomUUID(),
  },
);
assertStatus(passwordResetCompletion, "COMPLETED", "password-reset-completion");
const oldPasswordLogin = await request("auth/sessions", {
  email,
  password,
  deviceId: `old-password-${randomUUID()}`,
});
assertStatus(oldPasswordLogin, "INVALID_CREDENTIALS", "old-password-login");
const newPasswordLogin = await request("auth/sessions", {
  email,
  password: newPassword,
  deviceId: `new-password-${randomUUID()}`,
});
assertStatus(newPasswordLogin, "AUTHENTICATED", "new-password-login");

process.stdout.write(
  `${JSON.stringify({
    ok: true,
    registration: registration.status,
    verification: verification.status,
    login: login.status,
    settings: "COMPLETED_AND_READ_BACK",
    safety: "PASSED",
    semanticUnits: units.length,
    chapters: confirmedChapters.length,
    englishSentences: confirmedChapters.reduce(
      (sum, chapter) => sum + chapter.sentences.length,
      0,
    ),
    learningTargets: learningTargets.targets.length,
    queryableExpressions: learningTargets.queryableExpressions.length,
    passwordReset: "COMPLETED_AND_RELOGGED_IN",
    balanceFen: billing.balanceFen,
    recipient: `${localPart.slice(0, 1)}***+full-test@${domain}`,
  })}\n`,
);

async function request(route, body, bearer, method = "POST") {
  const response = await fetch(`${apiBase}/${route}`, {
    method,
    headers: {
      "x-request-id": randomUUID(),
      ...(body === undefined ? {} : { "content-type": "application/json" }),
      ...(bearer ? { authorization: `Bearer ${bearer}` } : {}),
    },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  });
  const payload = await response.json();
  if (!response.ok) {
    throw new Error(
      `${route} failed (${response.status}): ${payload.error?.code ?? "UNKNOWN"}`,
    );
  }
  return payload;
}

async function waitForIdentityEmail(purpose, expectedRecipient, notBefore) {
  const mailboxPaths = [
    process.env.REALLANGUAGE_IDENTITY_CAPTURE,
    path.join(projectRoot, ".local-data/latest-identity-email.json"),
    path.join(
      os.homedir(),
      "Library/Application Support/RealLanguage/runtime/.local-data/latest-identity-email.json",
    ),
  ].filter(Boolean);
  const deadline = Date.now() + 10_000;
  while (Date.now() < deadline) {
    for (const mailboxPath of mailboxPaths) {
      try {
        const message = JSON.parse(await readFile(mailboxPath, "utf8"));
        if (
          message.purpose === purpose &&
          message.recipient === expectedRecipient &&
          Date.parse(message.capturedAt) >= notBefore
        ) {
          return message;
        }
      } catch {
        // A capture file may not exist yet or may be mid-replacement; retry.
      }
    }
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
  throw new Error(`${purpose} email was not captured after real dispatch`);
}

function assertStatus(value, expected, stage) {
  if (value?.status !== expected) {
    throw new Error(`${stage} returned ${String(value?.status ?? "UNKNOWN")}`);
  }
}

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
