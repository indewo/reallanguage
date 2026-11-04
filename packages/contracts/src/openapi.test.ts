import { readFile } from "node:fs/promises";
import { parse } from "yaml";
import { describe, expect, it } from "vitest";
import { z } from "zod";

const openApiShape = z.object({
  openapi: z.literal("3.1.0"),
  servers: z.array(z.object({ url: z.string() })),
  paths: z.object({
    "/auth/sessions": z.object({
      post: z.object({ operationId: z.literal("createSession") }),
    }),
    "/safety/checks": z.object({
      post: z.object({ operationId: z.literal("checkContentSafety") }),
    }),
    "/content-formation/semantic-units/split": z.object({
      post: z.object({ operationId: z.literal("splitSemanticUnits") }),
    }),
    "/content-formation/chapters/context-selection": z.object({
      post: z.object({ operationId: z.literal("selectChapterContext") }),
    }),
    "/content-formation/english-candidates": z.object({
      post: z.object({ operationId: z.literal("generateEnglishCandidates") }),
    }),
    "/content-formation/learning-targets": z.object({
      post: z.object({ operationId: z.literal("extractLearningTargets") }),
    }),
    "/learning/explanations": z.object({
      post: z.object({ operationId: z.literal("generateLearningExplanation") }),
    }),
    "/media/recording-transcriptions": z.object({
      post: z.object({ operationId: z.literal("transcribeRecording") }),
    }),
    "/media/transcript-repairs": z.object({
      post: z.object({ operationId: z.literal("repairTranscript") }),
    }),
    "/sources/scenes/recommendations": z.object({
      post: z.object({
        operationId: z.literal("generateSceneRecommendations"),
      }),
    }),
    "/sources/scenes/next-question": z.object({
      post: z.object({ operationId: z.literal("getNextSceneQuestion") }),
    }),
    "/sources/scenes/drafts": z.object({
      post: z.object({ operationId: z.literal("generateSceneDraft") }),
    }),
    "/sources/scenes/edit-reviews": z.object({
      post: z.object({ operationId: z.literal("reviewSceneEdit") }),
    }),
    "/learning/reading-feedback": z.object({
      post: z.object({ operationId: z.literal("generateReadingFeedback") }),
    }),
    "/learning/speaking-transcriptions": z.object({
      post: z.object({
        operationId: z.literal("generateSpeakingTranscription"),
      }),
    }),
    "/learning/diary-dictations": z.object({
      post: z.object({ operationId: z.literal("generateDiaryDictation") }),
    }),
    "/learning/full-sentence-speaking-evaluations": z.object({
      post: z.object({
        operationId: z.literal("evaluateFullSentenceSpeaking"),
      }),
    }),
    "/learning/pattern-practice-sets": z.object({
      post: z.object({ operationId: z.literal("generatePatternPractice") }),
    }),
    "/learning/pattern-practice-feedback": z.object({
      post: z.object({
        operationId: z.literal("generatePatternPracticeFeedback"),
      }),
    }),
    "/safety/cases/{safetyCaseId}/handling": z.object({
      post: z.object({ operationId: z.literal("prepareSafetyHandling") }),
    }),
    "/messages": z.object({
      get: z.object({ operationId: z.literal("listAppMessages") }),
    }),
    "/account/current": z.object({
      get: z.object({ operationId: z.literal("getCurrentAccountProfile") }),
      delete: z.object({ operationId: z.literal("deleteCurrentAccount") }),
    }),
    "/billing/summary": z.object({
      get: z.object({ operationId: z.literal("getBillingSummary") }),
    }),
    "/billing/recharge-config": z.object({
      get: z.object({ operationId: z.literal("getRechargeConfiguration") }),
    }),
    "/billing/recharge-orders": z.object({
      post: z.object({ operationId: z.literal("createRechargeOrder") }),
    }),
    "/billing/recharge-orders/{orderId}": z.object({
      get: z.object({ operationId: z.literal("getRechargeOrder") }),
    }),
    "/billing/refunds/current": z.object({
      get: z.object({ operationId: z.literal("getCurrentRefund") }),
    }),
    "/billing/refunds": z.object({
      post: z.object({ operationId: z.literal("requestRefund") }),
    }),
    "/billing/payment-events": z.object({
      post: z.object({ operationId: z.literal("applyPaymentEvent") }),
    }),
    "/billing/minor-recharge-support": z.object({
      post: z.object({
        operationId: z.literal("requestMinorRechargeSupport"),
      }),
    }),
    "/safety/cases/manual-reviews/{reviewRequestId}": z.object({
      get: z.object({ operationId: z.literal("getManualReviewResult") }),
    }),
    "/auth/sessions/refresh": z.object({
      post: z.object({ operationId: z.literal("refreshSession") }),
    }),
    "/auth/sessions/current": z.object({
      delete: z.object({ operationId: z.literal("revokeCurrentSession") }),
    }),
    "/auth/password-resets": z.object({
      post: z.object({ operationId: z.literal("requestPasswordReset") }),
    }),
    "/auth/password-resets/{challengeId}/verification": z.object({
      post: z.object({ operationId: z.literal("verifyPasswordReset") }),
    }),
    "/auth/password-resets/completion": z.object({
      post: z.object({ operationId: z.literal("completePasswordReset") }),
    }),
    "/auth/registrations": z.object({
      post: z.object({ operationId: z.literal("startRegistration") }),
    }),
    "/auth/registrations/{registrationId}/verification": z.object({
      post: z.object({ operationId: z.literal("verifyRegistration") }),
    }),
    "/auth/registrations/{registrationId}/verification-email": z.object({
      post: z.object({
        operationId: z.literal("resendRegistrationVerificationEmail"),
      }),
    }),
    "/auth/registrations/{registrationId}/verification-status": z.object({
      get: z.object({
        operationId: z.literal("getRegistrationVerificationStatus"),
      }),
    }),
  }),
  components: z.object({
    schemas: z.object({
      LoginRequest: z.object({ required: z.array(z.string()) }),
      SafetyCheckRequest: z.object({ required: z.array(z.string()) }),
      SafetyCheckResult: z.object({
        discriminator: z.object({ propertyName: z.literal("status") }),
      }),
      LoginResult: z.object({
        discriminator: z.object({ propertyName: z.literal("status") }),
      }),
      RefreshSessionRequest: z.object({ required: z.array(z.string()) }),
      RefreshSessionResult: z.object({
        discriminator: z.object({ propertyName: z.literal("status") }),
      }),
      RevokeSessionRequest: z.object({ required: z.array(z.string()) }),
      PasswordResetRequest: z.object({ required: z.array(z.string()) }),
      PasswordResetCompletionResult: z.object({
        discriminator: z.object({ propertyName: z.literal("status") }),
      }),
      RegistrationRequest: z.object({
        required: z.array(z.string()),
      }),
      RegistrationResult: z.object({
        discriminator: z.object({ propertyName: z.literal("status") }),
      }),
      RegistrationVerificationResult: z.object({
        discriminator: z.object({ propertyName: z.literal("status") }),
      }),
      RegistrationVerificationEmailResendResult: z.object({
        discriminator: z.object({ propertyName: z.literal("status") }),
      }),
      LearningExplanationRequest: z.object({ required: z.array(z.string()) }),
      LearningExplanationResult: z.object({
        discriminator: z.object({ propertyName: z.literal("kind") }),
      }),
      ReadingFeedbackRequest: z.object({ required: z.array(z.string()) }),
      ReadingFeedbackResult: z.object({ required: z.array(z.string()) }),
      DiaryDictationRequest: z.object({ required: z.array(z.string()) }),
      AccountDeletionCommand: z.object({ required: z.array(z.string()) }),
      AccountDeletionResult: z.object({ required: z.array(z.string()) }),
      BillingSummary: z.object({ required: z.array(z.string()) }),
      RechargeConfiguration: z.object({ required: z.array(z.string()) }),
      RechargeOrderCommand: z.object({ required: z.array(z.string()) }),
      RechargeOrderResult: z.object({ required: z.array(z.string()) }),
      RefundPage: z.object({ required: z.array(z.string()) }),
      RefundCommand: z.object({ required: z.array(z.string()) }),
      RefundResult: z.object({ required: z.array(z.string()) }),
      PaymentEventEnvelope: z.object({ required: z.array(z.string()) }),
    }),
  }),
});

describe("versioned OpenAPI source", () => {
  it("publishes login, registration, and verification contracts under api v1", async () => {
    const source = await readFile(
      new URL("../openapi/real-language-v1.yaml", import.meta.url),
      "utf8",
    );
    const document = openApiShape.parse(parse(source) as unknown);

    expect(document.servers).toContainEqual({ url: "/api/v1" });
    expect(document.components.schemas.LoginRequest.required).toEqual([
      "email",
      "password",
      "deviceId",
    ]);
    expect(document.components.schemas.RegistrationRequest.required).toEqual([
      "email",
      "password",
      "birthYearMonth",
      "acceptedTerms",
      "acceptedPrivacyPolicy",
    ]);
    expect(
      document.components.schemas.RegistrationResult.discriminator.propertyName,
    ).toBe("status");
    expect(
      document.components.schemas.RegistrationVerificationResult.discriminator
        .propertyName,
    ).toBe("status");
    expect(
      document.components.schemas.LearningExplanationRequest.required,
    ).toEqual(["chapter", "focusSentenceId", "round", "commandId"]);
    expect(
      document.components.schemas.LearningExplanationResult.discriminator
        .propertyName,
    ).toBe("kind");
    expect(document.components.schemas.ReadingFeedbackRequest.required).toEqual(
      [
        "chapterId",
        "sentenceId",
        "targetTextEn",
        "completionSignal",
        "audioMimeType",
        "audioBase64",
        "commandId",
      ],
    );
    expect(document.components.schemas.DiaryDictationRequest.required).toEqual([
      "audioMimeType",
      "audioBase64",
      "commandId",
    ]);
    expect(document.components.schemas.AccountDeletionCommand.required).toEqual(
      ["commandId"],
    );
    expect(document.components.schemas.AccountDeletionResult.required).toEqual([
      "status",
    ]);
    expect(document.components.schemas.BillingSummary.required).toEqual([
      "balanceAmountFen",
      "charges",
    ]);
  });
});
