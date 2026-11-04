import type { Kysely } from "kysely";
import {
  type MigrationProvider,
  Migrator,
  NO_MIGRATIONS,
} from "kysely/migration";
import { identityRegistrationMigration } from "../migrations/202609010001_identity_registration.js";
import { platformOutboxMigration } from "../migrations/202609010002_platform_outbox.js";
import { verificationEmailResendMigration } from "../migrations/202609010003_verification_email_resend.js";
import { identityUserSessionsMigration } from "../migrations/202609010004_identity_user_sessions.js";
import { settingsUserSettingsMigration } from "../migrations/202609010005_settings_user_settings.js";
import { identityAccessTokensMigration } from "../migrations/202609010006_identity_access_tokens.js";
import { identityPasswordResetMigration } from "../migrations/202609010007_identity_password_reset.js";
import { safetyChecksMigration } from "../migrations/202609010008_safety_checks.js";
import { safetyHandlingMigration } from "../migrations/202609020009_safety_handling.js";
import { messagingAppMessagesMigration } from "../migrations/202609020010_messaging_app_messages.js";
import { billingLedgerMigration } from "../migrations/202609020011_billing_ledger.js";
import { minorRechargeSupportMigration } from "../migrations/202609020012_minor_recharge_support.js";
import { idempotentRegistrationVerificationMigration } from "../migrations/202609020013_idempotent_registration_verification.js";
import { adminReviewMigration } from "../migrations/202609040014_admin_review.js";
import { adminMfaEnrollmentMigration } from "../migrations/202609040015_admin_mfa_enrollment.js";
import { aiResultDeliveryMigration } from "../migrations/202609070016_ai_result_delivery.js";

const provider: MigrationProvider = {
  async getMigrations() {
    return {
      "202609070016_ai_result_delivery": aiResultDeliveryMigration,
      "202609010001_identity_registration": identityRegistrationMigration,
      "202609010002_platform_outbox": platformOutboxMigration,
      "202609010003_verification_email_resend":
        verificationEmailResendMigration,
      "202609010004_identity_user_sessions": identityUserSessionsMigration,
      "202609010005_settings_user_settings": settingsUserSettingsMigration,
      "202609010006_identity_access_tokens": identityAccessTokensMigration,
      "202609010007_identity_password_reset": identityPasswordResetMigration,
      "202609010008_safety_checks": safetyChecksMigration,
      "202609020009_safety_handling": safetyHandlingMigration,
      "202609020010_messaging_app_messages": messagingAppMessagesMigration,
      "202609020011_billing_ledger": billingLedgerMigration,
      "202609020012_minor_recharge_support": minorRechargeSupportMigration,
      "202609020013_idempotent_registration_verification":
        idempotentRegistrationVerificationMigration,
      "202609040014_admin_review": adminReviewMigration,
      "202609040015_admin_mfa_enrollment": adminMfaEnrollmentMigration,
    };
  },
};

export const migrateToLatest = async <Database>(
  database: Kysely<Database>,
): Promise<void> => {
  const result = await new Migrator({
    db: database,
    provider,
  }).migrateToLatest();
  if (result.error) throw result.error;
};

export const migrateToEmpty = async <Database>(
  database: Kysely<Database>,
): Promise<void> => {
  const result = await new Migrator({ db: database, provider }).migrateTo(
    NO_MIGRATIONS,
  );
  if (result.error) throw result.error;
};
