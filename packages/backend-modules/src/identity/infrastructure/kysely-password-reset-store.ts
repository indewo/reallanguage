import type { ColumnType, Generated, JSONColumnType, Kysely } from "kysely";

import type {
  PasswordResetCompleter,
  PasswordResetLinkVerifier,
  PasswordResetRequester,
  RequestPasswordResetCommand,
} from "../application/password-reset.js";
import type {
  RegistrationPasswordHasher,
  VerificationSecretFactory,
} from "./kysely-eligible-registration-starter.js";
import type {
  LoginEmailIndex,
  SettingsCompletionReader,
} from "./kysely-credential-verifier.js";
import type { RegistrationSecretVerifier } from "../application/verify-registration.js";

type Timestamp = ColumnType<Date, Date | undefined, Date>;
type RequiredTimestamp = ColumnType<Date, Date, Date>;

interface AuthIdentitiesTable {
  account_id: string;
  normalized_email_index: Uint8Array;
  normalized_email_index_key_version: number;
  password_hash: string;
  password_hash_version: number;
  row_version: Generated<number>;
  updated_at: Timestamp;
}

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
  expires_at: RequiredTimestamp;
  consumed_at: RequiredTimestamp | null;
  superseded_at: RequiredTimestamp | null;
  row_version: Generated<number>;
  last_email_requested_at: RequiredTimestamp;
  email_request_count: Generated<number>;
  created_at: Timestamp;
  updated_at: Timestamp;
}

interface PasswordResetRequestsTable {
  command_id: string;
  challenge_id: string;
  account_id: string | null;
  delivery_secret_ciphertext: Uint8Array | null;
  delivery_secret_algorithm: string | null;
  delivery_secret_key_version: number | null;
  created_at: Timestamp;
}

interface PasswordResetGrantsTable {
  grant_id: string;
  challenge_id: string;
  account_id: string;
  reset_token_digest: Uint8Array;
  reset_token_digest_key_version: number;
  expires_at: RequiredTimestamp;
  consumed_at: RequiredTimestamp | null;
  completion_command_id: string | null;
  created_at: Timestamp;
  updated_at: Timestamp;
}

interface UserSessionsTable {
  session_id: string;
  account_id: string;
  revoked_at: RequiredTimestamp | null;
  updated_at: Timestamp;
}

interface OutboxEventsTable {
  event_id: string;
  user_id: string | null;
  aggregate_id: string;
  event_type: string;
  schema_version: number;
  payload: JSONColumnType<Readonly<{ challengeId: string }>, string, never>;
  request_id: string;
  available_at: Timestamp;
  published_at: RequiredTimestamp | null;
  created_at: Timestamp;
  updated_at: Timestamp;
}

export interface IdentityPasswordResetDatabase {
  "identity.auth_identities": AuthIdentitiesTable;
  "identity.verification_challenges": VerificationChallengesTable;
  "identity.password_reset_requests": PasswordResetRequestsTable;
  "identity.password_reset_grants": PasswordResetGrantsTable;
  "identity.user_sessions": UserSessionsTable;
  "platform.outbox_events": OutboxEventsTable;
}

export interface PasswordResetInstantClock {
  now(): Date;
}
export interface PasswordResetIdentifierFactory {
  next(): string;
}
export interface PasswordResetTokenFactory {
  create(): Readonly<{
    bearer: string;
    digest: Uint8Array;
    digestKeyVersion: number;
  }>;
}
export interface PasswordResetTokenDigester {
  digest(bearer: string): Readonly<{ digest: Uint8Array; keyVersion: number }>;
}
export interface PasswordResetDeliverySecretProtector {
  protect(bearer: string): Promise<
    Readonly<{
      ciphertext: Uint8Array;
      algorithm: string;
      keyVersion: number;
    }>
  >;
}

export type KyselyPasswordResetDependencies = Readonly<{
  clock: PasswordResetInstantClock;
  identifiers: PasswordResetIdentifierFactory;
  emailIndex: LoginEmailIndex;
  verificationSecrets: VerificationSecretFactory;
  deliverySecrets: PasswordResetDeliverySecretProtector;
  verificationSecretVerifier: RegistrationSecretVerifier;
  resetTokens: PasswordResetTokenFactory;
  resetTokenDigester: PasswordResetTokenDigester;
  passwordHasher: RegistrationPasswordHasher;
  settings: SettingsCompletionReader;
}>;

const LINK_TTL_MILLISECONDS = 30 * 60 * 1000;
const RESET_GRANT_TTL_MILLISECONDS = 15 * 60 * 1000;

export class KyselyPasswordResetStore
  implements
    PasswordResetRequester,
    PasswordResetLinkVerifier,
    PasswordResetCompleter
{
  public constructor(
    private readonly database: Kysely<IdentityPasswordResetDatabase>,
    private readonly dependencies: KyselyPasswordResetDependencies,
  ) {}

  public async request(
    command: RequestPasswordResetCommand,
  ): Promise<Readonly<{ challengeId: string }>> {
    const known = await this.findRequest(command.commandId);
    if (known) return known;

    const requestedAt = this.dependencies.clock.now();
    const challengeId = this.dependencies.identifiers.next();
    const email = this.dependencies.emailIndex.create(command.email);
    const identity = await this.database
      .selectFrom("identity.auth_identities")
      .select("account_id")
      .where("normalized_email_index", "=", email.digest)
      .where("normalized_email_index_key_version", "=", email.keyVersion)
      .executeTakeFirst();
    const verification = identity
      ? this.dependencies.verificationSecrets.create()
      : null;
    const protectedDelivery = verification
      ? await this.dependencies.deliverySecrets.protect(verification.bearer)
      : null;

    return this.database.transaction().execute(async (transaction) => {
      const inserted = await transaction
        .insertInto("identity.password_reset_requests")
        .values({
          command_id: command.commandId,
          challenge_id: challengeId,
          account_id: identity?.account_id ?? null,
          delivery_secret_ciphertext: protectedDelivery?.ciphertext ?? null,
          delivery_secret_algorithm: protectedDelivery?.algorithm ?? null,
          delivery_secret_key_version: protectedDelivery?.keyVersion ?? null,
          created_at: requestedAt,
        })
        .onConflict((conflict) => conflict.column("command_id").doNothing())
        .returning("challenge_id")
        .executeTakeFirst();
      if (!inserted) {
        const existing = await transaction
          .selectFrom("identity.password_reset_requests")
          .select("challenge_id")
          .where("command_id", "=", command.commandId)
          .executeTakeFirstOrThrow();
        return { challengeId: existing.challenge_id };
      }
      if (!identity) return { challengeId };

      if (!verification) return { challengeId };
      await transaction
        .updateTable("identity.verification_challenges")
        .set({
          status: "SUPERSEDED",
          superseded_at: requestedAt,
          updated_at: requestedAt,
        })
        .where("purpose", "=", "PASSWORD_RESET")
        .where("subject_index", "=", email.digest)
        .where("status", "=", "ACTIVE")
        .execute();
      await transaction
        .insertInto("identity.verification_challenges")
        .values({
          challenge_id: challengeId,
          account_id: identity.account_id,
          purpose: "PASSWORD_RESET",
          subject_index: email.digest,
          subject_index_key_version: email.keyVersion,
          secret_digest: verification.digest,
          secret_digest_key_version: verification.digestKeyVersion,
          pending_registration_ciphertext: null,
          payload_algorithm: null,
          payload_key_version: null,
          expires_at: new Date(requestedAt.getTime() + LINK_TTL_MILLISECONDS),
          consumed_at: null,
          superseded_at: null,
          last_email_requested_at: requestedAt,
          created_at: requestedAt,
          updated_at: requestedAt,
        })
        .execute();
      await transaction
        .insertInto("platform.outbox_events")
        .values({
          event_id: this.dependencies.identifiers.next(),
          user_id: identity.account_id,
          aggregate_id: challengeId,
          event_type: "identity.password-reset-requested",
          schema_version: 1,
          payload: JSON.stringify({
            challengeId,
          }),
          request_id: command.commandId,
          available_at: requestedAt,
          published_at: null,
          created_at: requestedAt,
          updated_at: requestedAt,
        })
        .execute();
      return { challengeId };
    });
  }

  public async verify(command: {
    challengeId: string;
    bearer: string;
  }): Promise<Readonly<{ resetToken: string }> | null> {
    const now = this.dependencies.clock.now();
    const challenge = await this.database
      .selectFrom("identity.verification_challenges")
      .select([
        "account_id",
        "secret_digest",
        "secret_digest_key_version",
        "status",
        "expires_at",
      ])
      .where("challenge_id", "=", command.challengeId)
      .where("purpose", "=", "PASSWORD_RESET")
      .executeTakeFirst();
    if (
      !challenge?.account_id ||
      challenge.status !== "ACTIVE" ||
      challenge.expires_at.getTime() <= now.getTime() ||
      !this.dependencies.verificationSecretVerifier.verify({
        bearer: command.bearer,
        digest: challenge.secret_digest,
        digestKeyVersion: challenge.secret_digest_key_version,
      })
    )
      return null;

    const resetToken = this.dependencies.resetTokens.create();
    return this.database.transaction().execute(async (transaction) => {
      const consumed = await transaction
        .updateTable("identity.verification_challenges")
        .set({ status: "CONSUMED", consumed_at: now, updated_at: now })
        .where("challenge_id", "=", command.challengeId)
        .where("status", "=", "ACTIVE")
        .where("expires_at", ">", now)
        .returning("account_id")
        .executeTakeFirst();
      if (!consumed?.account_id) return null;

      await transaction
        .insertInto("identity.password_reset_grants")
        .values({
          grant_id: this.dependencies.identifiers.next(),
          challenge_id: command.challengeId,
          account_id: consumed.account_id,
          reset_token_digest: resetToken.digest,
          reset_token_digest_key_version: resetToken.digestKeyVersion,
          expires_at: new Date(now.getTime() + RESET_GRANT_TTL_MILLISECONDS),
          consumed_at: null,
          completion_command_id: null,
          created_at: now,
          updated_at: now,
        })
        .execute();
      return { resetToken: resetToken.bearer };
    });
  }

  public async complete(command: {
    resetToken: string;
    normalizedPassword: string;
    commandId: string;
  }): Promise<Readonly<{ initialSettingsComplete: boolean }> | null> {
    const now = this.dependencies.clock.now();
    const token = this.dependencies.resetTokenDigester.digest(
      command.resetToken,
    );
    const grant = await this.database
      .selectFrom("identity.password_reset_grants")
      .select([
        "grant_id",
        "account_id",
        "expires_at",
        "consumed_at",
        "completion_command_id",
      ])
      .where("reset_token_digest", "=", token.digest)
      .where("reset_token_digest_key_version", "=", token.keyVersion)
      .executeTakeFirst();
    if (!grant) return null;
    if (grant.consumed_at) {
      return grant.completion_command_id === command.commandId
        ? {
            initialSettingsComplete:
              await this.dependencies.settings.hasCompleted(grant.account_id),
          }
        : null;
    }
    if (grant.expires_at.getTime() <= now.getTime()) return null;

    const passwordHash = await this.dependencies.passwordHasher.hash(
      command.normalizedPassword,
    );
    const accountId = await this.database
      .transaction()
      .execute(async (transaction) => {
        const consumed = await transaction
          .updateTable("identity.password_reset_grants")
          .set({
            consumed_at: now,
            completion_command_id: command.commandId,
            updated_at: now,
          })
          .where("grant_id", "=", grant.grant_id)
          .where("consumed_at", "is", null)
          .where("expires_at", ">", now)
          .returning("account_id")
          .executeTakeFirst();
        if (!consumed) return null;
        await transaction
          .updateTable("identity.auth_identities")
          .set((expressions) => ({
            password_hash: passwordHash,
            password_hash_version: 1,
            row_version: expressions("row_version", "+", 1),
            updated_at: now,
          }))
          .where("account_id", "=", consumed.account_id)
          .executeTakeFirstOrThrow();
        await transaction
          .updateTable("identity.user_sessions")
          .set({ revoked_at: now, updated_at: now })
          .where("account_id", "=", consumed.account_id)
          .where("revoked_at", "is", null)
          .execute();
        return consumed.account_id;
      });
    return accountId
      ? {
          initialSettingsComplete:
            await this.dependencies.settings.hasCompleted(accountId),
        }
      : null;
  }

  private async findRequest(commandId: string) {
    const request = await this.database
      .selectFrom("identity.password_reset_requests")
      .select("challenge_id")
      .where("command_id", "=", commandId)
      .executeTakeFirst();
    return request ? { challengeId: request.challenge_id } : null;
  }
}
