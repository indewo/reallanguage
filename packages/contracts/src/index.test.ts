import { describe, expect, it } from "vitest";
import {
  apiErrorResponseSchema,
  healthResponseSchema,
  loginRequestSchema,
  loginResultSchema,
  refreshSessionRequestSchema,
  refreshSessionResultSchema,
  revokeSessionRequestSchema,
  revokeSessionResultSchema,
  registrationRequestSchema,
  registrationResultSchema,
  registrationVerificationEmailResendResultSchema,
  registrationVerificationRequestSchema,
  registrationVerificationResultSchema,
  registrationVerificationStatusResultSchema,
  initialSettingsCompletionRequestSchema,
  initialSettingsCompletionResultSchema,
  agreementDocumentSchema,
  passwordResetRequestSchema,
  passwordResetRequestResultSchema,
  passwordResetVerificationRequestSchema,
  passwordResetVerificationResultSchema,
  passwordResetCompletionRequestSchema,
  passwordResetCompletionResultSchema,
  safetyCheckRequestSchema,
  safetyCheckResultSchema,
  semanticUnitSplitRequestSchema,
  semanticUnitSplitResultSchema,
  chapterContextSelectionRequestSchema,
  chapterContextSelectionResultSchema,
  appMessagesResponseSchema,
  manualReviewResultSchema,
  accountProfileSchema,
  accountDeletionCommandSchema,
  accountDeletionResultSchema,
  billingSummarySchema,
  rechargeConfigSchema,
  rechargeOrderCommandSchema,
  rechargeOrderResultSchema,
  refundPageSchema,
  refundCommandSchema,
  refundResultSchema,
  minorRechargeSupportResultSchema,
  paymentEventEnvelopeSchema,
  sceneNextQuestionRequestSchema,
  sceneNextQuestionResultSchema,
  sceneDraftResultSchema,
} from "./index.js";

describe("scene clarification contract", () => {
  const answers = Array.from({ length: 10 }, (_, index) => ({
    questionId: `q${index + 1}`,
    questionZh: `问题${index + 1}`,
    answerZh: "重复且没有新增信息",
  }));

  it("accepts ten questions and an explicit insufficient-information result", () => {
    expect(
      sceneNextQuestionRequestSchema.parse({
        operationId: "operation",
        start: {
          kind: "TEMPLATE",
          templateId: "work",
          titleZh: "工作沟通",
          scopeZh: "工作场景",
        },
        contentType: "DIALOGUE",
        confirmedAnswers: answers,
        remainingQuestions: 0,
        remainingContentBudget: 10,
        stepIndex: 10,
        commandId: "command",
      }).confirmedAnswers,
    ).toHaveLength(10);
    expect(
      sceneNextQuestionResultSchema.parse({
        action: "INSUFFICIENT_INFORMATION",
      }),
    ).toEqual({ action: "INSUFFICIENT_INFORMATION" });
  });

  it("rejects an eleventh answer and drafts shorter than three units", () => {
    expect(() =>
      sceneNextQuestionRequestSchema.parse({
        operationId: "operation",
        start: { kind: "DIRECT_DESCRIPTION", description: "描述" },
        contentType: "ARTICLE",
        confirmedAnswers: [
          ...answers,
          { questionId: "q11", questionZh: "问题11", answerZh: "回答" },
        ],
        remainingQuestions: 0,
        remainingContentBudget: 10,
        stepIndex: 11,
        commandId: "command",
      }),
    ).toThrow();
    expect(() =>
      sceneDraftResultSchema.parse({
        draftId: "draft",
        contentType: "ARTICLE",
        units: [
          { unitId: "u1", speaker: null, textZh: "第一句", factReferences: [] },
          { unitId: "u2", speaker: null, textZh: "第二句", factReferences: [] },
        ],
      }),
    ).toThrow();
  });
});

describe("initial settings contract", () => {
  const request = {
    formalityLevel: 3,
    slangEnabled: true,
    basicVocabulary: "CET_6",
    purposeVocabularies: ["IELTS_TOEFL", "BUSINESS_ENGLISH"],
    styleReference: "Clear and direct",
    voiceEnabled: true,
    autoPlayEnabled: false,
    speechSpeedLevel: 4,
  };

  it("accepts one complete explicit settings snapshot", () => {
    expect(initialSettingsCompletionRequestSchema.parse(request)).toEqual(
      request,
    );
    expect(
      initialSettingsCompletionResultSchema.parse({
        status: "COMPLETED",
        rowVersion: 1,
      }),
    ).toEqual({ status: "COMPLETED", rowVersion: 1 });
  });

  it.each([
    "JUNIOR_HIGH",
    "SENIOR_HIGH",
    "CET_4",
    "CET_6",
    "POSTGRADUATE",
    "TOEFL",
    "IELTS",
    "GRE",
  ])("accepts peer vocabulary pool %s without a purpose pool", (pool) => {
    expect(
      initialSettingsCompletionRequestSchema.parse({
        ...request,
        basicVocabulary: pool,
        purposeVocabularies: [],
      }).basicVocabulary,
    ).toBe(pool);
  });

  it("rejects incomplete levels and duplicate purpose vocabularies", () => {
    expect(() =>
      initialSettingsCompletionRequestSchema.parse({
        ...request,
        formalityLevel: 0,
      }),
    ).toThrow();
    expect(() =>
      initialSettingsCompletionRequestSchema.parse({
        ...request,
        purposeVocabularies: ["GRE", "GRE"],
      }),
    ).toThrow();
  });
});

describe("agreement document contract", () => {
  it("requires a versioned authoritative document with non-empty sections", () => {
    expect(
      agreementDocumentSchema.parse({
        kind: "PRIVACY_POLICY",
        titleZh: "隐私政策",
        version: "2026-09-01",
        sections: ["第一节"],
      }),
    ).toMatchObject({ kind: "PRIVACY_POLICY", version: "2026-09-01" });
    expect(() =>
      agreementDocumentSchema.parse({
        kind: "USER_AGREEMENT",
        titleZh: "用户协议",
        version: "",
        sections: [],
      }),
    ).toThrow();
  });
});

describe("password reset contracts", () => {
  const bearer = Buffer.alloc(32, 9).toString("base64url");

  it("supports neutral request, verified link, and completed reset outcomes", () => {
    expect(
      passwordResetRequestSchema.parse({
        email: "learner@example.com",
        commandId: "request-command-1",
      }),
    ).toMatchObject({ email: "learner@example.com" });
    expect(
      passwordResetRequestResultSchema.parse({
        status: "SENT",
        challengeId: "10000000-0000-4000-8000-000000000001",
      }),
    ).toMatchObject({ status: "SENT" });
    expect(passwordResetVerificationRequestSchema.parse({ bearer })).toEqual({
      bearer,
    });
    expect(
      passwordResetVerificationResultSchema.parse({
        status: "VERIFIED",
        resetToken: bearer,
      }),
    ).toMatchObject({ status: "VERIFIED" });
    expect(
      passwordResetCompletionRequestSchema.parse({
        resetToken: bearer,
        newPassword: "a sufficiently long password",
        commandId: "reset-command-1",
      }),
    ).toMatchObject({ commandId: "reset-command-1" });
    expect(
      passwordResetCompletionResultSchema.parse({
        status: "COMPLETED",
        sessionEstablished: false,
        initialSettingsComplete: true,
      }),
    ).toMatchObject({ status: "COMPLETED" });
  });

  it("rejects short link and reset bearers", () => {
    expect(() =>
      passwordResetVerificationRequestSchema.parse({ bearer: "short" }),
    ).toThrow();
    expect(() =>
      passwordResetCompletionRequestSchema.parse({
        resetToken: "short",
        newPassword: "a sufficiently long password",
        commandId: "reset-command-1",
      }),
    ).toThrow();
  });
});

describe("content safety check contract", () => {
  it("accepts the full confirmed source and a stable command identity", () => {
    expect(
      safetyCheckRequestSchema.parse({
        text: "今天发生了一件值得记录的事。",
        sourceType: "DIARY",
        contentShape: "ARTICLE",
        commandId: "safety-command-1",
      }),
    ).toMatchObject({ sourceType: "DIARY", contentShape: "ARTICLE" });
  });

  it("supports only passed or identified blocked outcomes", () => {
    expect(safetyCheckResultSchema.parse({ status: "PASSED" })).toEqual({
      status: "PASSED",
    });
    expect(
      safetyCheckResultSchema.parse({
        status: "BLOCKED",
        safetyCaseId: "10000000-0000-4000-8000-000000000001",
      }),
    ).toMatchObject({ status: "BLOCKED" });
    expect(() =>
      safetyCheckResultSchema.parse({ status: "UNCERTAIN" }),
    ).toThrow();
  });
});

describe("semantic unit split contract", () => {
  it("accepts complete ordered source references", () => {
    const request = semanticUnitSplitRequestSchema.parse({
      text: "今天很忙。",
      contentShape: "ARTICLE",
      commandId: "split-command-1",
    });
    expect(request.contentShape).toBe("ARTICLE");
    const result = semanticUnitSplitResultSchema.parse({
      units: [
        {
          unitId: "unit-1",
          order: 0,
          sourceSpans: [{ startCodePoint: 0, endCodePoint: 5 }],
          textZh: "今天很忙。",
          speakerRole: null,
          semanticPurposeZh: "陈述当天状态",
          recommendationReasonZh: null,
        },
      ],
      coverage: { complete: true, missingSpans: [], duplicatedSpans: [] },
    });
    expect(result.units).toHaveLength(1);
  });

  it("rejects incomplete provider coverage", () => {
    expect(() =>
      semanticUnitSplitResultSchema.parse({
        units: [],
        coverage: { complete: false, missingSpans: [], duplicatedSpans: [] },
      }),
    ).toThrow();
  });
});

describe("chapter context selection contract", () => {
  it("accepts one to three unique focus units and chapters of at most four units", () => {
    expect(
      chapterContextSelectionRequestSchema.parse({
        units: [{ unitId: "u1", order: 0, textZh: "第一句" }],
        selectedUnitIds: ["u1"],
        commandId: "chapter-1",
      }).selectedUnitIds,
    ).toEqual(["u1"]);
    expect(
      chapterContextSelectionResultSchema.parse({
        chapters: [{ chapterId: "c1", unitIds: ["u1"] }],
      }).chapters,
    ).toHaveLength(1);
    expect(() =>
      chapterContextSelectionRequestSchema.parse({
        units: [{ unitId: "u1", order: 0, textZh: "第一句" }],
        selectedUnitIds: ["u1", "u1"],
        commandId: "chapter-1",
      }),
    ).toThrow();
  });
});

describe("healthResponseSchema", () => {
  it("accepts the stable health response", () => {
    expect(
      healthResponseSchema.parse({
        status: "ok",
        service: "api",
        requestId: "req-1",
      }),
    ).toEqual({
      status: "ok",
      service: "api",
      requestId: "req-1",
    });
  });

  it("rejects a response without request correlation", () => {
    expect(() =>
      healthResponseSchema.parse({ status: "ok", service: "api" }),
    ).toThrow();
  });
});

describe("login session contract", () => {
  const bearer = Buffer.alloc(32, 7).toString("base64url");

  it("accepts credentials and a successful opaque session", () => {
    expect(
      loginRequestSchema.parse({
        email: "learner@example.com",
        password: "not-a-real-password",
        deviceId: "android-installation-1",
      }),
    ).toEqual({
      email: "learner@example.com",
      password: "not-a-real-password",
      deviceId: "android-installation-1",
    });
    expect(
      loginResultSchema.parse({
        status: "AUTHENTICATED",
        accountId: "30000000-0000-4000-8000-000000000001",
        accessToken: bearer,
        refreshToken: bearer,
        accessTokenExpiresInSeconds: 900,
        settingsCompleted: false,
      }),
    ).toMatchObject({ status: "AUTHENTICATED", settingsCompleted: false });
  });

  it("uses one neutral invalid-credentials outcome and never accepts short bearers", () => {
    expect(loginResultSchema.parse({ status: "INVALID_CREDENTIALS" })).toEqual({
      status: "INVALID_CREDENTIALS",
    });
    expect(() =>
      loginResultSchema.parse({
        status: "AUTHENTICATED",
        accountId: "30000000-0000-4000-8000-000000000001",
        accessToken: "short",
        refreshToken: bearer,
        accessTokenExpiresInSeconds: 900,
        settingsCompleted: true,
      }),
    ).toThrow();
  });
});

describe("refresh and revoke session contracts", () => {
  const bearer = Buffer.alloc(32, 8).toString("base64url");

  it("requires an opaque refresh bearer and installation identity", () => {
    expect(
      refreshSessionRequestSchema.parse({
        refreshToken: bearer,
        deviceId: "android-installation-1",
      }),
    ).toEqual({ refreshToken: bearer, deviceId: "android-installation-1" });
    expect(
      refreshSessionResultSchema.parse({ status: "INVALID_OR_EXPIRED" }),
    ).toEqual({ status: "INVALID_OR_EXPIRED" });
  });

  it("supports idempotent current-session revocation", () => {
    expect(revokeSessionRequestSchema.parse({ refreshToken: bearer })).toEqual({
      refreshToken: bearer,
    });
    expect(revokeSessionResultSchema.parse({ status: "REVOKED" })).toEqual({
      status: "REVOKED",
    });
  });
});

describe("registration request contract", () => {
  const validRequest = {
    email: "learner@example.com",
    password: "not-a-real-password",
    birthYearMonth: "2012-09",
    acceptedTerms: true,
    acceptedPrivacyPolicy: true,
  };

  it("accepts the confirmed registration fields", () => {
    expect(registrationRequestSchema.parse(validRequest)).toEqual(validRequest);
  });

  it("requires both agreements", () => {
    expect(() =>
      registrationRequestSchema.parse({
        ...validRequest,
        acceptedPrivacyPolicy: false,
      }),
    ).toThrow();
  });

  it("requires a real calendar month", () => {
    expect(() =>
      registrationRequestSchema.parse({
        ...validRequest,
        birthYearMonth: "2012-13",
      }),
    ).toThrow();
  });
});

describe("registration response contract", () => {
  it("requires an opaque UUID for the pending registration", () => {
    expect(
      registrationResultSchema.parse({
        status: "EMAIL_VERIFICATION_REQUIRED",
        registrationId: "10000000-0000-4000-8000-000000000001",
      }),
    ).toEqual({
      status: "EMAIL_VERIFICATION_REQUIRED",
      registrationId: "10000000-0000-4000-8000-000000000001",
    });
    expect(() =>
      registrationResultSchema.parse({
        status: "EMAIL_VERIFICATION_REQUIRED",
        registrationId: "not-an-id",
      }),
    ).toThrow();
  });
});

describe("registration verification contract", () => {
  const bearer = Buffer.alloc(32, 7).toString("base64url");

  it("accepts only a 256-bit base64url bearer", () => {
    expect(registrationVerificationRequestSchema.parse({ bearer })).toEqual({
      bearer,
    });
    expect(() =>
      registrationVerificationRequestSchema.parse({ bearer: "short" }),
    ).toThrow();
  });

  it("supports created and invalid-or-expired outcomes", () => {
    expect(
      registrationVerificationResultSchema.parse({
        status: "ACCOUNT_CREATED",
        accountId: "30000000-0000-4000-8000-000000000001",
      }),
    ).toEqual({
      status: "ACCOUNT_CREATED",
      accountId: "30000000-0000-4000-8000-000000000001",
    });
    expect(
      registrationVerificationResultSchema.parse({
        status: "INVALID_OR_EXPIRED",
      }),
    ).toEqual({ status: "INVALID_OR_EXPIRED" });
  });

  it("supports waiting, verified, and unavailable status polling", () => {
    for (const status of ["WAITING", "VERIFIED", "NOT_AVAILABLE"] as const) {
      expect(
        registrationVerificationStatusResultSchema.parse({ status }),
      ).toEqual({ status });
    }
  });
});

describe("registration verification email resend contract", () => {
  it("supports resent, cooldown, and unavailable outcomes", () => {
    expect(
      registrationVerificationEmailResendResultSchema.parse({
        status: "RESENT",
        retryAfterSeconds: 60,
      }),
    ).toEqual({ status: "RESENT", retryAfterSeconds: 60 });
    expect(
      registrationVerificationEmailResendResultSchema.parse({
        status: "COOLDOWN",
        retryAfterSeconds: 12,
      }),
    ).toEqual({ status: "COOLDOWN", retryAfterSeconds: 12 });
    expect(
      registrationVerificationEmailResendResultSchema.parse({
        status: "NOT_AVAILABLE",
      }),
    ).toEqual({ status: "NOT_AVAILABLE" });
  });

  it("rejects a non-positive or fractional retry delay", () => {
    expect(() =>
      registrationVerificationEmailResendResultSchema.parse({
        status: "COOLDOWN",
        retryAfterSeconds: 0,
      }),
    ).toThrow();
    expect(() =>
      registrationVerificationEmailResendResultSchema.parse({
        status: "RESENT",
        retryAfterSeconds: 1.5,
      }),
    ).toThrow();
  });
});

describe("standard API error contract", () => {
  it("requires stable code, retryability, and request correlation", () => {
    const error = {
      error: {
        code: "VALIDATION_FAILED",
        message: "请求内容无效。",
        retryable: false,
        requestId: "request-1",
        details: {},
      },
    };

    expect(apiErrorResponseSchema.parse(error)).toEqual(error);
    expect(() =>
      apiErrorResponseSchema.parse({
        error: { ...error.error, requestId: undefined },
      }),
    ).toThrow();
  });
});

describe("app messages contract", () => {
  it("accepts only controlled message targets", () => {
    const message = {
      messageId: "m1",
      kind: "MANUAL_REVIEW_RESULT",
      summaryZh: "复核完成",
      relatedContentLabelZh: "昨晚的日记",
      createdAt: "2026-09-02T00:00:00.000Z",
      read: false,
      target: { route: "MANUAL_REVIEW_RESULT", resourceId: "r1" },
    };
    expect(appMessagesResponseSchema.parse({ messages: [message] })).toEqual({
      messages: [message],
    });
    expect(() =>
      appMessagesResponseSchema.parse({
        messages: [
          {
            ...message,
            target: { route: "https://evil.example", resourceId: "r1" },
          },
        ],
      }),
    ).toThrow();
  });
});

describe("manual review result contract", () => {
  it("returns only an adjudicated outcome and the temporary approved source", () => {
    const result = {
      outcome: "APPROVED",
      source: {
        safetyCaseId: "case-1",
        text: "内容",
        sourceType: "DIARY",
        contentShape: "ARTICLE",
        userReasonZh: "原因",
      },
    };
    expect(manualReviewResultSchema.parse(result)).toEqual(result);
    expect(() =>
      manualReviewResultSchema.parse({ ...result, outcome: "PENDING" }),
    ).toThrow();
  });
});

describe("account profile contract", () => {
  it("returns only the verified account email", () => {
    expect(
      accountProfileSchema.parse({ email: "learner@example.com" }),
    ).toEqual({ email: "learner@example.com" });
    expect(() => accountProfileSchema.parse({ email: "invalid" })).toThrow();
  });
});
describe("account deletion contract", () => {
  it("requires an explicit stable command", () => {
    expect(
      accountDeletionCommandSchema.parse({ commandId: "delete-1" }),
    ).toEqual({ commandId: "delete-1" });
    expect(accountDeletionResultSchema.parse({ status: "DELETED" })).toEqual({
      status: "DELETED",
    });
  });
});

describe("billing contracts", () => {
  it("publishes only人民币余额 and actual provider charge facts", () => {
    const summary = {
      balanceAmountFen: 12_345,
      charges: [
        {
          chargeId: "charge-1",
          amountFen: 25,
          occurredAt: "2026-09-02T00:00:00.000Z",
          modelSource: "provider/model",
          callType: "USER_RETRY",
          operationLabelZh: "生成英语表达",
        },
      ],
    };
    expect(billingSummarySchema.parse(summary)).toEqual(summary);
    expect(() =>
      billingSummarySchema.parse({ ...summary, tokenBalance: 100 }),
    ).toThrow();
    expect(() =>
      billingSummarySchema.parse({ ...summary, balanceAmountFen: -1 }),
    ).toThrow();
  });

  it("validates recharge and refund commands and states", () => {
    expect(
      rechargeConfigSchema.parse({
        recommendedOptions: [
          { amountFen: 5_000, visionZh: "开始把真正需要的英语练熟" },
        ],
        minimumAmountFen: 100,
        maximumAmountFen: 100_000,
      }).minimumAmountFen,
    ).toBe(100);
    expect(
      rechargeOrderCommandSchema.parse({
        amountFen: 5_000,
        paymentMethod: "WECHAT_PAY",
        commandId: "pay-1",
      }).amountFen,
    ).toBe(5_000);
    expect(
      rechargeOrderResultSchema.parse({
        orderId: "order-1",
        status: "PENDING_PAYMENT",
        paymentLaunch: "launch",
      }).status,
    ).toBe("PENDING_PAYMENT");
    expect(
      refundPageSchema.parse({
        refundableAmountFen: 3_000,
        refundMethodLabelZh: "原路退回",
        requestId: null,
        requestStatus: null,
      }).refundableAmountFen,
    ).toBe(3_000);
    expect(
      refundCommandSchema.parse({ amountFen: 3_000, commandId: "refund-1" })
        .commandId,
    ).toBe("refund-1");
    expect(
      refundResultSchema.parse({ requestId: "refund-1", status: "REQUESTED" })
        .status,
    ).toBe("REQUESTED");
    expect(
      minorRechargeSupportResultSchema.parse({
        supportCaseId: "case-1",
        status: "REQUESTED",
      }).status,
    ).toBe("REQUESTED");
  });
  it("requires a bounded signed payment event envelope", () => {
    expect(
      paymentEventEnvelopeSchema.parse({
        payloadBase64: "e30=",
        signature: "YWJj",
        timestamp: "1788300000",
      }).timestamp,
    ).toBe("1788300000");
    expect(() =>
      paymentEventEnvelopeSchema.parse({
        payloadBase64: "{}",
        signature: "bad!",
        timestamp: "now",
      }),
    ).toThrow();
  });
});
