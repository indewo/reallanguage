import type { Kysely } from "kysely";

import type {
  PendingRegistrationChallenge,
  RegistrationChallengeReader,
} from "../application/verify-registration.js";
import type { IdentityRegistrationDatabase } from "./kysely-eligible-registration-starter.js";

export class KyselyRegistrationChallengeReader implements RegistrationChallengeReader {
  public constructor(
    private readonly database: Kysely<IdentityRegistrationDatabase>,
  ) {}

  public async findById(
    challengeId: string,
  ): Promise<PendingRegistrationChallenge | null> {
    const row = await this.database
      .selectFrom("identity.verification_challenges as challenge")
      .leftJoin("identity.auth_identities as identity", (join) =>
        join
          .onRef(
            "identity.normalized_email_index",
            "=",
            "challenge.subject_index",
          )
          .onRef(
            "identity.normalized_email_index_key_version",
            "=",
            "challenge.subject_index_key_version",
          ),
      )
      .select([
        "challenge.challenge_id as challenge_id",
        "challenge.account_id as challenge_account_id",
        "identity.account_id as identity_account_id",
        "challenge.status as status",
        "challenge.expires_at as expires_at",
        "challenge.subject_index as subject_index",
        "challenge.subject_index_key_version as subject_index_key_version",
        "challenge.secret_digest as secret_digest",
        "challenge.secret_digest_key_version as secret_digest_key_version",
        "challenge.pending_registration_ciphertext as pending_registration_ciphertext",
        "challenge.payload_algorithm as payload_algorithm",
        "challenge.payload_key_version as payload_key_version",
      ])
      .where("challenge.challenge_id", "=", challengeId)
      .where("challenge.purpose", "=", "EMAIL_VERIFICATION")
      .executeTakeFirst();

    if (!row) return null;
    if (
      row.pending_registration_ciphertext === null ||
      row.payload_algorithm === null ||
      row.payload_key_version === null
    ) {
      throw new Error("Email-verification challenge has no protected payload");
    }

    return {
      challengeId: row.challenge_id,
      accountId: row.challenge_account_id ?? row.identity_account_id ?? null,
      status: row.status,
      expiresAt: new Date(row.expires_at),
      subjectIndex: row.subject_index,
      subjectIndexKeyVersion: row.subject_index_key_version,
      secretDigest: row.secret_digest,
      secretDigestKeyVersion: row.secret_digest_key_version,
      protectedPayload: {
        ciphertext: row.pending_registration_ciphertext,
        algorithm: row.payload_algorithm,
        keyVersion: row.payload_key_version,
      },
    };
  }
}
