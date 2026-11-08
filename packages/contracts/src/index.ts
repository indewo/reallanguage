import { z } from "zod";

export const requestIdSchema = z.string().min(1).max(128);

export const healthResponseSchema = z.object({
  status: z.literal("ok"),
  service: z.string().min(1),
  requestId: requestIdSchema,
});

export type HealthResponse = z.infer<typeof healthResponseSchema>;

export const apiErrorResponseSchema = z.object({
  error: z.object({
    code: z.string().regex(/^[A-Z][A-Z0-9_]{1,63}$/),
    message: z.string().min(1),
    retryable: z.boolean(),
    requestId: requestIdSchema,
    details: z.record(z.string(), z.unknown()),
  }),
});

export type ApiErrorResponse = z.infer<typeof apiErrorResponseSchema>;

const opaqueSessionBearerSchema = z.string().regex(/^[A-Za-z0-9_-]{43}$/);

export const loginRequestSchema = z.object({
  email: z.email(),
  password: z.string().min(1),
  deviceId: z.string().min(1).max(256),
});

const authenticatedSessionSchema = z.object({
  status: z.literal("AUTHENTICATED"),
  accountId: z.uuid(),
  accessToken: opaqueSessionBearerSchema,
  refreshToken: opaqueSessionBearerSchema,
  accessTokenExpiresInSeconds: z.number().int().positive(),
  settingsCompleted: z.boolean(),
});

export const loginResultSchema = z.discriminatedUnion("status", [
  z.object({ status: z.literal("INVALID_CREDENTIALS") }),
  authenticatedSessionSchema,
]);

export const refreshSessionRequestSchema = z.object({
  refreshToken: opaqueSessionBearerSchema,
  deviceId: z.string().min(1).max(256),
});

export const refreshSessionResultSchema = z.discriminatedUnion("status", [
  z.object({ status: z.literal("INVALID_OR_EXPIRED") }),
  authenticatedSessionSchema,
]);

export const revokeSessionRequestSchema = z.object({
  refreshToken: opaqueSessionBearerSchema,
});

export const revokeSessionResultSchema = z.object({
  status: z.literal("REVOKED"),
});

export type LoginRequest = z.infer<typeof loginRequestSchema>;
export type LoginResult = z.infer<typeof loginResultSchema>;
export type RefreshSessionRequest = z.infer<typeof refreshSessionRequestSchema>;
export type RefreshSessionResult = z.infer<typeof refreshSessionResultSchema>;
export type RevokeSessionRequest = z.infer<typeof revokeSessionRequestSchema>;
export type RevokeSessionResult = z.infer<typeof revokeSessionResultSchema>;

export const birthYearMonthSchema = z.string().regex(/^\d{4}-(0[1-9]|1[0-2])$/);
export const registrationIdSchema = z.uuid();

export const registrationRequestSchema = z.object({
  email: z.email(),
  password: z.string().min(1),
  birthYearMonth: birthYearMonthSchema,
  acceptedTerms: z.literal(true),
  acceptedPrivacyPolicy: z.literal(true),
});

export const registrationResultSchema = z.discriminatedUnion("status", [
  z.object({ status: z.literal("AGE_INELIGIBLE") }),
  z.object({
    status: z.literal("PASSWORD_REJECTED"),
    reason: z.enum(["TOO_SHORT", "TOO_LONG", "BLOCKED"]),
  }),
  z.object({
    status: z.literal("EMAIL_VERIFICATION_REQUIRED"),
    registrationId: registrationIdSchema,
  }),
]);

export const registrationVerificationRequestSchema = z.object({
  bearer: z.string().regex(/^[A-Za-z0-9_-]{43}$/),
});

export const registrationVerificationResultSchema = z.discriminatedUnion(
  "status",
  [
    z.object({ status: z.literal("INVALID_OR_EXPIRED") }),
    z.object({ status: z.literal("ACCOUNT_CREATED"), accountId: z.uuid() }),
  ],
);

export const registrationVerificationStatusResultSchema = z.discriminatedUnion(
  "status",
  [
    z.object({ status: z.literal("WAITING") }),
    z.object({ status: z.literal("VERIFIED") }),
    z.object({ status: z.literal("NOT_AVAILABLE") }),
  ],
);

export const registrationVerificationEmailResendResultSchema =
  z.discriminatedUnion("status", [
    z.object({ status: z.literal("NOT_AVAILABLE") }),
    z.object({
      status: z.literal("COOLDOWN"),
      retryAfterSeconds: z.number().int().positive(),
    }),
    z.object({
      status: z.literal("RESENT"),
      retryAfterSeconds: z.number().int().positive(),
    }),
  ]);

export type RegistrationRequest = z.infer<typeof registrationRequestSchema>;
export type RegistrationResult = z.infer<typeof registrationResultSchema>;
export type RegistrationVerificationRequest = z.infer<
  typeof registrationVerificationRequestSchema
>;
export type RegistrationVerificationResult = z.infer<
  typeof registrationVerificationResultSchema
>;
export type RegistrationVerificationStatusResult = z.infer<
  typeof registrationVerificationStatusResultSchema
>;
export type RegistrationVerificationEmailResendResult = z.infer<
  typeof registrationVerificationEmailResendResultSchema
>;

export const basicVocabularySchema = z.enum([
  // PRIMARY_SCHOOL and TEM_8 remain wire-compatible for already-published clients only.
  "PRIMARY_SCHOOL",
  "JUNIOR_HIGH",
  "SENIOR_HIGH",
  "CET_4",
  "CET_6",
  "POSTGRADUATE",
  "TOEFL",
  "IELTS",
  "GRE",
  "TEM_8",
]);

export const purposeVocabularySchema = z.enum([
  "IELTS_TOEFL",
  "GRE",
  "BUSINESS_ENGLISH",
]);

export const initialSettingsCompletionRequestSchema = z.object({
  formalityLevel: z.number().int().min(1).max(5),
  slangEnabled: z.boolean(),
  basicVocabulary: basicVocabularySchema,
  purposeVocabularies: z
    .array(purposeVocabularySchema)
    .max(3)
    .refine((values) => new Set(values).size === values.length),
  styleReference: z.string(),
  voiceEnabled: z.boolean(),
  autoPlayEnabled: z.boolean(),
  speechSpeedLevel: z.number().int().min(1).max(5),
});

export const initialSettingsCompletionResultSchema = z.object({
  status: z.literal("COMPLETED"),
  rowVersion: z.number().int().positive(),
});

export const currentSettingsSchema = initialSettingsCompletionRequestSchema;
export type CurrentSettings = z.infer<typeof currentSettingsSchema>;

export const versionedSettingsSchema = z.object({
  settings: currentSettingsSchema,
  rowVersion: z.number().int().positive().safe(),
});
export const updateCurrentSettingsRequestSchema = z
  .object({
    settings: currentSettingsSchema.refine(
      (settings) => settings.voiceEnabled || !settings.autoPlayEnabled,
    ),
    expectedRowVersion: z.number().int().positive().safe(),
  })
  .strict();
export const updateCurrentSettingsResultSchema = z.object({
  status: z.literal("SAVED"),
  rowVersion: z.number().int().positive().safe(),
});

export type InitialSettingsCompletionRequest = z.infer<
  typeof initialSettingsCompletionRequestSchema
>;
export type InitialSettingsCompletionResult = z.infer<
  typeof initialSettingsCompletionResultSchema
>;

export const agreementKindSchema = z.enum(["USER_AGREEMENT", "PRIVACY_POLICY"]);

export const agreementDocumentSchema = z.object({
  kind: agreementKindSchema,
  titleZh: z.string().min(1),
  version: z.string().min(1),
  sections: z.array(z.string().min(1)).min(1),
});

export type AgreementKind = z.infer<typeof agreementKindSchema>;
export type AgreementDocument = z.infer<typeof agreementDocumentSchema>;

const commandIdSchema = z.string().min(1).max(128);

export const passwordResetRequestSchema = z.object({
  email: z.email(),
  commandId: commandIdSchema,
});
export const passwordResetRequestResultSchema = z.object({
  status: z.literal("SENT"),
  challengeId: z.uuid(),
});
export const passwordResetVerificationRequestSchema = z.object({
  bearer: opaqueSessionBearerSchema,
});
export const passwordResetVerificationResultSchema = z.discriminatedUnion(
  "status",
  [
    z.object({ status: z.literal("INVALID_OR_EXPIRED") }),
    z.object({
      status: z.literal("VERIFIED"),
      resetToken: opaqueSessionBearerSchema,
    }),
  ],
);
export const passwordResetCompletionRequestSchema = z.object({
  resetToken: opaqueSessionBearerSchema,
  newPassword: z.string().min(1),
  commandId: commandIdSchema,
});
export const passwordResetCompletionResultSchema = z.discriminatedUnion(
  "status",
  [
    z.object({
      status: z.literal("COMPLETED"),
      sessionEstablished: z.boolean(),
      initialSettingsComplete: z.boolean(),
    }),
    z.object({
      status: z.literal("PASSWORD_REJECTED"),
      reason: z.enum(["TOO_SHORT", "TOO_LONG", "BLOCKED"]),
    }),
    z.object({ status: z.literal("INVALID_OR_EXPIRED") }),
  ],
);

export const safetyCheckRequestSchema = z.object({
  text: z.string().min(1),
  sourceType: z.enum(["DIARY", "RECORDING", "AI_SCENE"]),
  contentShape: z.enum(["ARTICLE", "DIALOGUE"]),
  commandId: commandIdSchema,
});
export const safetyCheckResultSchema = z.discriminatedUnion("status", [
  z.object({ status: z.literal("PASSED") }),
  z.object({ status: z.literal("BLOCKED"), safetyCaseId: z.uuid() }),
]);

const sourceSpanSchema = z.object({
  startCodePoint: z.number().int().nonnegative(),
  endCodePoint: z.number().int().positive(),
});

export const semanticUnitSplitRequestSchema = z.object({
  text: z.string().min(1),
  contentShape: z.enum(["ARTICLE", "DIALOGUE"]),
  commandId: commandIdSchema,
});

export const semanticUnitSplitResultSchema = z.object({
  units: z
    .array(
      z.object({
        unitId: z.string().min(1).max(128),
        order: z.number().int().nonnegative(),
        sourceSpans: z.array(sourceSpanSchema).min(1),
        textZh: z.string().min(1),
        speakerRole: z.string().min(1).nullable(),
        semanticPurposeZh: z.string().min(1),
        recommendationReasonZh: z.string().min(1).nullable(),
      }),
    )
    .min(1),
  coverage: z.object({
    complete: z.literal(true),
    missingSpans: z.array(sourceSpanSchema).length(0),
    duplicatedSpans: z.array(sourceSpanSchema).length(0),
  }),
});

export type SemanticUnitSplitRequest = z.infer<
  typeof semanticUnitSplitRequestSchema
>;
export type SemanticUnitSplitResult = z.infer<
  typeof semanticUnitSplitResultSchema
>;

export const safetyHandlingDetailsSchema = z.object({
  safetyCaseId: z.uuid(),
  originalSource: z.object({
    text: z.string().min(1),
    sourceType: z.enum(["DIARY", "RECORDING", "AI_SCENE"]),
    contentShape: z.enum(["ARTICLE", "DIALOGUE"]),
  }),
  userReasonZh: z.string().min(1),
  deidentifiedSource: z.object({
    text: z.string().min(1),
    sourceType: z.enum(["DIARY", "RECORDING", "AI_SCENE"]),
    contentShape: z.enum(["ARTICLE", "DIALOGUE"]),
  }),
  edits: z
    .array(
      z.object({
        originalText: z.string().min(1),
        replacementText: z.string().min(1),
        explanationZh: z.string().min(1),
      }),
    )
    .min(1),
});
export const safetyHandlingCommandSchema = z.object({
  commandId: commandIdSchema,
});
export const safetyHandlingCommandResultSchema = z.object({
  status: z.literal("COMPLETED"),
});
export const manualReviewSubmissionResultSchema = z.object({
  status: z.literal("SUBMITTED"),
  reviewRequestId: z.uuid(),
  // Optional on the wire for older services; current clients require this
  // explicit state before selecting a pending or already-decided destination.
  reviewStatus: z.enum(["PENDING", "APPROVED", "NOT_APPROVED"]).optional(),
});

const chapterSemanticUnitSchema = z.object({
  unitId: z.string().min(1).max(128),
  order: z.number().int().nonnegative(),
  textZh: z.string().min(1),
});
export const chapterContextSelectionRequestSchema = z.object({
  units: z.array(chapterSemanticUnitSchema).min(1),
  selectedUnitIds: z
    .array(z.string().min(1).max(128))
    .min(1)
    .max(3)
    .refine((ids) => new Set(ids).size === ids.length),
  commandId: commandIdSchema,
});
export const chapterContextSelectionResultSchema = z.object({
  chapters: z
    .array(
      z.object({
        chapterId: z.string().min(1).max(128),
        unitIds: z.array(z.string().min(1).max(128)).min(1).max(4),
      }),
    )
    .min(1),
});

const englishCandidateSchema = z.object({
  candidateId: z.string().min(1).max(128),
  english: z.string().min(1),
  chineseCorrespondenceZh: z.string().min(1),
  meaningClaims: z.array(z.string().min(1)),
  styleFit: z.string().min(1),
  reusedKnowledgeRefs: z.array(z.string().min(1).max(128)),
});

const knownKnowledgeCandidateSchema = z
  .object({
    ref: z.string().min(1).max(128),
    type: z.enum(["VOCABULARY", "PATTERN"]),
    display: z.string().refine((value) => value.trim().length > 0),
    meaningZh: z.string().refine((value) => value.trim().length > 0),
  })
  .strict()
  .refine(
    (item) =>
      item.ref.startsWith(`${item.type}:`) &&
      item.ref.slice(item.type.length + 1).trim().length > 0 &&
      !/\s/u.test(item.ref),
  );
export const englishCandidateGenerationRequestSchema = z.object({
  sourceType: z.enum(["DIARY", "RECORDING", "AI_SCENE"]),
  units: z.array(chapterSemanticUnitSchema).min(1),
  chapters: z
    .array(
      z.object({
        chapterId: z.string().min(1).max(128),
        unitIds: z.array(z.string().min(1).max(128)).min(1).max(4),
      }),
    )
    .min(1),
  selectedUnitIds: z
    .array(z.string().min(1).max(128))
    .min(1)
    .max(3)
    .refine((ids) => new Set(ids).size === ids.length),
  commandId: commandIdSchema,
  knownKnowledge: z
    .array(knownKnowledgeCandidateSchema)
    .max(16)
    .refine(
      (items) =>
        new Set(items.map((item) => item.ref)).size === items.length &&
        items.reduce(
          (total, item) =>
            total +
            Array.from(item.display).length +
            Array.from(item.meaningZh).length,
          0,
        ) <= 4000,
    )
    .optional(),
});

export const englishCandidateGenerationResultSchema = z.object({
  chapters: z
    .array(
      z.object({
        chapterId: z.string().min(1).max(128),
        sentences: z
          .array(
            z.object({
              sourceUnitId: z.string().min(1).max(128),
              textZh: z.string().min(1),
              isFocus: z.boolean(),
              candidates: z.array(englishCandidateSchema).min(1).max(3),
            }),
          )
          .min(1)
          .max(4),
      }),
    )
    .min(1),
});

export type EnglishCandidateGenerationRequest = z.infer<
  typeof englishCandidateGenerationRequestSchema
>;
export type EnglishCandidateGenerationResult = z.infer<
  typeof englishCandidateGenerationResultSchema
>;

const confirmedLearningSentenceSchema = z.object({
  unitId: z.string().min(1).max(128),
  textZh: z.string().min(1),
  textEn: z.string().min(1),
  isFocus: z.boolean(),
  reusedKnowledge: z
    .array(knownKnowledgeCandidateSchema)
    .max(16)
    .refine(
      (items) =>
        new Set(items.map((item) => item.ref)).size === items.length &&
        items.reduce(
          (total, item) =>
            total +
            Array.from(item.display).length +
            Array.from(item.meaningZh).length,
          0,
        ) <= 4000,
    )
    .optional(),
});
export const targetSourceEvidenceSchema = z
  .object({
    version: z.literal(1),
    startUtf16: z.number().int().nonnegative().max(Number.MAX_SAFE_INTEGER),
    endUtf16: z.number().int().positive().max(Number.MAX_SAFE_INTEGER),
    surfaceForm: z.string().refine((value) => value.trim().length > 0),
    normalizedForm: z.string().refine((value) => value.trim().length > 0),
    allowedForms: z.array(z.string()).length(1),
  })
  .strict()
  .refine(
    (value) =>
      value.endUtf16 > value.startUtf16 &&
      value.endUtf16 - value.startUtf16 === value.surfaceForm.length &&
      value.allowedForms[0] === value.surfaceForm,
  );
export const acceptedVocabularySchema = z
  .object({
    display: z.string().refine((value) => value.trim().length > 0),
    senseZh: z.string().refine((value) => value.trim().length > 0),
    unitType: z.enum(["WORD", "FIXED_EXPRESSION"]),
    sourceEvidence: targetSourceEvidenceSchema,
  })
  .strict();
export const learningTargetExtractionRequestSchema = z.object({
  chapters: z
    .array(
      z.object({
        chapterId: z.string().min(1).max(128),
        sentences: z.array(confirmedLearningSentenceSchema).min(1).max(4),
      }),
    )
    .min(1),
  requestedExpression: z
    .object({
      unitId: z.string().min(1).max(128),
      expressionId: z.string().min(1).max(128),
      display: z.string().min(1).optional(),
      acceptedVocabulary: acceptedVocabularySchema.optional(),
    })
    .optional(),
  commandId: commandIdSchema,
});
export const relatedVocabularyItemSchema = z
  .object({
    itemRef: z
      .string()
      .max(128)
      .refine((value) => value.trim().length > 0),
    unitId: z.string().min(1).max(128),
    display: z.string().refine((value) => value.trim().length > 0),
    senseZh: z.string().refine((value) => value.trim().length > 0),
    unitType: z.enum(["WORD", "FIXED_EXPRESSION"]),
    sourceEvidence: targetSourceEvidenceSchema,
  })
  .strict()
  .refine(
    (value) =>
      value.unitType !== "WORD" ||
      (/^[\p{L}\p{M}\p{N}]+(?:['‘’ʼ＇-][\p{L}\p{M}\p{N}]+)*$/u.test(
        value.sourceEvidence.surfaceForm,
      ) &&
        value.display.trim().toLowerCase() ===
          value.sourceEvidence.surfaceForm.toLowerCase()),
  );
export const learningTargetSchema = z
  .object({
    targetId: z.string().min(1).max(128),
    unitId: z.string().min(1).max(128),
    display: z.string().min(1),
    explanationZh: z.string().min(1),
    type: z.enum(["WORD", "FIXED_EXPRESSION", "PATTERN"]),
    candidateExample: z
      .object({
        textEn: z.string().refine((value) => value.trim().length > 0),
        textZh: z.string().refine((value) => value.trim().length > 0),
      })
      .strict()
      .nullable()
      .optional(),
    structureParts: z.array(
      z.string().refine((part) => part.trim().length > 0),
    ),
    relatedVocabulary: z.array(z.string().min(1)),
    relatedVocabularyItems: z
      .array(relatedVocabularyItemSchema)
      .nullable()
      .optional(),
    relatedVocabularyItemRefs: z
      .array(
        z
          .string()
          .max(128)
          .refine((value) => value.trim().length > 0),
      )
      .nullable()
      .optional(),
    selectedByDefault: z.literal(true),
    sourceEvidence: targetSourceEvidenceSchema.nullable().optional(),
    reusedKnowledgeRef: z
      .string()
      .max(128)
      .regex(/^(?:VOCABULARY|PATTERN):[^\s]+$/u)
      .nullable()
      .optional(),
  })
  .refine((value) => {
    const items = value.relatedVocabularyItems;
    const refs = value.relatedVocabularyItemRefs;
    if (
      (items === undefined && refs === undefined) ||
      (items === null && refs === null)
    )
      return true;
    if (
      !Array.isArray(items) ||
      !Array.isArray(refs) ||
      items.length !== refs.length ||
      value.relatedVocabulary.length !== refs.length ||
      (value.type !== "PATTERN" && items.length > 0) ||
      new Set(refs).size !== refs.length ||
      new Set(items.map((item) => item.itemRef)).size !== items.length ||
      new Set(
        items.map((item) =>
          JSON.stringify([
            item.sourceEvidence.startUtf16,
            item.sourceEvidence.endUtf16,
          ]),
        ),
      ).size !== items.length ||
      items.some((item) => item.unitId !== value.unitId)
    )
      return false;
    return refs.every((ref, index) =>
      items.some(
        (item) =>
          item.itemRef === ref &&
          item.display === value.relatedVocabulary[index],
      ),
    );
  })
  .refine(
    (value) =>
      value.type !== "PATTERN" ||
      value.sourceEvidence == null ||
      value.structureParts.length > 0,
  )
  .refine(
    (value) =>
      value.type !== "WORD" ||
      value.sourceEvidence == null ||
      (/^[\p{L}\p{M}\p{N}]+(?:['‘’ʼ＇-][\p{L}\p{M}\p{N}]+)*$/u.test(
        value.sourceEvidence.surfaceForm,
      ) &&
        value.display.trim().toLowerCase() ===
          value.sourceEvidence.surfaceForm.toLowerCase()),
  );
export const learningTargetExtractionResultSchema = z.object({
  targets: z.array(learningTargetSchema),
  learningScopes: z
    .array(z.union([basicVocabularySchema, purposeVocabularySchema]))
    .min(1)
    .refine((values) => new Set(values).size === values.length),
  queryableExpressions: z.array(
    z.object({
      expressionId: z.string().min(1).max(128),
      unitId: z.string().min(1).max(128),
      display: z.string().min(1),
      explanationZh: z.string().min(1).optional(),
    }),
  ),
});

export const knowledgeExampleGenerationRequestSchema = z.object({
  itemId: z.string().min(1).max(128),
  targetType: z.enum(["VOCABULARY", "PATTERN"]),
  display: z.string().min(1),
  meaningZh: z.string().min(1),
  sourceScene: z
    .array(
      z.object({
        textEn: z.string().min(1),
        textZh: z.string().min(1),
      }),
    )
    .min(1)
    .max(4),
  commandId: commandIdSchema,
});
export const knowledgeExampleGenerationResultSchema = z.object({
  itemId: z.string().min(1).max(128),
  phonetic: z.string().min(1).nullable(),
  pronunciationText: z.string().min(1),
  representativeScene: z.string().min(1),
  naturalRevalidation: z.boolean().nullable(),
  examples: z
    .array(
      z.object({
        textEn: z.string().min(1),
        textZh: z.string().min(1),
        pronunciationText: z.string().min(1),
      }),
    )
    .length(3),
});
export type KnowledgeExampleGenerationRequest = z.infer<
  typeof knowledgeExampleGenerationRequestSchema
>;
export type KnowledgeExampleGenerationResult = z.infer<
  typeof knowledgeExampleGenerationResultSchema
>;

const fixedExplanationSentenceSchema = z.object({
  sentenceId: z.string().min(1).max(128),
  textEn: z.string().min(1),
  textZh: z.string().min(1),
  isFocus: z.boolean(),
});
export const lockedExplanationTargetSchema = z
  .object({
    targetId: z
      .string()
      .max(128)
      .refine((value) => value.trim().length > 0),
    type: z.enum(["WORD", "FIXED_EXPRESSION", "PATTERN"]),
    display: z.string().refine((value) => value.trim().length > 0),
    explanationZh: z.string().refine((value) => value.trim().length > 0),
    structureParts: z.array(
      z.string().refine((value) => value.trim().length > 0),
    ),
    sourceEvidence: targetSourceEvidenceSchema.nullable(),
    relatedVocabulary: z.array(acceptedVocabularySchema).nullable(),
  })
  .strict()
  .refine(
    (target) =>
      target.type !== "PATTERN" ||
      target.sourceEvidence === null ||
      target.structureParts.length > 0,
  );
export const learningExplanationRequestSchema = z.object({
  chapter: z.object({
    chapterId: z.string().min(1).max(128),
    sentences: z.array(fixedExplanationSentenceSchema).min(1).max(4),
  }),
  focusSentenceId: z.string().min(1).max(128),
  round: z.number().int().min(1).max(3),
  existingBaseExplanationZh: z.string().min(1).optional(),
  lockedTargets: z.array(lockedExplanationTargetSchema).optional(),
  requestedExpression: z
    .object({
      expressionId: z.string().min(1).max(128),
      display: z.string().min(1),
      startUtf16: z
        .number()
        .int()
        .nonnegative()
        .max(Number.MAX_SAFE_INTEGER)
        .optional(),
      endUtf16: z
        .number()
        .int()
        .positive()
        .max(Number.MAX_SAFE_INTEGER)
        .optional(),
    })
    .refine(
      (value) =>
        (value.startUtf16 === undefined && value.endUtf16 === undefined) ||
        (value.startUtf16 !== undefined &&
          value.endUtf16 !== undefined &&
          value.endUtf16 > value.startUtf16),
    )
    .optional(),
  commandId: commandIdSchema,
});
export const learningExplanationResultSchema = z
  .discriminatedUnion("kind", [
    z.object({ kind: z.literal("SENTENCE"), explanationZh: z.string().min(1) }),
    z.object({
      kind: z.literal("EXPRESSION"),
      expressionId: z.string().min(1).max(128),
      explanationZh: z.string().min(1),
      phonetic: z.string().min(1).nullable(),
      contextualMeaningZh: z
        .string()
        .refine((value) => value.trim().length > 0)
        .nullable()
        .optional(),
      usageZh: z.string().trim().min(1).nullable().optional(),
      expressionHintZh: z.string().trim().min(1).nullable().optional(),
      naturalExample: z
        .object({
          textEn: z.string().trim().min(1),
          textZh: z.string().trim().min(1),
        })
        .nullable()
        .optional(),
    }),
  ])
  .refine(
    (result) =>
      result.kind !== "EXPRESSION" ||
      [
        result.contextualMeaningZh,
        result.usageZh,
        result.expressionHintZh,
      ].every((value) => value == null) ||
      (typeof result.contextualMeaningZh === "string" &&
        typeof result.usageZh === "string" &&
        result.expressionHintZh !== undefined),
    {
      message:
        "Expression details must be entirely absent/null or a complete valid group",
    },
  );

export const learningExplanationRecoveryQuerySchema = z
  .object({ kind: z.enum(["SENTENCE", "EXPRESSION"]) })
  .strict();
export const learningExplanationRecoveryCommandIdSchema =
  commandIdSchema.refine((value) => value.trim().length > 0);
export const learningExplanationRecoveryResultSchema = z.discriminatedUnion(
  "status",
  [
    z
      .object({
        status: z.literal("COMPLETED"),
        commandId: learningExplanationRecoveryCommandIdSchema,
        provenance: z.literal("EXISTING_DELIVERY"),
        result: learningExplanationResultSchema.refine(
          (value) => value.explanationZh.trim().length > 0,
        ),
      })
      .strict(),
    z
      .object({
        status: z.enum([
          "NO_DELIVERY",
          "NO_DELIVERY_WITH_ATTEMPT",
          "LEGACY_UNAVAILABLE",
          "PROCESSING",
          "FAILED",
          "EXPIRED",
          "INVALID_DELIVERY",
          "BILLING_UNVERIFIED",
          "PENDING_SETTLEMENT",
          "RELEASED",
          "RECONCILIATION_REQUIRED",
          "INVALID_RESULT",
        ]),
      })
      .strict(),
  ],
);

const recordingAudioBase64Schema = z
  .string()
  .min(1)
  .max(8_000_000)
  .regex(/^[A-Za-z0-9+/]+={0,2}$/);
export const recordingTranscriptionRequestSchema = z.object({
  recordingId: z.string().min(1).max(128),
  durationSeconds: z.number().int().min(1).max(120),
  audioFormat: z.enum(["audio/mp4", "audio/wav"]),
  languageHint: z.enum(["zh-CN", "auto"]),
  endReason: z.enum(["USER_STOPPED", "MAX_DURATION", "CAPTURE_WINDOW"]),
  coreStartMs: z.number().int().min(0).max(120000).optional(),
  coreEndMs: z.number().int().min(1).max(120000).optional(),
  audioBase64: recordingAudioBase64Schema,
  commandId: commandIdSchema,
}).refine(request => request.endReason === "CAPTURE_WINDOW"
  ? request.audioFormat === "audio/wav" && request.languageHint === "auto" &&
    request.coreStartMs !== undefined && request.coreEndMs !== undefined &&
    request.coreStartMs < request.coreEndMs && request.coreEndMs <= request.durationSeconds * 1000
  : request.audioFormat === "audio/mp4" && request.languageHint === "zh-CN" &&
    request.coreStartMs === undefined && request.coreEndMs === undefined,
{ message: "Invalid recording window or source format" });
const recordingTranscriptSegmentSchema = z
  .object({
    segmentId: z.string().min(1).max(128),
    startMs: z.number().int().nonnegative(),
    endMs: z.number().int().nonnegative(),
    text: z.string().min(1),
    speakerLabel: z.enum(["USER", "OTHER", "UNKNOWN"]),
    confidence: z.number().min(0).max(1).nullable(),
    needsConfirmation: z.boolean().optional(),
    alternatives: z.array(z.string()),
    mostLikelyText: z.string().nullable(),
    mostLikelyIsInference: z.boolean(),
  })
  .refine((segment) => {
    if (segment.needsConfirmation === undefined) return true;
    return (
      segment.alternatives.every((text) => Boolean(text.trim())) &&
      new Set(segment.alternatives.map((text) => text.trim())).size ===
        segment.alternatives.length &&
      (segment.mostLikelyText === null
        ? !segment.mostLikelyIsInference
        : Boolean(segment.mostLikelyText.trim()) &&
          segment.mostLikelyIsInference) &&
      (segment.needsConfirmation ||
        (segment.alternatives.length === 0 && segment.mostLikelyText === null))
    );
  }, "Contradictory recording confirmation evidence");
export const recordingTranscriptionResultSchema = z.object({
  transcriptId: z.string().min(1).max(128),
  language: z.string().min(1).max(32),
  fullText: z.string().min(1),
  segments: z.array(recordingTranscriptSegmentSchema),
  finalBoundary: z.object({
    acceptedEndMs: z.number().int().nonnegative(),
    droppedIncompleteTail: z.boolean(),
  }),
});
export const transcriptRepairRequestSchema = z.object({
  transcriptId: z.string().min(1).max(128),
  currentText: z.string().min(1),
  manuallyEdited: z.boolean(),
  audioFormat: z.literal("audio/mp4"),
  audioBase64: recordingAudioBase64Schema,
  commandId: commandIdSchema,
  evidence: z.object({
    durationSeconds: z.number().int().min(1).max(120),
    endReason: z.enum(["USER_STOPPED", "MAX_DURATION"]),
    acceptedEndMs: z.number().int().min(1).max(120000),
    segments: z
      .array(
        z.object({
          segmentId: z.string().min(1).max(128),
          startMs: z.number().int().nonnegative(),
          endMs: z.number().int().min(1),
          text: z.string().min(1),
        }),
      )
      .min(1)
      .max(4000),
  }),
});
export const transcriptRepairResultSchema = z.discriminatedUnion("decision", [
  z.object({ decision: z.literal("NO_CHANGE") }),
  z.object({
    decision: z.literal("PROPOSE_CHANGES"),
    repairedText: z.string().min(1),
    changes: z
      .array(
        z.object({
          originalText: z.string().min(1),
          proposedText: z.string().min(1),
          evidenceSegmentIds: z.array(z.string().min(1).max(128)),
          reason: z.enum(["CONFIRMED_OMISSION", "CONFIRMED_DISFLUENCY"]),
        }),
      )
      .min(1),
  }),
]);

const sceneStartSchema = z.discriminatedUnion("kind", [
  z.object({
    kind: z.literal("RECOMMENDATION"),
    recommendationId: z.string().min(1),
    shortTopicZh: z.string().min(1),
    whyRelevantZh: z.string().min(1),
    sourceRefs: z.array(z.string().min(1)),
  }),
  z.object({
    kind: z.literal("TEMPLATE"),
    templateId: z.string().min(1),
    titleZh: z.string().min(1),
    scopeZh: z.string().min(1),
  }),
  z.object({
    kind: z.literal("DIRECT_DESCRIPTION"),
    description: z.string().min(1),
  }),
]);
const sceneAnswerSchema = z.object({
  questionId: z.string().min(1).max(128),
  questionZh: z.string().min(1).optional(),
  answerZh: z.string().min(1),
});
const sceneUnitSchema = z.object({
  unitId: z.string().min(1).max(128),
  speaker: z.enum(["USER", "OTHER"]).nullable(),
  textZh: z.string().min(1),
  factReferences: z.array(z.string()),
});
const sceneSessionSchema = z.object({
  operationId: z.string().min(1).max(128),
  start: sceneStartSchema,
  contentType: z.enum(["DIALOGUE", "ARTICLE"]),
  answers: z.array(sceneAnswerSchema).min(1).max(10),
  collectedFacts: z.array(z.string()),
  missingNonBlockingFacts: z.array(z.string()),
});
export const sceneRecommendationRequestSchema = z.object({
  sources: z
    .array(
      z.object({
        sourceId: z.string().min(1).max(128),
        sourceType: z.enum(["DIARY", "QUICK_RECORDING", "AI_SCENE"]),
        completedLocalDate: z.iso.date(),
        topicSummaryZh: z.string().min(1),
        confirmedTextZh: z.string().min(1),
        sceneContextZh: z.string(),
      }),
    )
    .max(12),
  commandId: commandIdSchema,
});
export const sceneRecommendationResultSchema = z.object({
  recommendations: z
    .array(
      z.object({
        recommendationId: z.string().min(1).max(128),
        shortTopicZh: z.string().min(1),
        whyRelevantZh: z.string().min(1),
        sourceRefs: z.array(z.string().min(1)),
        differentiationKey: z.string().min(1),
      }),
    )
    .max(4),
});
export const sceneNextQuestionRequestSchema = z.object({
  operationId: z.string().min(1).max(128),
  start: sceneStartSchema,
  contentType: z.enum(["DIALOGUE", "ARTICLE"]),
  confirmedAnswers: z.array(sceneAnswerSchema).max(10),
  remainingQuestions: z.number().int().min(0).max(10),
  remainingContentBudget: z.number().int().min(3).max(10),
  stepIndex: z.number().int().min(0).max(10),
  commandId: commandIdSchema,
});
export const sceneNextQuestionResultSchema = z.discriminatedUnion("action", [
  z.object({
    action: z.literal("ASK"),
    questionId: z.string().min(1).max(128),
    questionZh: z.string().min(1),
    options: z.array(z.string()),
  }),
  z.object({
    action: z.literal("GENERATE"),
    collectedFacts: z.array(z.string()),
    missingNonBlockingFacts: z.array(z.string()),
  }),
  z.object({ action: z.literal("INSUFFICIENT_INFORMATION") }),
]);
export const sceneDraftRequestSchema = z.object({
  task: z.enum(["SCENE_DIALOGUE_TURN_V1", "SCENE_ARTICLE_DRAFT_V1"]),
  session: sceneSessionSchema,
  remainingContentBudget: z.number().int().min(3).max(10),
  commandId: commandIdSchema,
  previousUnits: z.array(sceneUnitSchema).min(1).max(10).optional(),
});
export const sceneDraftResultSchema = z.object({
  draftId: z.string().min(1).max(128),
  contentType: z.enum(["DIALOGUE", "ARTICLE"]),
  units: z.array(sceneUnitSchema).min(3).max(10),
});
export const sceneEditReviewRequestSchema = z.object({
  contentType: z.enum(["DIALOGUE", "ARTICLE"]),
  changedUnitIds: z.array(z.string().min(1)).min(1),
  originalUnits: z.array(sceneUnitSchema).min(1).max(10),
  editedUnits: z.array(sceneUnitSchema).min(1).max(10),
  commandId: commandIdSchema,
});
export const sceneEditReviewResultSchema = z.discriminatedUnion("decision", [
  z.object({ decision: z.literal("PASS") }),
  z.object({
    decision: z.literal("NEEDS_USER_EDIT"),
    issues: z
      .array(
        z.object({
          unitId: z.string().min(1).max(128),
          startOffset: z.number().int().nonnegative(),
          endOffset: z.number().int().positive(),
          reason: z.enum([
            "REQUIRED_INFORMATION_MISSING",
            "CONTRADICTION",
            "UNINTELLIGIBLE",
            "SEVERELY_UNNATURAL",
          ]),
          explanationZh: z.string().min(1),
        }),
      )
      .min(1),
  }),
]);

export const readingFeedbackRequestSchema = z.object({
  chapterId: z.string().min(1).max(128),
  sentenceId: z.string().min(1).max(128),
  targetTextEn: z.string().min(1).max(1_000),
  completionSignal: z.enum([
    "TOUCH_REACHED_END",
    "RECOGNITION_COMPLETE",
    "MANUAL_CONFIRMATION",
  ]),
  audioMimeType: z.literal("audio/mp4"),
  audioBase64: z
    .string()
    .min(1)
    .max(900_000)
    .regex(/^[A-Za-z0-9+/]+={0,2}$/),
  commandId: commandIdSchema,
});

export const readingFeedbackResultSchema = z.object({
  feedbackZh: z.string().min(1),
  feedbackItems: z
    .array(
      z.object({
        aspect: z.enum(["WORD_OR_PHRASE", "RHYTHM", "COMPLETENESS"]),
        targetText: z.string().min(1).nullable(),
        observationZh: z.string().min(1),
        suggestionZh: z.string().min(1),
        evidence: z.enum(["AUDIO", "TRANSCRIPT", "GENERAL"]),
      }),
    )
    .length(3),
  transcript: z.string(),
  contentComplete: z.boolean(),
  feedbackConfidence: z.number().min(0).max(1),
  action: z.enum(["CONTINUE", "REPEAT_FOR_CLARITY"]),
});

const speakingAudioSchema = z.object({
  audioMimeType: z.literal("audio/mp4"),
  audioBase64: z
    .string()
    .min(1)
    .max(900_000)
    .regex(/^[A-Za-z0-9+/]+={0,2}$/),
});

export const speakingTranscriptionRequestSchema = z.object({
  chapterId: z.string().min(1).max(128),
  sentenceId: z.string().min(1).max(128),
  ...speakingAudioSchema.shape,
  commandId: commandIdSchema,
});
export const speakingTranscriptionResultSchema = z.object({
  transcript: z.string(),
  lowConfidenceSegments: z.array(z.string()),
  confidence: z.number().min(0).max(1).nullable(),
});

export const diaryDictationRequestSchema = z.object({
  ...speakingAudioSchema.shape,
  commandId: commandIdSchema,
});
export const diaryDictationResultSchema = speakingTranscriptionResultSchema;

export const fullSentenceSpeakingEvaluationRequestSchema = z.object({
  chapterId: z.string().min(1).max(128),
  sentenceId: z.string().min(1).max(128),
  targetTextEn: z.string().min(1).max(1_000),
  confirmedTranscript: z.string().min(1).max(2_000),
  targetHidden: z.literal(true),
  answerAudioPlayed: z.boolean(),
  userTranscriptConfirmed: z.literal(true),
  ...speakingAudioSchema.shape,
  commandId: commandIdSchema,
});
export const fullSentenceSpeakingEvaluationResultSchema = z.object({
  decision: z.enum(["PASS", "FAIL", "INCONCLUSIVE"]),
  completenessScore: z.number().int().min(0).max(100),
  coreMeaningRecognizable: z.boolean(),
  pronunciationIntelligible: z.boolean(),
  feedbackZh: z.string().min(1),
  pronunciationConfidence: z.number().min(0).max(1),
});

const patternPracticeExampleSchema = z.object({
  exampleId: z.string().min(1).max(128),
  sceneZh: z.string().min(1),
  aiPromptEn: z.string().min(1),
  targetResponseEn: z.string().min(1),
  targetResponseZh: z.string().min(1),
});
export const patternPracticeGenerationRequestSchema = z.object({
  chapterId: z.string().min(1).max(128),
  sourceSentenceId: z.string().min(1).max(128),
  sourceTextEn: z.string().min(1).max(1_000),
  sourceTextZh: z.string().min(1).max(1_000),
  patternId: z.string().min(1).max(128),
  structure: z.string().min(1).max(1_000),
  explanationZh: z.string().min(1).max(1_000),
  commandId: commandIdSchema,
});
export const patternPracticeGenerationResultSchema = z.discriminatedUnion(
  "status",
  [
    z.object({
      status: z.literal("READY"),
      patternId: z.string().min(1).max(128),
      examples: z.array(patternPracticeExampleSchema).min(1).max(5),
    }),
    z.object({ status: z.literal("NOT_NATURALLY_VERIFIABLE") }),
  ],
);
export const patternPracticeFeedbackRequestSchema = z.object({
  chapterId: z.string().min(1).max(128),
  patternId: z.string().min(1).max(128),
  exampleId: z.string().min(1).max(128),
  mode: z.enum(["READING", "RESPONSE"]),
  targetTextEn: z.string().min(1).max(1_000),
  targetStructure: z.string().min(1).max(1_000),
  targetVisible: z.literal(true),
  audioMimeType: z.literal("audio/mp4"),
  audioBase64: z
    .string()
    .min(1)
    .max(900_000)
    .regex(/^[A-Za-z0-9+/]+={0,2}$/),
  commandId: commandIdSchema,
});
export const patternPracticeFeedbackResultSchema = z.discriminatedUnion(
  "status",
  [
    z.object({
      status: z.literal("READY"),
      transcript: z.string(),
      feedbackZh: z.string().min(1),
      repeatRecommended: z.boolean(),
      confidence: z.number().min(0).max(1),
    }),
    z.object({ status: z.literal("INCONCLUSIVE") }),
  ],
);

export const patternIndependentEvaluationRequestSchema = z.object({
  chapterId: z.string().min(1).max(128),
  patternId: z.string().min(1).max(128),
  exampleId: z.string().min(1).max(128),
  targetResponseEn: z.string().min(1).max(1_000),
  targetStructure: z.string().min(1).max(1_000),
  targetHidden: z.literal(true),
  answerAudioPlayed: z.literal(false),
  helpUsed: z.literal(false),
  audioMimeType: z.literal("audio/mp4"),
  audioBase64: z
    .string()
    .min(1)
    .max(900_000)
    .regex(/^[A-Za-z0-9+/]+={0,2}$/),
  commandId: commandIdSchema,
});
export const patternIndependentEvaluationResultSchema = z.object({
  decision: z.enum(["PASS", "FAIL", "INCONCLUSIVE"]),
  structurePreserved: z.boolean(),
  contentSuitable: z.boolean(),
  pronunciationIntelligible: z.boolean(),
  feedbackZh: z.string().min(1),
  confidence: z.number().min(0).max(1),
});

const sceneResponseContextLineSchema = z.object({
  speaker: z.string().min(1).max(128),
  textEn: z.string().min(1).max(1_000),
});
const sceneResponseChapterSentenceSchema = z.object({
  sentenceId: z.string().min(1).max(128),
  textEn: z.string().min(1).max(1_000),
  textZh: z.string().min(1).max(1_000),
  isFocus: z.boolean(),
});
export const sceneResponseGenerationRequestSchema = z.object({
  chapterId: z
    .string()
    .min(1)
    .max(128)
    .refine((value) => value.trim().length > 0),
  focusSentenceId: z
    .string()
    .min(1)
    .max(128)
    .refine((value) => value.trim().length > 0),
  chapterSentences: z.array(sceneResponseChapterSentenceSchema).min(1).max(4),
  commandId: commandIdSchema.refine((value) => value.trim().length > 0),
});
export const sceneResponseGenerationResultSchema = z.object({
  // New tasks use application-owned scoped identities. Previously fixed opaque
  // task IDs remain valid for restoring and practising already saved chapters.
  taskId: z.string().min(1).max(128),
  sentenceId: z.string().min(1).max(128),
  backgroundEn: z.string().min(1).max(1_000).nullable(),
  contextLines: z.array(sceneResponseContextLineSchema).max(3),
  targetSentenceEn: z.string().min(1).max(1_000),
});
const sceneResponseEvidenceSchema = z.object({
  chapterId: z.string().min(1).max(128),
  taskId: z.string().min(1).max(128),
  sentenceId: z.string().min(1).max(128),
  targetTextEn: z.string().min(1).max(1_000),
  contextLines: z.array(sceneResponseContextLineSchema).max(3),
  confirmedTranscript: z.string().min(1).max(2_000),
  userTranscriptConfirmed: z.literal(true),
  ...speakingAudioSchema.shape,
  commandId: commandIdSchema,
});
export const sceneResponseTrainingFeedbackRequestSchema =
  sceneResponseEvidenceSchema.extend({
    roundIndex: z.union([z.literal(0), z.literal(1)]),
  });
export const sceneResponseTrainingFeedbackResultSchema = z.discriminatedUnion(
  "status",
  [
    z.object({
      status: z.literal("READY"),
      feedbackZh: z.string().min(1),
      pronunciationConfidence: z.number().min(0).max(1),
    }),
    z.object({ status: z.literal("INCONCLUSIVE") }),
  ],
);
export const sceneResponseEvaluationRequestSchema =
  sceneResponseEvidenceSchema.extend({
    roundIndex: z.literal(2),
    targetHidden: z.literal(true),
  });
export const sceneResponseEvaluationResultSchema = z.object({
  decision: z.enum(["PASS", "FAIL", "INCONCLUSIVE"]),
  correctTurn: z.boolean(),
  targetSentenceComplete: z.boolean(),
  pronunciationIntelligible: z.boolean(),
  feedbackZh: z.string().min(1),
  pronunciationConfidence: z.number().min(0).max(1),
});

export const appMessageTargetSchema = z.object({
  route: z.literal("MANUAL_REVIEW_RESULT"),
  resourceId: z.string().min(1).max(128),
});
export const appMessageSchema = z.object({
  messageId: z.string().min(1).max(128),
  kind: z.enum(["MANUAL_REVIEW_RESULT", "SYSTEM"]),
  summaryZh: z.string().min(1).max(2_000),
  relatedContentLabelZh: z.string().min(1).max(500).nullable(),
  createdAt: z.iso.datetime({ offset: true }),
  read: z.boolean(),
  target: appMessageTargetSchema.nullable(),
});
export const appMessagesResponseSchema = z.object({
  messages: z.array(appMessageSchema).max(200),
});
export type AppMessagesResponse = z.infer<typeof appMessagesResponseSchema>;
export const appMessagesReadAllResponseSchema = z.object({
  updatedCount: z.number().int().nonnegative(),
});
export type AppMessagesReadAllResponse = z.infer<
  typeof appMessagesReadAllResponseSchema
>;
export const manualReviewResultSchema = z.object({
  outcome: z.enum(["APPROVED", "NOT_APPROVED"]),
  source: z.object({
    safetyCaseId: z.string().min(1).max(128),
    text: z.string().min(1).max(100_000),
    sourceType: z.enum(["DIARY", "RECORDING", "AI_SCENE"]),
    contentShape: z.enum(["ARTICLE", "DIALOGUE"]),
    userReasonZh: z.string().min(1).max(2_000),
  }),
});
export const accountProfileSchema = z.object({ email: z.email().max(320) });
export const accountDeletionCommandSchema = z.object({
  commandId: commandIdSchema,
});
export const accountDeletionResultSchema = z.object({
  status: z.literal("DELETED"),
});

const amountFenSchema = z.number().int().nonnegative().max(100_000_000);
export const billingChargeSchema = z
  .object({
    chargeId: z.string().min(1).max(128),
    amountFen: amountFenSchema,
    occurredAt: z.iso.datetime({ offset: true }),
    modelSource: z.string().min(1).max(128),
    callType: z.enum([
      "INITIAL_CALL",
      "AUTOMATIC_RETRY",
      "USER_RETRY",
      "PROVIDER_SWITCH",
    ]),
    operationLabelZh: z.string().min(1).max(200),
  })
  .strict();
export const billingSummarySchema = z
  .object({
    balanceAmountFen: amountFenSchema,
    charges: z.array(billingChargeSchema).max(500),
  })
  .strict();
export const rechargeConfigSchema = z.object({
  recommendedOptions: z
    .array(
      z.object({
        amountFen: amountFenSchema.positive(),
        visionZh: z.string().min(1).max(300),
      }),
    )
    .min(1)
    .max(10),
  minimumAmountFen: amountFenSchema.positive(),
  maximumAmountFen: amountFenSchema.positive(),
});
export const rechargeOrderCommandSchema = z.object({
  amountFen: amountFenSchema.positive(),
  paymentMethod: z.enum(["WECHAT_PAY", "ALIPAY"]),
  commandId: commandIdSchema,
});
export const rechargeOrderResultSchema = z.object({
  orderId: z.string().min(1).max(128),
  status: z.enum([
    "CREATED",
    "PENDING_PAYMENT",
    "IN_PROGRESS",
    "PAID",
    "FAILED",
    "CANCELLED",
  ]),
  paymentLaunch: z.string().min(1).max(4_096).nullable(),
});
export const refundPageSchema = z.object({
  refundableAmountFen: amountFenSchema,
  refundMethodLabelZh: z.string().min(1).max(200),
  requestId: z.string().min(1).max(128).nullable(),
  requestStatus: z
    .enum([
      "REQUESTED",
      "REVIEWING",
      "APPROVED",
      "REFUNDED",
      "REJECTED",
      "FAILED",
    ])
    .nullable(),
});
export const refundCommandSchema = z.object({
  amountFen: amountFenSchema.positive(),
  commandId: commandIdSchema,
});
export const refundResultSchema = z.object({
  requestId: z.string().min(1).max(128),
  status: z.enum([
    "REQUESTED",
    "REVIEWING",
    "APPROVED",
    "REFUNDED",
    "REJECTED",
    "FAILED",
  ]),
});
export const minorRechargeSupportCommandSchema = z.object({
  commandId: commandIdSchema,
});
export const minorRechargeSupportResultSchema = z.object({
  supportCaseId: z.string().min(1).max(128),
  status: z.enum(["REQUESTED", "REVIEWING", "RESOLVED", "REJECTED"]),
});
export const paymentEventEnvelopeSchema = z
  .object({
    payloadBase64: z
      .string()
      .min(1)
      .max(16_384)
      .regex(/^[A-Za-z0-9+/]+={0,2}$/),
    signature: z
      .string()
      .min(1)
      .max(512)
      .regex(/^[A-Za-z0-9+/]+={0,2}$/),
    timestamp: z.string().regex(/^\d{10}$/),
  })
  .strict();

export const bilingualPassagesRequestSchema = z.object({
  commandId: commandIdSchema,
  text: z.string().min(1).refine((value) => value.trim().length > 0),
  sourceType: z.enum(["DIARY", "RECORDING", "AI_SCENE", "SCREENSHOT", "AUDIO_IMPORT"]),
  contentShape: z.enum(["ARTICLE", "DIALOGUE"]),
});
export type BilingualPassagesRequest = z.infer<typeof bilingualPassagesRequestSchema>;

const nonblankPassageText = z.string().min(1).refine((value) => value.trim().length > 0);
export const bilingualPassagesResponseSchema = z.object({
  paragraphs: z.array(z.object({
    textZh: nonblankPassageText,
    textEn: nonblankPassageText,
    sentences: z.array(z.object({ textEn: nonblankPassageText, textZh: nonblankPassageText })).min(1),
  })).min(1),
});
