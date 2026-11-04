import { PGlite } from "@electric-sql/pglite";
import { migrateToLatest } from "@reallanguage/database";
import { Kysely, PGliteDialect, sql } from "kysely";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import type { IdentityRegistrationDatabase } from "./kysely-eligible-registration-starter.js";
import { KyselyRegistrationVerificationEmailResender } from "./kysely-registration-verification-email-resender.js";

const challengeId = "10000000-0000-4000-8000-000000000001";
const eventId = "20000000-0000-4000-8000-000000000001";
const lastRequestedAt = new Date("2026-09-01T00:00:00.000Z");
let database: Kysely<IdentityRegistrationDatabase>;

beforeEach(async () => {
  database = new Kysely({
    dialect: new PGliteDialect({ pglite: new PGlite() }),
  });
  await migrateToLatest(database);
});

afterEach(async () => {
  await database.destroy();
});

describe("KyselyRegistrationVerificationEmailResender", () => {
  it("returns the remaining cooldown without writing an outbox event", async () => {
    await insertChallenge();
    const resender = createResender();

    await expect(
      resender.resend({
        registrationId: challengeId,
        requestId: "request-1",
        requestedAt: new Date("2026-09-01T00:00:30.001Z"),
        cooldownSeconds: 60,
      }),
    ).resolves.toEqual({ status: "COOLDOWN", retryAfterSeconds: 30 });

    expect(await eventCount()).toBe(0);
  });

  it("atomically records a resend and its email outbox event", async () => {
    await insertChallenge();
    const resender = createResender();
    const requestedAt = new Date("2026-09-01T00:01:00.000Z");

    await expect(
      resender.resend({
        registrationId: challengeId,
        requestId: "request-2",
        requestedAt,
        cooldownSeconds: 60,
      }),
    ).resolves.toEqual({ status: "RESENT", retryAfterSeconds: 60 });

    const challenge = await sql<{
      email_request_count: number;
      last_email_requested_at: Date;
      row_version: number;
    }>`
      select email_request_count, last_email_requested_at, row_version
      from identity.verification_challenges
      where challenge_id = ${challengeId}
    `.execute(database);
    expect(challenge.rows[0]).toMatchObject({
      email_request_count: 2,
      row_version: 1,
    });
    expect(new Date(challenge.rows[0]!.last_email_requested_at)).toEqual(
      requestedAt,
    );

    const events = await sql<{
      aggregate_id: string;
      event_type: string;
      payload: unknown;
      request_id: string;
    }>`select aggregate_id, event_type, payload, request_id from platform.outbox_events`.execute(
      database,
    );
    expect(events.rows).toEqual([
      {
        aggregate_id: challengeId,
        event_type: "identity.email-verification-requested",
        payload: { challengeId },
        request_id: "request-2",
      },
    ]);
  });

  it.each(["CONSUMED", "EXPIRED"] as const)(
    "does not resend a %s challenge",
    async (status) => {
      await insertChallenge(status);
      const resender = createResender();

      await expect(
        resender.resend({
          registrationId: challengeId,
          requestId: "request-3",
          requestedAt: new Date("2026-09-01T00:02:00.000Z"),
          cooldownSeconds: 60,
        }),
      ).resolves.toEqual({ status: "NOT_AVAILABLE" });
      expect(await eventCount()).toBe(0);
    },
  );

  it("does not resend an active challenge after its expiry time", async () => {
    await insertChallenge();
    const resender = createResender();

    await expect(
      resender.resend({
        registrationId: challengeId,
        requestId: "request-expired",
        requestedAt: new Date("2026-09-01T00:10:00.000Z"),
        cooldownSeconds: 60,
      }),
    ).resolves.toEqual({ status: "NOT_AVAILABLE" });
    expect(await eventCount()).toBe(0);
  });

  it("does not reveal whether an unknown challenge exists", async () => {
    const resender = createResender();

    await expect(
      resender.resend({
        registrationId: "10000000-0000-4000-8000-000000000099",
        requestId: "request-unknown",
        requestedAt: new Date("2026-09-01T00:02:00.000Z"),
        cooldownSeconds: 60,
      }),
    ).resolves.toEqual({ status: "NOT_AVAILABLE" });
    expect(await eventCount()).toBe(0);
  });

  it("rolls back the resend counter when the outbox write fails", async () => {
    await insertChallenge();
    await sql`
      insert into platform.outbox_events (
        event_id, aggregate_id, event_type, schema_version, payload, request_id
      ) values (
        ${eventId}, ${challengeId}, 'existing', 1, ${JSON.stringify({ challengeId })}, 'existing'
      )
    `.execute(database);
    const resender = createResender();

    await expect(
      resender.resend({
        registrationId: challengeId,
        requestId: "request-4",
        requestedAt: new Date("2026-09-01T00:02:00.000Z"),
        cooldownSeconds: 60,
      }),
    ).rejects.toThrow();

    const result = await sql<{ email_request_count: number }>`
      select email_request_count
      from identity.verification_challenges
      where challenge_id = ${challengeId}
    `.execute(database);
    expect(result.rows[0]?.email_request_count).toBe(1);
  });
});

const createResender = () =>
  new KyselyRegistrationVerificationEmailResender(database, {
    next: vi.fn(() => eventId),
  });

const eventCount = async () => {
  const result = await sql<{ count: number }>`
    select count(*)::int as count from platform.outbox_events
  `.execute(database);
  return result.rows[0]?.count;
};

const insertChallenge = async (
  status: "ACTIVE" | "CONSUMED" | "EXPIRED" = "ACTIVE",
) => {
  const consumedAt = status === "CONSUMED" ? lastRequestedAt : null;
  await sql`
    insert into identity.verification_challenges (
      challenge_id, purpose, subject_index, subject_index_key_version,
      secret_digest, secret_digest_key_version, pending_registration_ciphertext,
      payload_algorithm, payload_key_version, status, expires_at, consumed_at,
      last_email_requested_at, email_request_count, created_at, updated_at
    ) values (
      ${challengeId}, 'EMAIL_VERIFICATION', decode('01', 'hex'), 1,
      decode('02', 'hex'), 1, decode('03', 'hex'), 'AES-256-GCM', 1,
      ${status}, ${new Date("2026-09-01T00:10:00.000Z")}, ${consumedAt},
      ${lastRequestedAt}, 1, ${lastRequestedAt}, ${lastRequestedAt}
    )
  `.execute(database);
};
