import { randomUUID } from "node:crypto";
import {
  KyselyAiResultDeliveryStore,
  purgeExpiredAiResults,
} from "@reallanguage/backend-modules/ai";
import { AiResultCleanup } from "./ai-result-cleanup.js";
import { Logger, Module, type Type } from "@nestjs/common";
import {
  AesGcmAccountSensitiveFieldsProtector,
  AesGcmPasswordResetDeliverySecretProtector,
  AesGcmRegistrationPayloadProtector,
  Argon2PasswordHasher,
  CompletePasswordReset,
  CreateSession,
  GetRegistrationVerificationStatus,
  HmacAccessTokenDigester,
  HmacDeviceIdentifierDigester,
  HmacEmailIndex,
  HmacPasswordResetTokenDigester,
  HmacPasswordResetTokenFactory,
  HmacSessionBearerDigester,
  HmacSessionSecretFactory,
  HmacVerificationSecretFactory,
  HmacVerificationSecretVerifier,
  KyselyAccessTokenAuthenticator,
  KyselyCredentialVerifier,
  KyselyEligibleRegistrationStarter,
  KyselyRegistrationChallengeReader,
  KyselyRegistrationVerificationEmailResender,
  KyselyPasswordResetStore,
  KyselySessionIssuer,
  KyselySessionLifecycleRepository,
  KyselyVerifiedAccountCreator,
  PasswordPolicy,
  RequestPasswordReset,
  RefreshSession,
  ResendRegistrationVerification,
  RevokeSession,
  StartRegistration,
  VerifyRegistration,
  VerifyPasswordReset,
  type IdentityRegistrationDatabase,
  type IdentityPasswordResetDatabase,
  type IdentitySessionDatabase,
  GetAccountProfile,
  KyselyAccountProfileReader,
  type AccountProfileDatabase,
  DeleteAccount,
  KyselyAccountDeletionStore,
  type AccountDeletionDatabase,
} from "@reallanguage/backend-modules/identity";
import {
  CompleteInitialSettings,
  GetCurrentSettings,
  KyselyInitialSettingsWriter,
  KyselyCurrentSettingsStore,
  ReadVersionedSettings,
  UpdateCurrentSettings,
  type SettingsDatabase,
} from "@reallanguage/backend-modules/settings";
import {
  AesGcmContentSafetyPayloadProtector,
  CheckContentSafety,
  HttpContentSafetyProvider,
  KyselyContentSafetyGateway,
  HandleBlockedSafety,
  HttpContentDeidentificationProvider,
  KyselySafetyHandlingStore,
  purgeExpiredSafetyPayloads,
  type ContentSafetyDatabase,
  type SafetyHandlingDatabase,
} from "@reallanguage/backend-modules/safety";
import type { Kysely } from "kysely";
import {
  EvaluateFullSentenceSpeaking,
  GenerateLearningExplanation,
  RecoverLearningExplanation,
  GenerateReadingFeedback,
  GenerateSpeakingTranscription,
  HttpSpeakingProvider,
  GeneratePatternPractice,
  GeneratePatternPracticeFeedback,
  EvaluatePatternIndependent,
  HttpPatternPracticeProvider,
  EvaluateSceneResponse,
  GenerateSceneResponse,
  GenerateSceneResponseFeedback,
  HttpSceneResponseProvider,
  HttpLearningExplanationProvider,
  HttpReadingFeedbackProvider,
} from "@reallanguage/backend-modules/learning";
import {
  GenerateEnglishCandidates,
  ExtractLearningTargets,
  HttpChapterContextProvider,
  HttpEnglishCandidateProvider,
  HttpLearningTargetProvider,
  HttpSemanticUnitSplitProvider,
  KyselyContentGenerationSettingsReader,
  RecoverLearningTargets,
  SelectChapterContext,
  SplitSemanticUnits,
  type ContentGenerationSettingsDatabase,
} from "@reallanguage/backend-modules/content-formation";
import {
  GenerateKnowledgeExamples,
  HttpKnowledgeExampleProvider,
} from "@reallanguage/backend-modules/knowledge";
import {
  HttpRecordingProvider,
  RepairTranscript,
  TranscribeRecording,
} from "@reallanguage/backend-modules/media";
import {
  GenerateSceneDraft,
  GenerateSceneRecommendations,
  GetNextSceneQuestion,
  HttpSceneFormationProvider,
  ReviewSceneEdit,
} from "@reallanguage/backend-modules/sources";
import {
  KyselyAppMessageReader,
  ListAppMessages,
  MarkAllAppMessagesRead,
  type MessagingDatabase,
} from "@reallanguage/backend-modules/messaging";
import {
  ApplyPaymentEvent,
  CreateRechargeOrder,
  GetBillingSummary,
  GetRechargeOrder,
  GetRefundSnapshot,
  HttpPaymentOrderProvider,
  HttpMeteredProviderGateway,
  HmacPaymentWebhookVerifier,
  KyselyAiChargeStore,
  KyselyExistingAiChargeReader,
  KyselyBillingCommandStore,
  KyselyBillingSummaryReader,
  MeteredProviderFetchAdapter,
  RequestRefund,
  RequestMinorRechargeSupport,
  RunMeteredProviderAttempt,
  type MeteredProviderGateway,
  type AiChargeDatabase,
  type BillingCommandDatabase,
  type BillingSummaryDatabase,
} from "@reallanguage/backend-modules/billing";
import {
  AdminCryptography,
  AuthenticateAdminSession,
  CreateAdminSession,
  DecideAdminReview,
  GetPendingAdminReview,
  KyselyAdminIdentityStore,
  KyselyAdminReviewStore,
  ListPendingAdminReviews,
  RevokeAdminSession,
  SetupInitialAdmin,
  TotpService,
  type AdminIdentityDatabase,
  type AdminReviewDatabase,
} from "@reallanguage/backend-modules/admin";
import {
  APPLY_PAYMENT_EVENT_APPLICATION,
  BILLING_SUMMARY_APPLICATION,
  BillingController,
  CREATE_RECHARGE_ORDER_APPLICATION,
  GET_RECHARGE_ORDER_APPLICATION,
  GET_REFUND_APPLICATION,
  REQUEST_REFUND_APPLICATION,
  REQUEST_MINOR_RECHARGE_SUPPORT_APPLICATION,
} from "../presentation/billing.controller.js";
import {
  APP_MESSAGES_APPLICATION,
  AppMessagesController,
  MARK_ALL_APP_MESSAGES_READ_APPLICATION,
} from "../presentation/app-messages.controller.js";
import {
  ACCOUNT_DELETION_APPLICATION,
  ACCOUNT_PROFILE_APPLICATION,
  AccountController,
} from "../presentation/account.controller.js";
import {
  ADMIN_BOOTSTRAP_ACCESS,
  ADMIN_ACCOUNT_EMAIL,
  ADMIN_SESSION_AUTHENTICATOR,
  AdminSessionController,
  CREATE_ADMIN_SESSION_APPLICATION,
  REVOKE_ADMIN_SESSION_APPLICATION,
  SETUP_INITIAL_ADMIN_APPLICATION,
} from "../presentation/admin-session.controller.js";
import {
  ADMIN_REVIEW_DECISION_APPLICATION,
  ADMIN_REVIEW_DETAILS_APPLICATION,
  ADMIN_REVIEW_LIST_APPLICATION,
  AdminReviewController,
} from "../presentation/admin-review.controller.js";

import { HealthController } from "../presentation/health.controller.js";
import {
  ChapterContextController,
  SELECT_CHAPTER_CONTEXT_APPLICATION,
} from "../presentation/chapter-context.controller.js";
import {
  EnglishCandidateController,
  GENERATE_ENGLISH_CANDIDATES_APPLICATION,
} from "../presentation/english-candidate.controller.js";
import {
  EXTRACT_LEARNING_TARGETS_APPLICATION,
  LearningTargetController,
} from "../presentation/learning-target.controller.js";
import {
  GENERATE_KNOWLEDGE_EXAMPLES_APPLICATION,
  KnowledgeExampleController,
} from "../presentation/knowledge-example.controller.js";
import {
  AGREEMENT_DOCUMENT_READER,
  AgreementDocumentController,
} from "../presentation/agreement-document.controller.js";
import {
  AUTHENTICATE_ACCESS_TOKEN,
  COMPLETE_INITIAL_SETTINGS_APPLICATION,
  GET_CURRENT_SETTINGS_APPLICATION,
  InitialSettingsController,
} from "../presentation/initial-settings.controller.js";
import {
  CurrentSettingsUpdateController,
  READ_VERSIONED_SETTINGS_APPLICATION,
  UPDATE_CURRENT_SETTINGS_APPLICATION,
} from "../presentation/current-settings-update.controller.js";
import {
  RegistrationController,
  GET_REGISTRATION_VERIFICATION_STATUS_APPLICATION,
  RESEND_REGISTRATION_VERIFICATION_APPLICATION,
  START_REGISTRATION_APPLICATION,
  VERIFY_REGISTRATION_APPLICATION,
} from "../presentation/registration.controller.js";
import {
  CREATE_SESSION_APPLICATION,
  REFRESH_SESSION_APPLICATION,
  REVOKE_SESSION_APPLICATION,
  SessionController,
} from "../presentation/session.controller.js";
import {
  COMPLETE_PASSWORD_RESET_APPLICATION,
  PasswordResetController,
  REQUEST_PASSWORD_RESET_APPLICATION,
  VERIFY_PASSWORD_RESET_APPLICATION,
} from "../presentation/password-reset.controller.js";
import {
  CHECK_CONTENT_SAFETY_APPLICATION,
  SafetyCheckController,
} from "../presentation/safety-check.controller.js";
import {
  HANDLE_BLOCKED_SAFETY_APPLICATION,
  SafetyHandlingController,
} from "../presentation/safety-handling.controller.js";
import {
  SemanticUnitSplitController,
  SPLIT_SEMANTIC_UNITS_APPLICATION,
} from "../presentation/semantic-unit-split.controller.js";
import type { ProductionConfig } from "./production-config.js";
import { FileAgreementDocumentReader } from "./file-agreement-document-reader.js";
import {
  GENERATE_LEARNING_EXPLANATION_APPLICATION,
  LearningExplanationController,
} from "../presentation/learning-explanation.controller.js";
import {
  LearningExplanationRecoveryController,
  RECOVER_LEARNING_EXPLANATION_APPLICATION,
} from "../presentation/learning-explanation-recovery.controller.js";
import {
  GENERATE_READING_FEEDBACK_APPLICATION,
  ReadingFeedbackController,
} from "../presentation/reading-feedback.controller.js";
import {
  EVALUATE_FULL_SENTENCE_SPEAKING_APPLICATION,
  FullSentenceSpeakingController,
  GENERATE_SPEAKING_TRANSCRIPTION_APPLICATION,
} from "../presentation/full-sentence-speaking.controller.js";
import {
  GENERATE_PATTERN_PRACTICE_FEEDBACK_APPLICATION,
  GENERATE_PATTERN_PRACTICE_APPLICATION,
  PatternPracticeController,
} from "../presentation/pattern-practice.controller.js";
import {
  EVALUATE_PATTERN_INDEPENDENT_APPLICATION,
  PatternIndependentController,
} from "../presentation/pattern-independent.controller.js";
import {
  EVALUATE_SCENE_RESPONSE_APPLICATION,
  GENERATE_SCENE_RESPONSE_APPLICATION,
  GENERATE_SCENE_RESPONSE_FEEDBACK_APPLICATION,
  SceneResponseController,
} from "../presentation/scene-response.controller.js";
import {
  RECORDING_TRANSCRIPTION_APPLICATION,
  RecordingTranscriptionController,
  TRANSCRIPT_REPAIR_APPLICATION,
} from "../presentation/recording-transcription.controller.js";
import {
  GENERATE_SCENE_DRAFT_APPLICATION,
  GENERATE_SCENE_RECOMMENDATIONS_APPLICATION,
  GET_NEXT_SCENE_QUESTION_APPLICATION,
  REVIEW_SCENE_EDIT_APPLICATION,
  SceneFormationController,
} from "../presentation/scene-formation.controller.js";

export type ProductionDatabase = IdentityRegistrationDatabase &
  IdentitySessionDatabase &
  IdentityPasswordResetDatabase &
  SettingsDatabase &
  ContentSafetyDatabase &
  SafetyHandlingDatabase &
  ContentGenerationSettingsDatabase &
  MessagingDatabase &
  AccountProfileDatabase &
  AccountDeletionDatabase &
  AdminIdentityDatabase &
  AdminReviewDatabase;

export const createProductionApiModule = (
  database: Kysely<ProductionDatabase>,
  config: ProductionConfig,
  providers: Readonly<{
    content?: MeteredProviderGateway;
    safety?: MeteredProviderGateway;
  }> = {},
): Type<unknown> => {
  const now = { now: () => new Date() };
  const learningTargetRecoveryLogger = new Logger("LearningTargetRecovery");
  const appMessages = new KyselyAppMessageReader(database, now);
  const identifiers = { next: () => randomUUID() };
  const passwordHasher = new Argon2PasswordHasher();
  const passwordPolicy = new PasswordPolicy({
    isBlocked: async (password) =>
      config.identity.passwordBlocklist.has(password),
  });
  const settings = new KyselyInitialSettingsWriter(database);
  const registrationPayloads = new AesGcmRegistrationPayloadProtector({
    activeEncryptionKey: config.identity.registrationEncryptionKey,
    decryptionKeys: [],
    emailIndexKey: config.identity.emailIndexKey,
    canonicalizeEmail,
  });
  const accountFields = new AesGcmAccountSensitiveFieldsProtector({
    activeEncryptionKey: config.identity.accountEncryptionKey,
    decryptionKeys: [],
  });
  const sessionSecrets = new HmacSessionSecretFactory(
    config.identity.sessionDigestKey,
  );
  const devices = new HmacDeviceIdentifierDigester(
    config.identity.deviceDigestKey,
  );

  const startRegistration = new StartRegistration(
    {
      current: () => {
        const current = new Date();
        return {
          year: current.getUTCFullYear(),
          month: current.getUTCMonth() + 1,
        };
      },
    },
    passwordPolicy,
    new KyselyEligibleRegistrationStarter(database, {
      clock: now,
      identifiers,
      passwordHasher,
      payloadProtector: registrationPayloads,
      verificationSecrets: new HmacVerificationSecretFactory(
        config.identity.verificationDigestKey,
      ),
    }),
  );
  const verifyRegistration = new VerifyRegistration(
    now,
    new KyselyRegistrationChallengeReader(database),
    new HmacVerificationSecretVerifier([config.identity.verificationDigestKey]),
    new KyselyVerifiedAccountCreator(database, {
      identifiers,
      payloadProtector: registrationPayloads,
      accountFields,
    }),
  );
  const getRegistrationVerificationStatus =
    new GetRegistrationVerificationStatus(
      now,
      new KyselyRegistrationChallengeReader(database),
    );
  const resendRegistration = new ResendRegistrationVerification(
    now,
    new KyselyRegistrationVerificationEmailResender(database, identifiers),
    config.identity.verificationEmailCooldownSeconds,
  );

  const credentials = new KyselyCredentialVerifier(database, {
    emailIndex: new HmacEmailIndex(
      config.identity.emailIndexKey,
      canonicalizeEmail,
    ),
    passwordHasher,
    settings,
    dummyPasswordHash: config.identity.dummyPasswordHash,
  });
  const createSession = new CreateSession(
    credentials,
    new KyselySessionIssuer(database, {
      clock: now,
      identifiers,
      secrets: sessionSecrets,
      devices,
    }),
  );
  const sessionLifecycle = new KyselySessionLifecycleRepository(database, {
    clock: now,
    identifiers,
    bearerDigester: new HmacSessionBearerDigester(
      config.identity.sessionDigestKey,
    ),
    devices,
    secrets: sessionSecrets,
    settings,
  });
  const accessTokens = new KyselyAccessTokenAuthenticator(
    database,
    new HmacAccessTokenDigester(config.identity.sessionDigestKey),
    now,
  );
  const passwordResetStore = new KyselyPasswordResetStore(database, {
    clock: now,
    identifiers,
    emailIndex: new HmacEmailIndex(
      config.identity.emailIndexKey,
      canonicalizeEmail,
    ),
    verificationSecrets: new HmacVerificationSecretFactory(
      config.identity.verificationDigestKey,
    ),
    deliverySecrets: new AesGcmPasswordResetDeliverySecretProtector({
      activeEncryptionKey: config.identity.accountEncryptionKey,
      decryptionKeys: [],
    }),
    verificationSecretVerifier: new HmacVerificationSecretVerifier([
      config.identity.verificationDigestKey,
    ]),
    resetTokens: new HmacPasswordResetTokenFactory(
      config.identity.verificationDigestKey,
    ),
    resetTokenDigester: new HmacPasswordResetTokenDigester(
      config.identity.verificationDigestKey,
    ),
    passwordHasher,
    settings,
  });
  const aiCharges = new KyselyAiChargeStore(
    database as Kysely<AiChargeDatabase>,
    identifiers,
    now,
  );
  const contentMeteredFetch = new MeteredProviderFetchAdapter(
    new RunMeteredProviderAttempt(
      aiCharges,
      providers.content ??
        new HttpMeteredProviderGateway({
          endpoint: config.contentFormation.providerEndpoint,
          apiKey: config.contentFormation.providerApiKey,
        }),
      new KyselyAiResultDeliveryStore(
        database as Kysely<unknown>,
        config.safety.payloadEncryptionKey,
        now,
      ),
    ),
    aiOperationLabelsZh,
  ).fetch;
  const safetyMeteredFetch = new MeteredProviderFetchAdapter(
    new RunMeteredProviderAttempt(
      aiCharges,
      providers.safety ??
        new HttpMeteredProviderGateway({
          endpoint: config.safety.providerEndpoint,
          apiKey: config.safety.providerApiKey,
        }),
      new KyselyAiResultDeliveryStore(
        database as Kysely<unknown>,
        config.safety.payloadEncryptionKey,
        now,
      ),
    ),
    aiOperationLabelsZh,
  ).fetch;
  const safetyCipher = new AesGcmContentSafetyPayloadProtector(
    config.safety.payloadEncryptionKey,
  );
  const contentSafety = new CheckContentSafety(
    new KyselyContentSafetyGateway(database, {
      clock: now,
      identifiers,
      payloadProtector: safetyCipher,
      provider: new HttpContentSafetyProvider(
        {
          endpoint: config.safety.providerEndpoint,
          apiKey: config.safety.providerApiKey,
        },
        safetyMeteredFetch,
      ),
      payloadTtlMilliseconds: config.safety.payloadTtlMilliseconds,
    }),
  );
  const blockedSafety = new HandleBlockedSafety(
    new KyselySafetyHandlingStore(database, {
      cipher: safetyCipher,
      identifiers,
      clock: now,
      candidateTtlMilliseconds: config.safety.payloadTtlMilliseconds,
    }),
    new HttpContentDeidentificationProvider(
      {
        endpoint: config.safety.providerEndpoint,
        apiKey: config.safety.providerApiKey,
      },
      safetyMeteredFetch,
    ),
  );
  const billingCommands = new KyselyBillingCommandStore(
    database as Kysely<BillingCommandDatabase>,
    identifiers,
  );
  const adminCryptography = new AdminCryptography({
    encryptionKey: config.admin.encryptionKey,
    digestKey: config.admin.digestKey,
  });
  const adminTotp = new TotpService();
  const adminStore = new KyselyAdminIdentityStore(
    database as Kysely<AdminIdentityDatabase>,
    { cryptography: adminCryptography, identifiers },
  );
  const setupInitialAdmin = new SetupInitialAdmin(adminStore, {
    canonicalEmail: config.admin.canonicalEmail,
    passwords: {
      assess: async (password) =>
        Array.from(password.normalize("NFC")).length >= 12 &&
        Array.from(password.normalize("NFC")).length <= 128 &&
        !config.identity.passwordBlocklist.has(password.normalize("NFC")),
      hash: (password) => passwordHasher.hash(password),
    },
    totp: adminTotp,
    clock: now,
  });
  const createAdminSession = new CreateAdminSession(adminStore, {
    passwords: passwordHasher,
    dummyPasswordHash: config.identity.dummyPasswordHash,
    totp: adminTotp,
    sessions: { create: () => adminCryptography.createSession() },
    clock: now,
    sessionTtlMilliseconds: 15 * 60 * 1_000,
  });
  const adminSessionDependencies = {
    sessions: adminCryptography,
    clock: now,
  };
  const adminReviewStore = new KyselyAdminReviewStore(
    database as Kysely<AdminReviewDatabase>,
    { cipher: safetyCipher, identifiers, clock: now },
  );
  const resultCleanup = new AiResultCleanup(
    async () => {
      // Await both jobs even if one fails so shutdown and overlap protection cover
      // the whole cleanup cycle. Never log database errors containing payloads.
      const results = await Promise.allSettled([
        purgeExpiredAiResults(database as Kysely<unknown>),
        purgeExpiredSafetyPayloads(database as Kysely<unknown>),
      ]);
      if (results.some((result) => result.status === "rejected")) {
        throw new Error("TEMPORARY_PAYLOAD_CLEANUP_FAILED");
      }
    },
    () => console.warn("TEMPORARY_PAYLOAD_CLEANUP_FAILED"),
  );
  const databaseLifecycle = {
    onModuleInit: () => resultCleanup.onModuleInit(),
    onApplicationShutdown: async () => {
      await resultCleanup.onModuleDestroy();
      await database.destroy();
    },
  };

  @Module({
    controllers: [
      HealthController,
      AgreementDocumentController,
      RegistrationController,
      SessionController,
      PasswordResetController,
      SafetyCheckController,
      SafetyHandlingController,
      SemanticUnitSplitController,
      ChapterContextController,
      EnglishCandidateController,
      LearningTargetController,
      KnowledgeExampleController,
      LearningExplanationController,
      LearningExplanationRecoveryController,
      ReadingFeedbackController,
      FullSentenceSpeakingController,
      PatternPracticeController,
      PatternIndependentController,
      SceneResponseController,
      RecordingTranscriptionController,
      SceneFormationController,
      AppMessagesController,
      AccountController,
      BillingController,
      InitialSettingsController,
      CurrentSettingsUpdateController,
      AdminSessionController,
      AdminReviewController,
    ],
    providers: [
      { provide: START_REGISTRATION_APPLICATION, useValue: startRegistration },
      {
        provide: SETUP_INITIAL_ADMIN_APPLICATION,
        useValue: setupInitialAdmin,
      },
      {
        provide: CREATE_ADMIN_SESSION_APPLICATION,
        useValue: createAdminSession,
      },
      {
        provide: ADMIN_SESSION_AUTHENTICATOR,
        useValue: new AuthenticateAdminSession(
          adminStore,
          adminSessionDependencies,
        ),
      },
      {
        provide: REVOKE_ADMIN_SESSION_APPLICATION,
        useValue: new RevokeAdminSession(adminStore, adminSessionDependencies),
      },
      {
        provide: ADMIN_BOOTSTRAP_ACCESS,
        useValue: {
          allows: (ip: string) =>
            config.admin.allowLoopbackBootstrap && isLoopback(ip),
        },
      },
      {
        provide: ADMIN_ACCOUNT_EMAIL,
        useValue: config.admin.canonicalEmail,
      },
      {
        provide: ADMIN_REVIEW_LIST_APPLICATION,
        useValue: new ListPendingAdminReviews(adminReviewStore),
      },
      {
        provide: ADMIN_REVIEW_DETAILS_APPLICATION,
        useValue: new GetPendingAdminReview(adminReviewStore),
      },
      {
        provide: ADMIN_REVIEW_DECISION_APPLICATION,
        useValue: new DecideAdminReview(adminReviewStore),
      },
      {
        provide: AGREEMENT_DOCUMENT_READER,
        useValue: new FileAgreementDocumentReader({
          USER_AGREEMENT: config.legalDocuments.userAgreementPath,
          PRIVACY_POLICY: config.legalDocuments.privacyPolicyPath,
        }),
      },
      {
        provide: VERIFY_REGISTRATION_APPLICATION,
        useValue: verifyRegistration,
      },
      {
        provide: GET_REGISTRATION_VERIFICATION_STATUS_APPLICATION,
        useValue: getRegistrationVerificationStatus,
      },
      {
        provide: RESEND_REGISTRATION_VERIFICATION_APPLICATION,
        useValue: resendRegistration,
      },
      { provide: CREATE_SESSION_APPLICATION, useValue: createSession },
      {
        provide: REQUEST_PASSWORD_RESET_APPLICATION,
        useValue: new RequestPasswordReset(passwordResetStore),
      },
      {
        provide: VERIFY_PASSWORD_RESET_APPLICATION,
        useValue: new VerifyPasswordReset(passwordResetStore),
      },
      {
        provide: COMPLETE_PASSWORD_RESET_APPLICATION,
        useValue: new CompletePasswordReset(passwordPolicy, passwordResetStore),
      },
      {
        provide: CHECK_CONTENT_SAFETY_APPLICATION,
        useValue: contentSafety,
      },
      {
        provide: HANDLE_BLOCKED_SAFETY_APPLICATION,
        useValue: blockedSafety,
      },
      {
        provide: SPLIT_SEMANTIC_UNITS_APPLICATION,
        useValue: new SplitSemanticUnits(
          new HttpSemanticUnitSplitProvider(
            {
              endpoint: config.contentFormation.providerEndpoint,
              apiKey: config.contentFormation.providerApiKey,
            },
            contentMeteredFetch,
          ),
        ),
      },
      {
        provide: SELECT_CHAPTER_CONTEXT_APPLICATION,
        useValue: new SelectChapterContext(
          new HttpChapterContextProvider(
            {
              endpoint: config.contentFormation.providerEndpoint,
              apiKey: config.contentFormation.providerApiKey,
            },
            contentMeteredFetch,
          ),
        ),
      },
      {
        provide: GENERATE_ENGLISH_CANDIDATES_APPLICATION,
        useValue: new GenerateEnglishCandidates(
          new KyselyContentGenerationSettingsReader(database),
          new HttpEnglishCandidateProvider(
            {
              endpoint: config.contentFormation.providerEndpoint,
              apiKey: config.contentFormation.providerApiKey,
            },
            contentMeteredFetch,
          ),
        ),
      },
      {
        provide: EXTRACT_LEARNING_TARGETS_APPLICATION,
        useValue: (() => {
          const generationSettings =
            new KyselyContentGenerationSettingsReader(database);
          const strict = new ExtractLearningTargets(
            generationSettings,
            new HttpLearningTargetProvider(
              {
                endpoint: config.contentFormation.providerEndpoint,
                apiKey: config.contentFormation.providerApiKey,
              },
              contentMeteredFetch,
            ),
          );
          return new RecoverLearningTargets(
            strict,
            generationSettings,
            (event) =>
              learningTargetRecoveryLogger.warn(
                `${event.phase} ${event.errorName}: ${event.errorMessage}`,
              ),
          );
        })(),
      },
      {
        provide: GENERATE_LEARNING_EXPLANATION_APPLICATION,
        useValue: new GenerateLearningExplanation(
          new KyselyContentGenerationSettingsReader(database),
          new HttpLearningExplanationProvider(
            {
              endpoint: config.contentFormation.providerEndpoint,
              apiKey: config.contentFormation.providerApiKey,
            },
            contentMeteredFetch,
          ),
        ),
      },
      {
        provide: RECOVER_LEARNING_EXPLANATION_APPLICATION,
        useValue: new RecoverLearningExplanation(
          new KyselyAiResultDeliveryStore(
            database as Kysely<unknown>,
            config.safety.payloadEncryptionKey,
            now,
          ),
          new KyselyExistingAiChargeReader(database as Kysely<unknown>),
        ),
      },
      {
        provide: GENERATE_READING_FEEDBACK_APPLICATION,
        useValue: new GenerateReadingFeedback(
          new HttpReadingFeedbackProvider(
            {
              endpoint: config.contentFormation.providerEndpoint,
              apiKey: config.contentFormation.providerApiKey,
            },
            contentMeteredFetch,
          ),
        ),
      },
      {
        provide: GENERATE_SPEAKING_TRANSCRIPTION_APPLICATION,
        useValue: new GenerateSpeakingTranscription(
          new HttpSpeakingProvider(
            {
              endpoint: config.contentFormation.providerEndpoint,
              apiKey: config.contentFormation.providerApiKey,
            },
            contentMeteredFetch,
          ),
        ),
      },
      {
        provide: EVALUATE_FULL_SENTENCE_SPEAKING_APPLICATION,
        useValue: new EvaluateFullSentenceSpeaking(
          new HttpSpeakingProvider(
            {
              endpoint: config.contentFormation.providerEndpoint,
              apiKey: config.contentFormation.providerApiKey,
            },
            contentMeteredFetch,
          ),
        ),
      },
      {
        provide: GENERATE_PATTERN_PRACTICE_APPLICATION,
        useValue: new GeneratePatternPractice(
          new HttpPatternPracticeProvider(
            {
              endpoint: config.contentFormation.providerEndpoint,
              apiKey: config.contentFormation.providerApiKey,
            },
            contentMeteredFetch,
          ),
        ),
      },
      {
        provide: GENERATE_PATTERN_PRACTICE_FEEDBACK_APPLICATION,
        useValue: new GeneratePatternPracticeFeedback(
          new HttpPatternPracticeProvider(
            {
              endpoint: config.contentFormation.providerEndpoint,
              apiKey: config.contentFormation.providerApiKey,
            },
            contentMeteredFetch,
          ),
        ),
      },
      {
        provide: EVALUATE_PATTERN_INDEPENDENT_APPLICATION,
        useValue: new EvaluatePatternIndependent(
          new HttpPatternPracticeProvider(
            {
              endpoint: config.contentFormation.providerEndpoint,
              apiKey: config.contentFormation.providerApiKey,
            },
            contentMeteredFetch,
          ),
        ),
      },
      {
        provide: GENERATE_SCENE_RESPONSE_APPLICATION,
        useValue: new GenerateSceneResponse(
          new HttpSceneResponseProvider(
            {
              endpoint: config.contentFormation.providerEndpoint,
              apiKey: config.contentFormation.providerApiKey,
            },
            contentMeteredFetch,
          ),
        ),
      },
      {
        provide: GENERATE_SCENE_RESPONSE_FEEDBACK_APPLICATION,
        useValue: new GenerateSceneResponseFeedback(
          new HttpSceneResponseProvider(
            {
              endpoint: config.contentFormation.providerEndpoint,
              apiKey: config.contentFormation.providerApiKey,
            },
            contentMeteredFetch,
          ),
        ),
      },
      {
        provide: EVALUATE_SCENE_RESPONSE_APPLICATION,
        useValue: new EvaluateSceneResponse(
          new HttpSceneResponseProvider(
            {
              endpoint: config.contentFormation.providerEndpoint,
              apiKey: config.contentFormation.providerApiKey,
            },
            contentMeteredFetch,
          ),
        ),
      },
      {
        provide: REFRESH_SESSION_APPLICATION,
        useValue: new RefreshSession(sessionLifecycle),
      },
      {
        provide: REVOKE_SESSION_APPLICATION,
        useValue: new RevokeSession(sessionLifecycle),
      },
      { provide: AUTHENTICATE_ACCESS_TOKEN, useValue: accessTokens },
      {
        provide: APP_MESSAGES_APPLICATION,
        useValue: new ListAppMessages(appMessages),
      },
      {
        provide: MARK_ALL_APP_MESSAGES_READ_APPLICATION,
        useValue: new MarkAllAppMessagesRead(appMessages, now),
      },
      {
        provide: ACCOUNT_PROFILE_APPLICATION,
        useValue: new GetAccountProfile(
          new KyselyAccountProfileReader(database, accountFields),
        ),
      },
      {
        provide: ACCOUNT_DELETION_APPLICATION,
        useValue: new DeleteAccount(new KyselyAccountDeletionStore(database)),
      },
      {
        provide: BILLING_SUMMARY_APPLICATION,
        useValue: new GetBillingSummary(
          new KyselyBillingSummaryReader(
            database as Kysely<BillingSummaryDatabase>,
          ),
        ),
      },
      {
        provide: CREATE_RECHARGE_ORDER_APPLICATION,
        useValue: new CreateRechargeOrder(
          billingCommands,
          new HttpPaymentOrderProvider({
            endpoint: config.billing.paymentProviderEndpoint,
            apiKey: config.billing.paymentProviderApiKey,
          }),
        ),
      },
      {
        provide: GET_RECHARGE_ORDER_APPLICATION,
        useValue: new GetRechargeOrder(billingCommands),
      },
      {
        provide: GET_REFUND_APPLICATION,
        useValue: new GetRefundSnapshot(billingCommands),
      },
      {
        provide: REQUEST_REFUND_APPLICATION,
        useValue: new RequestRefund(billingCommands),
      },
      {
        provide: REQUEST_MINOR_RECHARGE_SUPPORT_APPLICATION,
        useValue: new RequestMinorRechargeSupport(billingCommands),
      },
      {
        provide: APPLY_PAYMENT_EVENT_APPLICATION,
        useValue: new ApplyPaymentEvent(
          new HmacPaymentWebhookVerifier(
            { key: config.billing.paymentWebhookSecret },
            { nowEpochSeconds: () => Math.floor(Date.now() / 1_000) },
          ),
          billingCommands,
        ),
      },
      {
        provide: COMPLETE_INITIAL_SETTINGS_APPLICATION,
        useValue: new CompleteInitialSettings(settings),
      },
      {
        provide: GET_CURRENT_SETTINGS_APPLICATION,
        useValue: new GetCurrentSettings(settings),
      },
      {
        provide: READ_VERSIONED_SETTINGS_APPLICATION,
        useValue: new ReadVersionedSettings(
          new KyselyCurrentSettingsStore(database),
        ),
      },
      {
        provide: UPDATE_CURRENT_SETTINGS_APPLICATION,
        useValue: new UpdateCurrentSettings(
          new KyselyCurrentSettingsStore(database),
        ),
      },
      {
        provide: GENERATE_KNOWLEDGE_EXAMPLES_APPLICATION,
        useValue: new GenerateKnowledgeExamples(
          settings,
          new HttpKnowledgeExampleProvider(
            {
              endpoint: config.contentFormation.providerEndpoint,
              apiKey: config.contentFormation.providerApiKey,
            },
            contentMeteredFetch,
          ),
        ),
      },
      {
        provide: RECORDING_TRANSCRIPTION_APPLICATION,
        useValue: new TranscribeRecording(
          new HttpRecordingProvider(
            {
              endpoint: config.contentFormation.providerEndpoint,
              apiKey: config.contentFormation.providerApiKey,
            },
            contentMeteredFetch,
          ),
        ),
      },
      {
        provide: TRANSCRIPT_REPAIR_APPLICATION,
        useValue: new RepairTranscript(
          new HttpRecordingProvider(
            {
              endpoint: config.contentFormation.providerEndpoint,
              apiKey: config.contentFormation.providerApiKey,
            },
            contentMeteredFetch,
          ),
        ),
      },
      {
        provide: GENERATE_SCENE_RECOMMENDATIONS_APPLICATION,
        useValue: new GenerateSceneRecommendations(
          new HttpSceneFormationProvider(
            {
              endpoint: config.contentFormation.providerEndpoint,
              apiKey: config.contentFormation.providerApiKey,
            },
            contentMeteredFetch,
          ),
        ),
      },
      {
        provide: GET_NEXT_SCENE_QUESTION_APPLICATION,
        useValue: new GetNextSceneQuestion(
          new HttpSceneFormationProvider(
            {
              endpoint: config.contentFormation.providerEndpoint,
              apiKey: config.contentFormation.providerApiKey,
            },
            contentMeteredFetch,
          ),
        ),
      },
      {
        provide: GENERATE_SCENE_DRAFT_APPLICATION,
        useValue: new GenerateSceneDraft(
          new HttpSceneFormationProvider(
            {
              endpoint: config.contentFormation.providerEndpoint,
              apiKey: config.contentFormation.providerApiKey,
            },
            contentMeteredFetch,
          ),
          new KyselyContentGenerationSettingsReader(database),
        ),
      },
      {
        provide: REVIEW_SCENE_EDIT_APPLICATION,
        useValue: new ReviewSceneEdit(
          new HttpSceneFormationProvider(
            {
              endpoint: config.contentFormation.providerEndpoint,
              apiKey: config.contentFormation.providerApiKey,
            },
            contentMeteredFetch,
          ),
        ),
      },
      { provide: "PRODUCTION_DATABASE_LIFECYCLE", useValue: databaseLifecycle },
    ],
  })
  class ProductionApiModule {}

  return ProductionApiModule;
};

const canonicalizeEmail = (email: string): string => email.trim().toLowerCase();

const isLoopback = (ip: string): boolean =>
  ip === "127.0.0.1" || ip === "::1" || ip === "::ffff:127.0.0.1";

const aiOperationLabelsZh: Readonly<Record<string, string>> = {
  CONTENT_SAFETY_CHECK_V1: "内容安全检查",
  CONTENT_DEIDENTIFICATION_V1: "内容脱敏",
  SEMANTIC_UNIT_SPLIT_V1: "拆分语义单元",
  CHAPTER_CONTEXT_SELECTION_V1: "生成章节结构",
  ENGLISH_CANDIDATE_GENERATION_V1: "生成英语表达",
  ENGLISH_FIDELITY_CHECK_V1: "检查英语忠实度",
  LEARNING_TARGET_EXTRACTION_V1: "提取学习目标",
  KNOWLEDGE_EXAMPLE_GENERATION_V1: "生成知识例句",
  SENTENCE_EXPLANATION_V1: "生成句子讲解",
  WORD_EXPLANATION_V1: "生成表达讲解",
  READING_FEEDBACK_V1: "生成朗读反馈",
  SPEAKING_TRANSCRIPTION_V1: "口语转写",
  FULL_SENTENCE_SPEAKING_EVALUATION_V1: "整句口语评价",
  PATTERN_PRACTICE_GENERATION_V1: "生成句式练习",
  PATTERN_PRACTICE_FEEDBACK_V1: "生成句式反馈",
  PATTERN_INDEPENDENT_EVALUATION_V1: "句式独立评价",
  SCENE_RESPONSE_GENERATION_V1: "生成场景回应",
  SCENE_RESPONSE_TRAINING_FEEDBACK_V1: "生成场景训练反馈",
  SCENE_RESPONSE_EVALUATION_V1: "场景回应评价",
  SPEECH_TRANSCRIPTION_V1: "录音转写",
  TRANSCRIPT_REPAIR_V1: "转写修复",
  RECENT_SCENE_RECOMMENDATION_V1: "生成近期场景推荐",
  SCENE_NEXT_QUESTION_V1: "生成场景问题",
  SCENE_DIALOGUE_TURN_V1: "生成场景对话",
  SCENE_ARTICLE_DRAFT_V1: "生成场景文章",
  SCENE_EDIT_REVIEW_V1: "审核场景编辑",
};
