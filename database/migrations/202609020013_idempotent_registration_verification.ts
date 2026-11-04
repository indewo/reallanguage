import { sql } from "kysely";
import type { Migration } from "kysely/migration";

export const idempotentRegistrationVerificationMigration: Migration = {
  async up(database) {
    await sql`
      alter table identity.verification_challenges
      drop constraint verification_challenges_payload_shape
    `.execute(database);

    await sql`
      update identity.verification_challenges as challenge
         set account_id = identity.account_id
        from identity.auth_identities as identity
       where challenge.purpose = 'EMAIL_VERIFICATION'
         and challenge.status = 'CONSUMED'
         and challenge.account_id is null
         and identity.normalized_email_index = challenge.subject_index
         and identity.normalized_email_index_key_version = challenge.subject_index_key_version
    `.execute(database);

    await sql`
      alter table identity.verification_challenges
      add constraint verification_challenges_payload_shape check (
        (
          purpose = 'EMAIL_VERIFICATION'
          and pending_registration_ciphertext is not null
          and payload_algorithm is not null
          and payload_key_version is not null
          and payload_key_version > 0
          and (status = 'CONSUMED' or account_id is null)
        ) or (
          purpose = 'PASSWORD_RESET'
          and account_id is not null
          and pending_registration_ciphertext is null
          and payload_algorithm is null
          and payload_key_version is null
        )
      )
    `.execute(database);
  },

  async down(database) {
    await sql`
      alter table identity.verification_challenges
      drop constraint verification_challenges_payload_shape
    `.execute(database);
    await sql`
      update identity.verification_challenges
         set account_id = null
       where purpose = 'EMAIL_VERIFICATION'
    `.execute(database);
    await sql`
      alter table identity.verification_challenges
      add constraint verification_challenges_payload_shape check (
        (
          purpose = 'EMAIL_VERIFICATION'
          and account_id is null
          and pending_registration_ciphertext is not null
          and payload_algorithm is not null
          and payload_key_version is not null
          and payload_key_version > 0
        ) or (
          purpose = 'PASSWORD_RESET'
          and account_id is not null
          and pending_registration_ciphertext is null
          and payload_algorithm is null
          and payload_key_version is null
        )
      )
    `.execute(database);
  },
};
