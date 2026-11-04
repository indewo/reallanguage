import {
  sql,
  type ColumnType,
  type Generated,
  type JSONColumnType,
  type Kysely,
} from "kysely";

import type {
  EligibleRegistrationStarter,
  StartRegistrationCommand,
} from "../application/start-registration.js";
import type { YearMonth } from "../domain/registration-age.js";

type Timestamp = ColumnType<Date, Date, Date>;
type GeneratedTimestamp = ColumnType<Date, Date | undefined, Date>;

interface VerificationChallengesTable {
  challenge_id: string;
  account_id: string | null;
  purpose: "EMAIL_VERIFICATION" | "PASSWORD_RESET";
  subject_index: Uint8Array;
  subject_index_key_version: number;
  secret_digest: Uint8Array;
  secret_digest_key_version: number;
  pending_registration_ciphertext: Uint8Array | null;
  payload_algorithm: string | null;
  payload_key_version: number | null;
  status: Generated<"ACTIVE" | "CONSUMED" | "SUPERSEDED" | "EXPIRED">;
  expires_at: Timestamp;
  consumed_at: Timestamp | null;
  superseded_at: Timestamp | null;
  row_version: Generated<number>;
  created_at: GeneratedTimestamp;
  updated_at: GeneratedTimestamp;
  last_email_requested_at: Timestamp;
  email_request_count: Generated<number>;
}

interface OutboxEventsTable {
  event_id: string;
  user_id: string | null;
  aggregate_id: string;
  event_type: string;
  schema_version: number;
  payload: JSONColumnType<Readonly<{ challengeId: string }>, string, never>;
  request_id: string;
  status: Generated<"PENDING" | "PUBLISHED">;
  attempt_count: Generated<number>;
  available_at: GeneratedTimestamp;
  published_at: Timestamp | null;
  row_version: Generated<number>;
  created_at: GeneratedTimestamp;
  updated_at: GeneratedTimestamp;
}

interface AccountsTable {
  account_id: string;
  birth_year_month_ciphertext: Uint8Array;
  birth_year_month_algorithm: string;
  birth_year_month_key_version: number;
  age_eligible: boolean;
  age_eligibility_confirmed_at: Timestamp;
  email_verified_at: Timestamp;
  row_version: Generated<number>;
  created_at: GeneratedTimestamp;
  updated_at: GeneratedTimestamp;
}

interface AuthIdentitiesTable {
  account_id: string;
  normalized_email_index: Uint8Array;
  normalized_email_index_key_version: number;
  email_ciphertext: Uint8Array;
  email_algorithm: string;
  email_key_version: number;
  password_hash: string;
  password_hash_version: number;
  verified_at: Timestamp;
  row_version: Generated<number>;
  created_at: GeneratedTimestamp;
  updated_at: GeneratedTimestamp;
}

export interface IdentityRegistrationDatabase {
  "identity.accounts": AccountsTable;
  "identity.auth_identities": AuthIdentitiesTable;
  "identity.verification_challenges": VerificationChallengesTable;
  "platform.outbox_events": OutboxEventsTable;
}

export interface RegistrationInstantClock {
  now(): Date;
}

export interface RegistrationIdentifierFactory {
  next(): string;
}

export interface RegistrationPasswordHasher {
  hash(password: string): Promise<string>;
}

export interface VerificationSecretFactory {
  create(): Readonly<{
    bearer: string;
    digest: Uint8Array;
    digestKeyVersion: number;
  }>;
}

export type PendingRegistrationPayload = Readonly<{
  email: string;
  passwordHash: string;
  birthYearMonth: YearMonth;
  acceptedTerms: true;
  acceptedPrivacyPolicy: true;
  acceptedAt: Date;
  verificationBearer: string;
}>;

export interface RegistrationPayloadProtector {
  protect(payload: PendingRegistrationPayload): Promise<
    Readonly<{
      subjectIndex: Uint8Array;
      subjectIndexKeyVersion: number;
      ciphertext: Uint8Array;
      algorithm: string;
      keyVersion: number;
    }>
  >;
}

export type KyselyEligibleRegistrationDependencies = Readonly<{
  clock: RegistrationInstantClock;
  identifiers: RegistrationIdentifierFactory;
  passwordHasher: RegistrationPasswordHasher;
  payloadProtector: RegistrationPayloadProtector;
  verificationSecrets: VerificationSecretFactory;
}>;

const EMAIL_VERIFICATION_TTL_MILLISECONDS = 30 * 60 * 1000;

export class KyselyEligibleRegistrationStarter implements EligibleRegistrationStarter {
  public constructor(
    private readonly database: Kysely<IdentityRegistrationDatabase>,
    private readonly dependencies: KyselyEligibleRegistrationDependencies,
  ) {}

  public async startEligibleRegistration(
    command: StartRegistrationCommand,
  ): Promise<Readonly<{ registrationId: string }>> {
    const acceptedAt = this.dependencies.clock.now();
    const challengeId = this.dependencies.identifiers.next();
    const eventId = this.dependencies.identifiers.next();
    const passwordHash = await this.dependencies.passwordHasher.hash(
      command.password,
    );
    const verificationSecret = this.dependencies.verificationSecrets.create();
    const protectedPayload = await this.dependencies.payloadProtector.protect({
      email: command.email,
      passwordHash,
      birthYearMonth: command.birthYearMonth,
      acceptedTerms: command.acceptedTerms,
      acceptedPrivacyPolicy: command.acceptedPrivacyPolicy,
      acceptedAt,
      verificationBearer: verificationSecret.bearer,
    });
    const expiresAt = new Date(
      acceptedAt.getTime() + EMAIL_VERIFICATION_TTL_MILLISECONDS,
    );

    await this.database.transaction().execute(async (transaction) => {
      await transaction
        .updateTable("identity.verification_challenges")
        .set({
          status: "SUPERSEDED",
          superseded_at: acceptedAt,
          updated_at: acceptedAt,
          row_version: sql<number>`row_version + 1`,
        })
        .where("purpose", "=", "EMAIL_VERIFICATION")
        .where("subject_index", "=", protectedPayload.subjectIndex)
        .where(
          "subject_index_key_version",
          "=",
          protectedPayload.subjectIndexKeyVersion,
        )
        .where("status", "=", "ACTIVE")
        .execute();

      await transaction
        .insertInto("identity.verification_challenges")
        .values({
          challenge_id: challengeId,
          account_id: null,
          purpose: "EMAIL_VERIFICATION",
          subject_index: protectedPayload.subjectIndex,
          subject_index_key_version: protectedPayload.subjectIndexKeyVersion,
          secret_digest: verificationSecret.digest,
          secret_digest_key_version: verificationSecret.digestKeyVersion,
          pending_registration_ciphertext: protectedPayload.ciphertext,
          payload_algorithm: protectedPayload.algorithm,
          payload_key_version: protectedPayload.keyVersion,
          expires_at: expiresAt,
          consumed_at: null,
          superseded_at: null,
          last_email_requested_at: acceptedAt,
          created_at: acceptedAt,
          updated_at: acceptedAt,
        })
        .execute();

      await transaction
        .insertInto("platform.outbox_events")
        .values({
          event_id: eventId,
          user_id: null,
          aggregate_id: challengeId,
          event_type: "identity.email-verification-requested",
          schema_version: 1,
          payload: JSON.stringify({ challengeId }),
          request_id: command.requestId,
          available_at: acceptedAt,
          published_at: null,
          created_at: acceptedAt,
          updated_at: acceptedAt,
        })
        .execute();
    });

    return { registrationId: challengeId };
  }
}
