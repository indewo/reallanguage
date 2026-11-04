import { type Kysely, sql } from "kysely";

import type {
  RegistrationVerificationEmailResender,
  ResendRegistrationVerificationResult,
} from "../application/resend-registration-verification.js";
import type { IdentityRegistrationDatabase } from "./kysely-eligible-registration-starter.js";

export interface VerificationEmailEventIdentifierFactory {
  next(): string;
}

export class KyselyRegistrationVerificationEmailResender implements RegistrationVerificationEmailResender {
  public constructor(
    private readonly database: Kysely<IdentityRegistrationDatabase>,
    private readonly identifiers: VerificationEmailEventIdentifierFactory,
  ) {}

  public resend(
    command: Readonly<{
      registrationId: string;
      requestId: string;
      requestedAt: Date;
      cooldownSeconds: number;
    }>,
  ): Promise<ResendRegistrationVerificationResult> {
    return this.database.transaction().execute(async (transaction) => {
      const challenge = await transaction
        .selectFrom("identity.verification_challenges")
        .select(["status", "expires_at", "last_email_requested_at"])
        .where("challenge_id", "=", command.registrationId)
        .where("purpose", "=", "EMAIL_VERIFICATION")
        .forUpdate()
        .executeTakeFirst();

      if (
        !challenge ||
        challenge.status !== "ACTIVE" ||
        new Date(challenge.expires_at).getTime() <=
          command.requestedAt.getTime()
      ) {
        return { status: "NOT_AVAILABLE" };
      }

      const availableAt =
        new Date(challenge.last_email_requested_at).getTime() +
        command.cooldownSeconds * 1_000;
      const remainingMilliseconds = availableAt - command.requestedAt.getTime();
      if (remainingMilliseconds > 0) {
        return {
          status: "COOLDOWN",
          retryAfterSeconds: Math.ceil(remainingMilliseconds / 1_000),
        };
      }

      await transaction
        .updateTable("identity.verification_challenges")
        .set({
          last_email_requested_at: command.requestedAt,
          email_request_count: sql`email_request_count + 1`,
          row_version: sql`row_version + 1`,
          updated_at: command.requestedAt,
        })
        .where("challenge_id", "=", command.registrationId)
        .executeTakeFirstOrThrow();

      await transaction
        .insertInto("platform.outbox_events")
        .values({
          event_id: this.identifiers.next(),
          user_id: null,
          aggregate_id: command.registrationId,
          event_type: "identity.email-verification-requested",
          schema_version: 1,
          payload: JSON.stringify({ challengeId: command.registrationId }),
          request_id: command.requestId,
          available_at: command.requestedAt,
          published_at: null,
          created_at: command.requestedAt,
          updated_at: command.requestedAt,
        })
        .execute();

      return {
        status: "RESENT",
        retryAfterSeconds: command.cooldownSeconds,
      };
    });
  }
}
