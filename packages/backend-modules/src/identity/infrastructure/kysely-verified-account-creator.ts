import { sql, type Kysely } from "kysely";

import type {
  PendingRegistrationChallenge,
  ProtectedPendingRegistration,
  VerifiedAccountCreator,
} from "../application/verify-registration.js";
import type { YearMonth } from "../domain/registration-age.js";
import type {
  IdentityRegistrationDatabase,
  PendingRegistrationPayload,
  RegistrationIdentifierFactory,
} from "./kysely-eligible-registration-starter.js";

export interface PendingRegistrationPayloadUnprotector {
  unprotect(
    protectedPayload: ProtectedPendingRegistration,
  ): Promise<PendingRegistrationPayload>;
}

export type ProtectedAccountField = Readonly<{
  ciphertext: Uint8Array;
  algorithm: string;
  keyVersion: number;
}>;

export interface AccountSensitiveFieldsProtector {
  protectEmail(email: string): Promise<ProtectedAccountField>;
  protectBirthYearMonth(
    birthYearMonth: YearMonth,
  ): Promise<ProtectedAccountField>;
}

export type KyselyVerifiedAccountCreatorDependencies = Readonly<{
  identifiers: RegistrationIdentifierFactory;
  payloadProtector: PendingRegistrationPayloadUnprotector;
  accountFields: AccountSensitiveFieldsProtector;
}>;

const PASSWORD_HASH_VERSION = 1;

export class KyselyVerifiedAccountCreator implements VerifiedAccountCreator {
  public constructor(
    private readonly database: Kysely<IdentityRegistrationDatabase>,
    private readonly dependencies: KyselyVerifiedAccountCreatorDependencies,
  ) {}

  public async create(input: Parameters<VerifiedAccountCreator["create"]>[0]) {
    if (input.challenge.accountId !== null) {
      return this.consumeForExistingAccount(input.challenge, input.verifiedAt);
    }
    const payload = await this.dependencies.payloadProtector.unprotect(
      input.challenge.protectedPayload,
    );
    const [protectedEmail, protectedBirthYearMonth] = await Promise.all([
      this.dependencies.accountFields.protectEmail(payload.email),
      this.dependencies.accountFields.protectBirthYearMonth(
        payload.birthYearMonth,
      ),
    ]);
    const accountId = this.dependencies.identifiers.next();

    return this.database.transaction().execute(async (transaction) => {
      const consumed = await transaction
        .updateTable("identity.verification_challenges")
        .set({
          status: "CONSUMED",
          consumed_at: input.verifiedAt,
          updated_at: input.verifiedAt,
          row_version: sql`row_version + 1`,
        })
        .where("challenge_id", "=", input.challenge.challengeId)
        .where("purpose", "=", "EMAIL_VERIFICATION")
        .where("status", "=", "ACTIVE")
        .where("expires_at", ">", input.verifiedAt)
        .returning("challenge_id")
        .executeTakeFirst();

      if (!consumed) return null;

      await transaction
        .insertInto("identity.accounts")
        .values({
          account_id: accountId,
          birth_year_month_ciphertext: protectedBirthYearMonth.ciphertext,
          birth_year_month_algorithm: protectedBirthYearMonth.algorithm,
          birth_year_month_key_version: protectedBirthYearMonth.keyVersion,
          age_eligible: true,
          age_eligibility_confirmed_at: payload.acceptedAt,
          email_verified_at: input.verifiedAt,
        })
        .execute();

      await transaction
        .insertInto("identity.auth_identities")
        .values({
          account_id: accountId,
          normalized_email_index: input.challenge.subjectIndex,
          normalized_email_index_key_version:
            input.challenge.subjectIndexKeyVersion,
          email_ciphertext: protectedEmail.ciphertext,
          email_algorithm: protectedEmail.algorithm,
          email_key_version: protectedEmail.keyVersion,
          password_hash: payload.passwordHash,
          password_hash_version: PASSWORD_HASH_VERSION,
          verified_at: input.verifiedAt,
        })
        .execute();

      await transaction
        .updateTable("identity.verification_challenges")
        .set({
          account_id: accountId,
          updated_at: input.verifiedAt,
          row_version: sql`row_version + 1`,
        })
        .where("challenge_id", "=", input.challenge.challengeId)
        .where("status", "=", "CONSUMED")
        .execute();

      return { accountId };
    });
  }

  private consumeForExistingAccount(
    challenge: PendingRegistrationChallenge,
    verifiedAt: Date,
  ) {
    const accountId = challenge.accountId;
    if (accountId === null) return Promise.resolve(null);
    return this.database.transaction().execute(async (transaction) => {
      const matchingIdentity = await transaction
        .selectFrom("identity.auth_identities")
        .select("account_id")
        .where("account_id", "=", accountId)
        .where("normalized_email_index", "=", challenge.subjectIndex)
        .where(
          "normalized_email_index_key_version",
          "=",
          challenge.subjectIndexKeyVersion,
        )
        .executeTakeFirst();
      if (!matchingIdentity) return null;

      const consumed = await transaction
        .updateTable("identity.verification_challenges")
        .set({
          account_id: accountId,
          status: "CONSUMED",
          consumed_at: verifiedAt,
          updated_at: verifiedAt,
          row_version: sql`row_version + 1`,
        })
        .where("challenge_id", "=", challenge.challengeId)
        .where("purpose", "=", "EMAIL_VERIFICATION")
        .where("status", "=", "ACTIVE")
        .where("expires_at", ">", verifiedAt)
        .returning("challenge_id")
        .executeTakeFirst();
      return consumed ? { accountId } : null;
    });
  }
}
