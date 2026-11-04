// Real provider smoke test. Isolated in-memory account/database, no user credentials logged.
import { readFile, writeFile, unlink } from "node:fs/promises";
import { randomUUID } from "node:crypto";
import { assertExplanationRuntimeCompatibility } from "./explanation-runtime-preflight.mts";
if (
  process.argv.includes("--serve-android") ||
  process.argv.includes("--explanation-runtime-preflight-only")
) {
  try {
    const result = await assertExplanationRuntimeCompatibility();
    console.log(
      JSON.stringify({ stage: "isolated-runtime-compatible", ...result }),
    );
  } catch {
    console.log(
      JSON.stringify({
        stage: "isolated-runtime-incompatible",
        providerCalls: 0,
        accountOperations: 0,
      }),
    );
    process.exit(1);
  }
  if (process.argv.includes("--explanation-runtime-preflight-only"))
    process.exit(0);
}
const { createLocalApp } = await import("../apps/api/src/local/local-app.ts");
const { LocalOpenAiMeteredProvider, OpenAiLocalClient } =
  await import("../apps/api/src/local/openai-metered-provider.ts");
let key = process.env.OPENAI_API_KEY;
if (!key) {
  const configuration = await readFile(".env.local", "utf8");
  key = configuration
    .split(/\r?\n/u)
    .find((line) => line.startsWith("OPENAI_API_KEY="))
    ?.slice(15)
    .trim();
}
if (!key) throw new Error("Existing provider credential unavailable");
if (process.argv.includes("--recording-evidence-synthetic")) {
  // Bounded real-provider transport/semantic smoke test, NOT a real-person recording acceptance.
  const audioPath =
    ".local-data/recheck-2026-09-07/recording-synthetic-complete.m4a";
  let calls = 0;
  const client = new OpenAiLocalClient(key, undefined, async (url, options) => {
    if (calls >= 2)
      throw new Error("Two-call synthetic recording budget exhausted");
    calls += 1;
    let response: Response;
    try {
      response = await fetch(url, options);
    } catch (error) {
      const cause = (error as { cause?: { code?: string } }).cause;
      console.log(
        JSON.stringify({
          stage: "synthetic-recording-transport",
          call: calls,
          errorName: error instanceof Error ? error.name : "unknown",
          code: cause?.code ?? null,
        }),
      );
      throw error;
    }
    const payload = await response
      .clone()
      .json()
      .catch(() => ({}));
    console.log(
      JSON.stringify({
        stage: "synthetic-recording-provider",
        call: calls,
        status: response.status,
        endpoint: new URL(String(url)).pathname,
        model: payload.model ?? null,
        usage: payload.usage ?? null,
        durationSeconds: payload.duration ?? null,
      }),
    );
    if (
      response.ok &&
      new URL(String(url)).pathname === "/v1/audio/transcriptions"
    ) {
      await writeFile(
        ".local-data/recheck-2026-09-07/recording-synthetic-timing.json",
        JSON.stringify(payload),
        { mode: 0o600 },
      );
    }
    return response;
  });
  const provider = new LocalOpenAiMeteredProvider(client);
  const command = {
    accountId: `synthetic-recording-${randomUUID()}`,
    operationId: randomUUID(),
    operationLabelZh: "隔离合成录音时间证据检查",
    commandId: randomUUID(),
    task: "SPEECH_TRANSCRIPTION_V1",
    callType: "INITIAL_CALL" as const,
    input: {
      recordingId: randomUUID(),
      durationSeconds: 6,
      endReason: "USER_STOPPED",
      audioFormat: "audio/mp4",
      languageHint: "zh-CN",
      audioBase64: (await readFile(audioPath)).toString("base64"),
    },
  };
  try {
    const quote = await provider.quote(command);
    const result = await provider.execute({
      ...command,
      providerAttemptId: quote.providerAttemptId,
    });
    console.log(
      JSON.stringify({
        stage: "synthetic-recording-result",
        calls,
        output: result.output,
        realHumanAudio: false,
        actualCnyCostKnown: false,
      }),
    );
  } catch (error) {
    console.log(
      JSON.stringify({
        stage: "synthetic-recording-failed",
        calls,
        reason:
          error instanceof Error
            ? error.message.replace(/sk-[A-Za-z0-9_-]+/gu, "[REDACTED]")
            : "unknown",
      }),
    );
    process.exitCode = 1;
  }
  process.exit();
}
if (
  process.argv.includes("--transcription") ||
  process.argv.includes("--audio-assessment")
) {
  // Synthetic speech only: this verifies transport/ASR, not human pronunciation scoring.
  const assessment = process.argv.includes("--audio-assessment");
  const audioPath =
    process.argv[
      process.argv.indexOf(
        assessment ? "--audio-assessment" : "--transcription",
      ) + 1
    ];
  if (!audioPath) throw new Error("Synthetic audio path required");
  const mimeType = audioPath.endsWith(".wav") ? "audio/wav" : "audio/mp4";
  const client = new OpenAiLocalClient(key, undefined, (url, options) =>
    fetch(url, {
      ...options,
      signal: options?.signal ?? AbortSignal.timeout(90000),
    }),
  );
  const audioBase64 = (await readFile(audioPath)).toString("base64");
  if (assessment) {
    if (process.argv.includes("--probe")) {
      const response = await fetch(
        "https://api.openai.com/v1/chat/completions",
        {
          method: "POST",
          signal: AbortSignal.timeout(90000),
          headers: {
            Authorization: `Bearer ${key}`,
            "Content-Type": "application/json",
          },
          body: JSON.stringify({
            model: "gpt-audio-1.5",
            store: false,
            modalities: ["text"],
            messages: [
              {
                role: "user",
                content: [
                  {
                    type: "text",
                    text: "Listen to this recording. What exact words are spoken?",
                  },
                  {
                    type: "input_audio",
                    input_audio: { data: audioBase64, format: "wav" },
                  },
                ],
              },
            ],
          }),
        },
      );
      const payload = await response.json();
      console.log(
        JSON.stringify({
          stage: "synthetic-audio-probe",
          status: response.status,
          model: payload.model,
          content: payload.choices?.[0]?.message?.content,
        }),
      );
      process.exit(0);
    }
    const result = await client.generateJson(
      "FULL_SENTENCE_SPEAKING_EVALUATION_V1",
      {
        audioBase64,
        audioMimeType: mimeType,
        targetTextEn:
          "Today I worked up the courage to explain my idea to a colleague.",
        confirmedTranscript:
          "Today I worked up the courage to explain my idea to a colleague.",
      },
    );
    console.log(
      JSON.stringify({ stage: "synthetic-original-audio-assessment", result }),
    );
    process.exit(0);
  }
  const transcript = await client.transcribe(audioBase64, mimeType);
  console.log(
    JSON.stringify({
      stage: "synthetic-speech-real-asr",
      mimeType,
      transcript,
    }),
  );
  process.exit(0);
}
const delivered: string[] = [];
let providerCalls = 0;
const limitArgument = process.argv.find((argument) =>
  argument.startsWith("--provider-call-limit="),
);
const providerCallLimit = process.argv.includes("--one-provider-call")
  ? 1
  : limitArgument
    ? Number(limitArgument.split("=")[1])
    : undefined;
if (
  providerCallLimit !== undefined &&
  (!Number.isInteger(providerCallLimit) ||
    providerCallLimit < 1 ||
    providerCallLimit > 50)
)
  throw new Error("Provider call limit must be an integer between 1 and 50");
const boundedProviderCheck = providerCallLimit !== undefined;
const app = await createLocalApp({
  dataDirectory: undefined,
  developmentCreditFen: 10000,
  aiProvider: new LocalOpenAiMeteredProvider(
    new OpenAiLocalClient(key, undefined, async (url, options) => {
      const captureTargetSample = process.argv.includes(
        "--capture-target-source-sample",
      );
      if (captureTargetSample) {
        // This opt-in recorder is limited to the single neutral fixture, never
        // arbitrary account content or a production request.
        const body = JSON.parse(String(options?.body));
        const envelope = JSON.parse(body.input);
        const input = envelope.input;
        const sentences = input.chapters?.flatMap(
          (chapter: { sentences: unknown[] }) => chapter.sentences,
        );
        if (
          envelope.task !== "LEARNING_TARGET_EXTRACTION_V1" ||
          sentences?.length !== 1 ||
          sentences[0]?.textEn !==
            "Yesterday I worked up the courage to ask for feedback."
        )
          throw new Error(
            "Captured target sample is outside the isolated fixture",
          );
      }
      if (boundedProviderCheck) {
        if (providerCalls >= providerCallLimit!)
          throw new Error("Isolated provider call budget reached");
        providerCalls++;
      }
      const response = await fetch(url, {
        ...options,
        signal: options?.signal ?? AbortSignal.timeout(90000),
      });
      if (boundedProviderCheck) {
        const payload = await response
          .clone()
          .json()
          .catch(() => null);
        console.log(
          JSON.stringify({
            stage: "isolated-provider-usage",
            providerCalls,
            status: response.status,
            model: payload?.model,
            inputTokens:
              payload?.usage?.input_tokens ?? payload?.usage?.prompt_tokens,
            outputTokens:
              payload?.usage?.output_tokens ??
              payload?.usage?.completion_tokens,
            totalTokens: payload?.usage?.total_tokens,
            usage: payload?.usage ?? null,
            actualCnyCost: null,
          }),
        );
        if (captureTargetSample) {
          await writeFile(
            ".local-data/recheck-2026-09-07/target-source-live-captured-output.json",
            JSON.stringify(payload),
            { mode: 0o600 },
          );
        }
      }
      return response;
    }),
  ),
  registrationEmail: {
    publicApiBaseUrl: "http://127.0.0.1:3000",
    sender: {
      send: async (message) => {
        delivered.push(message.verificationUrl);
      },
      sendPasswordReset: async () => {},
    },
  },
});
let stage = "setup";
try {
  const email = `formation-${randomUUID()}@example.com`;
  const password = randomUUID();
  stage = "registration";
  const registration = await app.inject({
    method: "POST",
    url: "/api/v1/auth/registrations",
    headers: { "x-request-id": randomUUID() },
    payload: {
      email,
      password,
      birthYearMonth: "2000-01",
      acceptedTerms: true,
      acceptedPrivacyPolicy: true,
    },
  });
  console.log(
    JSON.stringify({
      stage,
      status: registration.statusCode,
      result: registration.json().status,
    }),
  );
  if (
    registration.statusCode !== 200 ||
    registration.json().status !== "EMAIL_VERIFICATION_REQUIRED"
  )
    throw new Error("Registration failed");
  stage = "internal-verification-delivery";
  for (let attempt = 0; delivered.length === 0 && attempt < 50; attempt++)
    await new Promise((resolve) => setTimeout(resolve, 100));
  if (!delivered[0]) throw new Error("Internal verification delivery failed");
  stage = "verification";
  const verified = await app.inject({
    method: "POST",
    url: `/api/v1/auth/registrations/${registration.json().registrationId}/verification`,
    payload: { bearer: new URL(delivered[0]).searchParams.get("bearer") },
  });
  if (verified.statusCode !== 200) throw new Error("Verification failed");
  stage = "login";
  const login = await app.inject({
    method: "POST",
    url: "/api/v1/auth/sessions",
    headers: { "x-request-id": randomUUID() },
    payload: { email, password, deviceId: "isolated-real-provider-test" },
  });
  const token = login.json().accessToken;
  if (!token) throw new Error("Test login failed");
  const headers = {
    authorization: `Bearer ${token}`,
    "x-request-id": randomUUID(),
  };
  stage = "settings";
  const settings = await app.inject({
    method: "PUT",
    url: "/api/v1/settings/current/initial",
    headers,
    payload: {
      formalityLevel: 3,
      slangEnabled: false,
      basicVocabulary: "CET_4",
      purposeVocabularies: ["BUSINESS_ENGLISH"],
      styleReference: "natural and clear",
      voiceEnabled: true,
      autoPlayEnabled: false,
      speechSpeedLevel: 3,
    },
  });
  if (settings.statusCode !== 200) throw new Error("Settings failed");
  if (process.argv.includes("--serve-android")) {
    const sessionPath = ".local-data/android-live-test-session.json";
    await writeFile(
      sessionPath,
      JSON.stringify({ accessToken: token, accountId: login.json().accountId }),
      { mode: 0o600 },
    );
    try {
      await app.listen(3001, "127.0.0.1");
      console.log(
        JSON.stringify({
          stage: "isolated-android-server-ready",
          port: 3001,
          credentialsOmitted: true,
        }),
      );
      await new Promise<void>((resolve) => {
        const timer = setTimeout(resolve, 600_000);
        process.once("SIGUSR2", () => {
          clearTimeout(timer);
          resolve();
        });
      });
    } finally {
      await unlink(sessionPath).catch(() => undefined);
    }
  } else if (process.argv.includes("--scene-regeneration")) {
    const session = {
      operationId: randomUUID(),
      start: {
        kind: "DIRECT_DESCRIPTION",
        description: "向同事解释项目需要延期两天，并确认新安排。",
      },
      contentType: "DIALOGUE",
      answers: [
        {
          questionId: "q1",
          answerZh: "对方是同事，需要延期两天，礼貌说明并确认。",
        },
      ],
      collectedFacts: [
        "对方是同事",
        "项目需要延期两天",
        "礼貌说明并确认新安排",
      ],
      missingNonBlockingFacts: [],
    };
    stage = "scene-first-generation";
    const first = await app.inject({
      method: "POST",
      url: "/api/v1/sources/scenes/drafts",
      headers,
      payload: {
        task: "SCENE_DIALOGUE_TURN_V1",
        session,
        remainingContentBudget: 10,
        commandId: randomUUID(),
      },
    });
    console.log(
      JSON.stringify({
        stage,
        status: first.statusCode,
        units: first.json().units?.length,
      }),
    );
    if (first.statusCode !== 200) throw new Error("First scene failed");
    stage = "scene-regeneration";
    const second = await app.inject({
      method: "POST",
      url: "/api/v1/sources/scenes/drafts",
      headers,
      payload: {
        task: "SCENE_DIALOGUE_TURN_V1",
        session,
        remainingContentBudget: 10,
        commandId: randomUUID(),
        previousUnits: first.json().units,
      },
    });
    const normalized = (units: { textZh: string }[]) =>
      units
        .map((unit) =>
          unit.textZh.normalize("NFKC").replace(/[\s\p{P}\p{S}]/gu, ""),
        )
        .join("");
    const different =
      second.statusCode === 200 &&
      normalized(first.json().units) !== normalized(second.json().units);
    console.log(
      JSON.stringify({
        stage,
        status: second.statusCode,
        units: second.json().units?.length,
        different,
      }),
    );
    if (!different) throw new Error("Regeneration did not change content");
  } else if (process.argv.includes("--contextual-english")) {
    stage = "contextual-english-generation";
    const response = await app.inject({
      method: "POST",
      url: "/api/v1/content-formation/english-candidates",
      headers,
      payload: {
        sourceType: "DIARY",
        units: [
          {
            unitId: "context-u0",
            order: 0,
            textZh: "我这周一直在做这个新版本。",
          },
          {
            unitId: "context-u1",
            order: 1,
            textZh: "今天我在开发这个 APP。",
          },
        ],
        chapters: [
          { chapterId: "context-c1", unitIds: ["context-u1"] },
        ],
        selectedUnitIds: ["context-u1"],
        commandId: randomUUID(),
        knownKnowledge: [],
      },
    });
    const sentences = response.json().chapters?.[0]?.sentences;
    const candidates = sentences?.[0]?.candidates;
    console.log(
      JSON.stringify({
        stage,
        status: response.statusCode,
        sourceZh: sentences?.[0]?.textZh,
        candidates: candidates?.map(
          (candidate: {
            english: string;
            chineseCorrespondenceZh: string;
            styleFit: string;
          }) => ({
            english: candidate.english,
            chineseCorrespondenceZh: candidate.chineseCorrespondenceZh,
            styleFit: candidate.styleFit,
          }),
        ),
      }),
    );
    if (
      response.statusCode !== 200 ||
      !Array.isArray(candidates) ||
      candidates.length < 1 ||
      candidates.some(
        (candidate: { english?: unknown }) =>
          typeof candidate.english !== "string" ||
          !candidate.english.trim() ||
          /today\s+i\s+(?:am\s+)?develop(?:ing)?\s+this\s+app[.!]?$/iu.test(
            candidate.english.trim(),
          ) || /\bnew\s+app\b/iu.test(candidate.english),
      )
    )
      throw new Error("Contextual English generation failed");
  } else {
    const chapters = [
      {
        chapterId: "live-c1",
        sentences: [
          {
            unitId: "live-u1",
            textZh: "今天我鼓起勇气向同事解释了自己的想法。",
            textEn:
              "Today I worked up the courage to explain my idea to a colleague.",
            isFocus: true,
          },
        ],
      },
    ];
    stage = "extract";
    const targets = await app.inject({
      method: "POST",
      url: "/api/v1/content-formation/learning-targets",
      headers,
      payload: { chapters, commandId: randomUUID() },
    });
    console.log(
      JSON.stringify({
        stage,
        status: targets.statusCode,
        targets: targets.json().targets?.length,
        targetSummaries: targets
          .json()
          .targets?.map(
            (target: {
              display: string;
              explanationZh: string;
              type: string;
            }) => ({
              display: target.display,
              explanationZh: target.explanationZh,
              type: target.type,
            }),
          ),
      }),
    );
    if (targets.statusCode !== 200) throw new Error("Target extraction failed");
    stage = "add-expression";
    const added = await app.inject({
      method: "POST",
      url: "/api/v1/content-formation/learning-targets",
      headers,
      payload: {
        chapters,
        commandId: randomUUID(),
        requestedExpression: {
          unitId: "live-u1",
          expressionId: "live-word-colleague",
          display: "colleague",
        },
      },
    });
    console.log(
      JSON.stringify({
        stage,
        status: added.statusCode,
        correctExpression: added.json().targets?.[0]?.display === "colleague",
      }),
    );
    if (
      added.statusCode !== 200 ||
      added.json().targets?.[0]?.display !== "colleague"
    )
      throw new Error("Requested expression failed");
    stage = "knowledge-examples";
    const target = added.json().targets[0];
    const examples = await app.inject({
      method: "POST",
      url: "/api/v1/knowledge/examples",
      headers,
      payload: {
        itemId: target.targetId,
        targetType: "VOCABULARY",
        display: target.display,
        meaningZh: target.explanationZh,
        sourceScene: chapters[0]!.sentences.map(({ textEn, textZh }) => ({
          textEn,
          textZh,
        })),
        commandId: randomUUID(),
      },
    });
    console.log(
      JSON.stringify({
        stage,
        status: examples.statusCode,
        examples: examples.json().examples?.length,
      }),
    );
    if (examples.statusCode !== 200 || examples.json().examples?.length !== 3)
      throw new Error("Knowledge examples failed");
  }
} catch {
  console.log(
    JSON.stringify({
      stage,
      result: "FAILED",
      credentialsAndResponseBodiesOmitted: true,
    }),
  );
  process.exitCode = 1;
} finally {
  await app.close();
}
