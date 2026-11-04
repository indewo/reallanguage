import type { NestFastifyApplication } from "@nestjs/platform-fastify";
import { afterEach, describe, expect, it, vi } from "vitest";

import type { RegistrationVerificationEmail } from "./directmail-registration-email.js";

import { createLocalApp } from "./local-app.js";
import { LocalOpenAiMeteredProvider } from "./openai-metered-provider.js";

let app: NestFastifyApplication | undefined;

afterEach(async () => {
  await app?.close();
  app = undefined;
});

describe("local API composition", () => {
  it("starts with an embedded database and accepts registration", async () => {
    app = await createLocalApp({ dataDirectory: undefined });

    const health = await app.inject({
      method: "GET",
      url: "/api/v1/health",
    });
    expect(health.statusCode).toBe(200);

    const preflight = await app.inject({
      method: "OPTIONS",
      url: "/api/v1/health",
      headers: {
        origin: "http://127.0.0.1:5173",
        "access-control-request-method": "GET",
      },
    });
    expect(preflight.statusCode).toBe(204);
    expect(preflight.headers["access-control-allow-origin"]).toBe(
      "http://127.0.0.1:5173",
    );

    const registration = await app.inject({
      method: "POST",
      url: "/api/v1/auth/registrations",
      headers: { "x-request-id": "local-registration-test" },
      payload: {
        email: "local-learner@example.com",
        password: "a sufficiently long password",
        birthYearMonth: "2000-01",
        acceptedTerms: true,
        acceptedPrivacyPolicy: true,
      },
    });

    expect(registration.statusCode).toBe(200);
    expect(registration.json()).toMatchObject({
      status: "EMAIL_VERIFICATION_REQUIRED",
    });

    const privacyPolicy = await app.inject({
      method: "GET",
      url: "/api/v1/legal/documents/privacy-policy",
    });
    expect(privacyPolicy.statusCode).toBe(200);
    expect(privacyPolicy.json()).toMatchObject({
      kind: "PRIVACY_POLICY",
      titleZh: "隐私政策",
      version: "2026-09-02",
    });
    expect(privacyPolicy.json().sections.length).toBeGreaterThan(5);

    const userAgreement = await app.inject({
      method: "GET",
      url: "/api/v1/legal/documents/user-agreement",
    });
    expect(userAgreement.statusCode).toBe(200);
    expect(userAgreement.json()).toMatchObject({
      kind: "USER_AGREEMENT",
      titleZh: "用户协议",
      version: "2026-09-02",
    });
    expect(userAgreement.json().sections.length).toBeGreaterThan(5);
  });

  it("delivers a usable verification email from the local outbox", async () => {
    const delivered: RegistrationVerificationEmail[] = [];
    app = await createLocalApp({
      dataDirectory: undefined,
      registrationEmail: {
        publicApiBaseUrl: "http://192.168.1.20:3000",
        sender: {
          send: async (message) => {
            delivered.push(message);
          },
          sendPasswordReset: async () => {},
        },
      },
    });

    const registration = await app.inject({
      method: "POST",
      url: "/api/v1/auth/registrations",
      headers: { "x-request-id": "local-email-test" },
      payload: {
        email: "mail-delivery@example.com",
        password: "a sufficiently long password",
        birthYearMonth: "2000-01",
        acceptedTerms: true,
        acceptedPrivacyPolicy: true,
      },
    });
    const registrationId = registration.json().registrationId as string;

    await vi.waitFor(() => expect(delivered).toHaveLength(1), {
      timeout: 3_000,
    });
    expect(delivered[0]?.recipient).toBe("mail-delivery@example.com");
    const verificationUrl = new URL(delivered[0]!.verificationUrl);
    expect(verificationUrl.origin).toBe("http://192.168.1.20:3000");
    expect(verificationUrl.pathname).toContain(registrationId);

    const waiting = await app.inject({
      method: "GET",
      url: `/api/v1/auth/registrations/${registrationId}/verification-status`,
    });
    expect(waiting.json()).toEqual({ status: "WAITING" });

    const page = await app.inject({
      method: "GET",
      url: verificationUrl.pathname + verificationUrl.search,
    });
    expect(page.statusCode).toBe(200);
    expect(page.body).toContain("确认邮箱");

    const verified = await app.inject({
      method: "POST",
      url: `/api/v1/auth/registrations/${registrationId}/verification`,
      payload: { bearer: verificationUrl.searchParams.get("bearer") },
    });
    expect(verified.json()).toMatchObject({ status: "ACCOUNT_CREATED" });

    const completed = await app.inject({
      method: "GET",
      url: `/api/v1/auth/registrations/${registrationId}/verification-status`,
    });
    expect(completed.json()).toEqual({ status: "VERIFIED" });
  });

  it("delivers a usable password-reset deep link for a verified account", async () => {
    const registrations: RegistrationVerificationEmail[] = [];
    const passwordResets: RegistrationVerificationEmail[] = [];
    app = await createLocalApp({
      dataDirectory: undefined,
      registrationEmail: {
        publicApiBaseUrl: "http://192.168.1.20:3000",
        sender: {
          send: async (message) => {
            registrations.push(message);
          },
          sendPasswordReset: async (message) => {
            passwordResets.push(message);
          },
        },
      },
    });

    const email = "password-reset@example.com";
    const registration = await app.inject({
      method: "POST",
      url: "/api/v1/auth/registrations",
      headers: { "x-request-id": "password-reset-registration" },
      payload: {
        email,
        password: "old-password",
        birthYearMonth: "2000-01",
        acceptedTerms: true,
        acceptedPrivacyPolicy: true,
      },
    });
    await vi.waitFor(() => expect(registrations).toHaveLength(1));
    const registrationUrl = new URL(registrations[0]!.verificationUrl);
    await app.inject({
      method: "POST",
      url: `/api/v1/auth/registrations/${registration.json().registrationId}/verification`,
      payload: { bearer: registrationUrl.searchParams.get("bearer") },
    });

    const requested = await app.inject({
      method: "POST",
      url: "/api/v1/auth/password-resets",
      headers: { "x-request-id": "password-reset-request" },
      payload: { email, commandId: "password-reset-command" },
    });
    expect(requested.statusCode).toBe(200);
    await vi.waitFor(() => expect(passwordResets).toHaveLength(1));
    const resetUrl = new URL(passwordResets[0]!.verificationUrl);
    expect(resetUrl.protocol).toBe("reallanguage:");
    expect(resetUrl.hostname).toBe("password-reset");
    expect(resetUrl.pathname).toBe("/verify");
    expect(resetUrl.searchParams.get("challengeId")).toBe(
      requested.json().challengeId,
    );
    expect(resetUrl.searchParams.get("bearer")).toMatch(/^[A-Za-z0-9_-]{43}$/u);

    const verified = await app.inject({
      method: "POST",
      url: `/api/v1/auth/password-resets/${requested.json().challengeId}/verification`,
      payload: { bearer: resetUrl.searchParams.get("bearer") },
    });
    expect(verified.json().status).toBe("VERIFIED");

    const completed = await app.inject({
      method: "POST",
      url: "/api/v1/auth/password-resets/completion",
      payload: {
        resetToken: verified.json().resetToken,
        newPassword: "new-password",
        commandId: "password-reset-completion",
      },
    });
    expect(completed.json().status).toBe("COMPLETED");

    const oldLogin = await app.inject({
      method: "POST",
      url: "/api/v1/auth/sessions",
      payload: { email, password: "old-password", deviceId: "old-device" },
    });
    expect(oldLogin.json().status).toBe("INVALID_CREDENTIALS");
    const newLogin = await app.inject({
      method: "POST",
      url: "/api/v1/auth/sessions",
      payload: { email, password: "new-password", deviceId: "new-device" },
    });
    expect(newLogin.json().status).toBe("AUTHENTICATED");
  });

  it("runs registration through settings and the complete content-formation chain", async () => {
    const delivered: RegistrationVerificationEmail[] = [];
    app = await createLocalApp({
      dataDirectory: undefined,
      developmentCreditFen: 10_000,
      aiProvider: new LocalOpenAiMeteredProvider({
        generateJson: async (task, input) => {
          if (task === "CONTENT_SAFETY_CHECK_V1") return { decision: "PASS" };
          if (task === "SEMANTIC_UNIT_SPLIT_V1") {
            const source = (input as { text: string }).text;
            const firstBoundary = source.indexOf("。") + 1;
            return {
              segments: [
                {
                  textZh: source.slice(0, firstBoundary),
                  speakerRole: null,
                  semanticPurposeZh: "描述会前感受",
                  recommendationReasonZh: "内容具体",
                },
                {
                  textZh: source.slice(firstBoundary),
                  speakerRole: null,
                  semanticPurposeZh: "描述后续行动",
                  recommendationReasonZh: "适合学习",
                },
              ],
            };
          }
          if (task === "CHAPTER_CONTEXT_SELECTION_V1") {
            const value = input as {
              units: Array<{ unitId: string }>;
              selectedUnitIds: string[];
            };
            return {
              chapters: [
                {
                  chapterId: "chapter-1",
                  unitIds: value.units.map((unit) => unit.unitId),
                },
              ],
            };
          }
          if (task === "ENGLISH_CANDIDATE_GENERATION_V1") {
            const value = input as {
              units: Array<{ unitId: string; textZh: string }>;
            };
            return {
              sentences: value.units.map((unit, index) => ({
                sourceUnitId: unit.unitId,
                candidates: [
                  {
                    candidateId: `candidate-${index + 1}`,
                    english:
                      index === 0
                        ? "I felt nervous before the meeting."
                        : "Then I worked up the courage to explain my idea clearly.",
                    chineseCorrespondenceZh: unit.textZh,
                    meaningClaims: [unit.textZh],
                    styleFit: "自然、简洁",
                    reusedKnowledgeRefs: [],
                    fidelity: {
                      facts: true,
                      entities: true,
                      time: true,
                      conditions: true,
                      causality: true,
                      logic: true,
                      tone: true,
                      questions: true,
                      noHallucination: true,
                    },
                  },
                ],
              })),
            };
          }
          if (task === "LEARNING_TARGET_EXTRACTION_V1") {
            const value = input as {
              chapters: Array<{
                sentences: Array<{
                  unitId: string;
                  textEn: string;
                  isFocus: boolean;
                }>;
              }>;
            };
            const focus = value.chapters
              .flatMap((chapter) => chapter.sentences)
              .find((sentence) => sentence.isFocus)!;
            return {
              targets: [
                {
                  targetId: "target-1",
                  unitId: focus.unitId,
                  display: "worked up the courage",
                  explanationZh: "在当前语境中表示鼓起勇气。",
                  type: "PATTERN",
                  structureParts: ["work up", "the courage"],
                  relatedVocabulary: ["courage"],
                  selectedByDefault: true,
                  sourceEvidence: {
                    version: 1,
                    startUtf16: focus.textEn.indexOf("worked up the courage"),
                    endUtf16:
                      focus.textEn.indexOf("worked up the courage") +
                      "worked up the courage".length,
                    surfaceForm: "worked up the courage",
                    normalizedForm: "work up the courage",
                    allowedForms: ["worked up the courage"],
                  },
                },
              ],
              queryableExpressions: [
                {
                  expressionId: "expression-1",
                  unitId: focus.unitId,
                  display: "worked up the courage",
                },
              ],
            };
          }
          throw new Error(`Unexpected AI task in content flow: ${task}`);
        },
        transcribe: async () => "",
      }),
      registrationEmail: {
        publicApiBaseUrl: "http://127.0.0.1:3000",
        sender: {
          send: async (message) => {
            delivered.push(message);
          },
          sendPasswordReset: async () => {},
        },
      },
    });
    const email = "full-chain@example.com";
    const password = "correct horse battery staple";
    const registration = await app.inject({
      method: "POST",
      url: "/api/v1/auth/registrations",
      headers: { "x-request-id": "full-chain-registration" },
      payload: {
        email,
        password,
        birthYearMonth: "2000-01",
        acceptedTerms: true,
        acceptedPrivacyPolicy: true,
      },
    });
    expect(registration.statusCode).toBe(200);
    await vi.waitFor(() => expect(delivered).toHaveLength(1), {
      timeout: 3_000,
    });
    const verificationUrl = new URL(delivered[0]!.verificationUrl);
    const verified = await app.inject({
      method: "POST",
      url: `/api/v1/auth/registrations/${registration.json().registrationId}/verification`,
      payload: { bearer: verificationUrl.searchParams.get("bearer") },
    });
    expect(verified.json().status).toBe("ACCOUNT_CREATED");

    const login = await app.inject({
      method: "POST",
      url: "/api/v1/auth/sessions",
      headers: { "x-request-id": "full-chain-login" },
      payload: { email, password, deviceId: "full-chain-device" },
    });
    expect(login.json().status).toBe("AUTHENTICATED");
    const authorization = `Bearer ${login.json().accessToken}`;
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
    const savedSettings = await app.inject({
      method: "PUT",
      url: "/api/v1/settings/current/initial",
      headers: { authorization, "x-request-id": "full-chain-settings" },
      payload: settings,
    });
    expect(savedSettings.json().status).toBe("COMPLETED");
    const currentSettings = await app.inject({
      method: "GET",
      url: "/api/v1/settings/current",
      headers: { authorization },
    });
    expect(currentSettings.json()).toEqual(settings);

    const source = "今天开会前我有点紧张。后来我鼓起勇气讲清楚了自己的想法。";
    const safety = await app.inject({
      method: "POST",
      url: "/api/v1/safety/checks",
      headers: { authorization },
      payload: {
        text: source,
        sourceType: "DIARY",
        contentShape: "ARTICLE",
        commandId: "full-chain-safety",
      },
    });
    expect(safety.json()).toEqual({ status: "PASSED" });
    const split = await app.inject({
      method: "POST",
      url: "/api/v1/content-formation/semantic-units/split",
      headers: { authorization },
      payload: {
        text: source,
        contentShape: "ARTICLE",
        commandId: "full-chain-split",
      },
    });
    expect(split.statusCode).toBe(200);
    expect(
      split
        .json()
        .units.map((unit: { textZh: string }) => unit.textZh)
        .join(""),
    ).toBe(source);
    const units = split
      .json()
      .units.map(
        ({
          unitId,
          order,
          textZh,
        }: {
          unitId: string;
          order: number;
          textZh: string;
        }) => ({ unitId, order, textZh }),
      );
    const selectedUnitIds = [units[1]!.unitId];
    const chapters = await app.inject({
      method: "POST",
      url: "/api/v1/content-formation/chapters/context-selection",
      headers: { authorization },
      payload: { units, selectedUnitIds, commandId: "full-chain-chapters" },
    });
    expect(chapters.statusCode).toBe(200);
    const english = await app.inject({
      method: "POST",
      url: "/api/v1/content-formation/english-candidates",
      headers: { authorization },
      payload: {
        sourceType: "DIARY",
        units,
        chapters: chapters.json().chapters,
        selectedUnitIds,
        commandId: "full-chain-english",
      },
    });
    expect(english.statusCode).toBe(200);
    const confirmedChapters = english.json().chapters.map(
      (chapter: {
        chapterId: string;
        sentences: Array<{
          sourceUnitId: string;
          textZh: string;
          candidates: Array<{ english: string }>;
        }>;
      }) => ({
        chapterId: chapter.chapterId,
        sentences: chapter.sentences.map((sentence) => ({
          unitId: sentence.sourceUnitId,
          textZh: sentence.textZh,
          textEn: sentence.candidates[0]!.english,
          isFocus: selectedUnitIds.includes(sentence.sourceUnitId),
        })),
      }),
    );
    const targets = await app.inject({
      method: "POST",
      url: "/api/v1/content-formation/learning-targets",
      headers: { authorization },
      payload: { chapters: confirmedChapters, commandId: "full-chain-targets" },
    });
    expect(targets.statusCode).toBe(200);
    expect(targets.json().targets.length).toBeGreaterThan(0);
    expect(targets.json().targets[0].sourceEvidence).toEqual({
      version: 1,
      startUtf16: 7,
      endUtf16: 28,
      surfaceForm: "worked up the courage",
      normalizedForm: "work up the courage",
      allowedForms: ["worked up the courage"],
    });
    expect(targets.json().queryableExpressions.length).toBeGreaterThan(0);
    const billing = await app.inject({
      method: "GET",
      url: "/api/v1/billing/summary",
      headers: { authorization },
    });
    expect(billing.statusCode).toBe(200);
    expect(billing.json().balanceAmountFen).toBeGreaterThan(0);
    expect(billing.json().charges).toHaveLength(5);
  });
});
