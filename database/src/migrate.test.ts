import { PGlite } from "@electric-sql/pglite";
import { Kysely, PGliteDialect, sql } from "kysely";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { migrateToEmpty, migrateToLatest } from "./migrate.js";
import { aiResultDeliveryMigration } from "../migrations/202609070016_ai_result_delivery.js";

let database: Kysely<unknown>;

beforeEach(() => {
  database = new Kysely({
    dialect: new PGliteDialect({ pglite: new PGlite() }),
  });
});

afterEach(async () => {
  await database.destroy();
});

describe("Cloud database migrations", () => {
  it("adds non-replayable tombstones for legacy supplier reservations without changing balances", async () => {
    await migrateToLatest(database);
    await aiResultDeliveryMigration.down!(database);
    await sql`insert into identity.accounts
      (account_id,birth_year_month_ciphertext,birth_year_month_algorithm,birth_year_month_key_version,age_eligible,age_eligibility_confirmed_at,email_verified_at)
      values ('61000000-0000-4000-8000-000000000001',decode('01','hex'),'AES-256-GCM/v1',1,true,now(),now())`.execute(
      database,
    );
    await sql`insert into billing.pricing_rules
      (pricing_rule_id,multiplier_numerator,multiplier_denominator,rounding_mode,minimum_charge_fen,effective_at)
      values ('61000000-0000-4000-8000-000000000002',2,1,'CEILING_TO_FEN',1,now())`.execute(
      database,
    );
    await sql`insert into billing.ai_charge_reservations
      (provider_attempt_id,billing_subject_id,wallet_id,operation_id,operation_label_zh,call_type,reserved_fen,pricing_rule_id,status)
      select 'legacy-attempt',billing_subject_id,wallet_id,'legacy-operation','生成讲解','INITIAL_CALL',80,
        '61000000-0000-4000-8000-000000000002','RESERVED' from billing.wallets`.execute(
      database,
    );
    await aiResultDeliveryMigration.up(database);
    const result = await sql<{
      provider_attempt_id: string;
      status: string;
      result_ciphertext: unknown;
    }>`select provider_attempt_id,status,result_ciphertext from ai.result_deliveries`.execute(
      database,
    );
    expect(result.rows).toEqual([
      {
        provider_attempt_id: "legacy-attempt",
        status: "FAILED",
        result_ciphertext: null,
      },
    ]);
    const reservation = await sql<{
      reserved_fen: string;
      status: string;
    }>`select reserved_fen,status from billing.ai_charge_reservations`.execute(
      database,
    );
    expect(Number(reservation.rows[0]!.reserved_fen)).toBe(80);
    expect(reservation.rows[0]!.status).toBe("RESERVED");
  });
  it("creates the CNY ledger, payment, usage charge, and refund authorities", async () => {
    await migrateToLatest(database);
    const result = await sql<{ table_name: string }>`
      select table_name from information_schema.tables
      where table_schema = 'billing' order by table_name
    `.execute(database);
    expect(result.rows.map((row) => row.table_name)).toEqual([
      "ai_charge_reservations",
      "ai_usage_charges",
      "billing_subjects",
      "ledger_entries",
      "minor_recharge_support_cases",
      "payment_provider_events",
      "pricing_rules",
      "recharge_orders",
      "refund_allocations",
      "refund_requests",
      "wallets",
    ]);
  });
  it("creates the account-isolated messaging authority", async () => {
    await migrateToLatest(database);
    const result = await sql<{
      table_name: string;
    }>`select table_name from information_schema.tables where table_schema='messaging' order by table_name`.execute(
      database,
    );
    expect(result.rows.map((row) => row.table_name)).toEqual(["app_messages"]);
  });
  it("creates the confirmed Identity account and session tables", async () => {
    await migrateToLatest(database);

    const result = await sql<{ table_name: string }>`
      select table_name
      from information_schema.tables
      where table_schema = 'identity'
      order by table_name
    `.execute(database);

    expect(result.rows.map((row) => row.table_name)).toEqual([
      "accounts",
      "auth_identities",
      "password_reset_grants",
      "password_reset_requests",
      "user_sessions",
      "verification_challenges",
    ]);
  });

  it("stores only reset-token digests and stable command identities", async () => {
    await migrateToLatest(database);

    const columns = await sql<{ table_name: string; column_name: string }>`
      select table_name, column_name
      from information_schema.columns
      where table_schema = 'identity'
        and table_name in ('password_reset_requests', 'password_reset_grants')
      order by table_name, column_name
    `.execute(database);
    const names = columns.rows.map(
      (row) => `${row.table_name}.${row.column_name}`,
    );

    expect(names).toEqual(
      expect.arrayContaining([
        "password_reset_requests.command_id",
        "password_reset_requests.challenge_id",
        "password_reset_requests.delivery_secret_ciphertext",
        "password_reset_requests.delivery_secret_key_version",
        "password_reset_grants.reset_token_digest",
        "password_reset_grants.reset_token_digest_key_version",
        "password_reset_grants.completion_command_id",
      ]),
    );
    expect(names).not.toContain("password_reset_grants.reset_token");
  });

  it("stores only digests for refresh sessions and enforces revocation shape", async () => {
    await migrateToLatest(database);

    const columns = await sql<{ column_name: string }>`
      select column_name
      from information_schema.columns
      where table_schema = 'identity'
        and table_name = 'user_sessions'
      order by column_name
    `.execute(database);

    expect(columns.rows.map((row) => row.column_name)).toEqual(
      expect.arrayContaining([
        "refresh_token_digest",
        "refresh_token_digest_key_version",
        "access_token_digest",
        "access_token_digest_key_version",
        "access_token_expires_at",
        "device_id_digest",
        "expires_at",
        "revoked_at",
      ]),
    );
    expect(columns.rows.map((row) => row.column_name)).not.toContain(
      "refresh_token",
    );
    expect(columns.rows.map((row) => row.column_name)).not.toContain(
      "access_token",
    );
  });

  it("enforces one active challenge for an email and purpose", async () => {
    await migrateToLatest(database);
    const insert = () =>
      sql`
        insert into identity.verification_challenges (
          challenge_id,
          purpose,
          subject_index,
          subject_index_key_version,
          secret_digest,
          secret_digest_key_version,
          pending_registration_ciphertext,
          payload_algorithm,
          payload_key_version,
          status,
          expires_at
        ) values (
          gen_random_uuid(),
          'EMAIL_VERIFICATION',
          decode('01', 'hex'),
          1,
          decode('02', 'hex'),
          1,
          decode('03', 'hex'),
          'AES-256-GCM',
          1,
          'ACTIVE',
          now() + interval '30 minutes'
        )
      `.execute(database);

    await insert();
    await expect(insert()).rejects.toThrow();
  });

  it("records algorithms and key versions for protected registration values", async () => {
    await migrateToLatest(database);

    const result = await sql<{ column_name: string }>`
      select column_name
      from information_schema.columns
      where table_schema = 'identity'
        and table_name = 'verification_challenges'
        and column_name in (
          'secret_digest_key_version',
          'subject_index_key_version',
          'payload_algorithm',
          'payload_key_version'
        )
      order by column_name
    `.execute(database);

    expect(result.rows.map((row) => row.column_name)).toEqual([
      "payload_algorithm",
      "payload_key_version",
      "secret_digest_key_version",
      "subject_index_key_version",
    ]);
  });

  it("records algorithms and blind-index versions on verified accounts", async () => {
    await migrateToLatest(database);

    const result = await sql<{ table_name: string; column_name: string }>`
      select table_name, column_name
      from information_schema.columns
      where table_schema = 'identity'
        and (
          (table_name = 'accounts' and column_name = 'birth_year_month_algorithm')
          or (
            table_name = 'auth_identities'
            and column_name in ('email_algorithm', 'normalized_email_index_key_version')
          )
        )
      order by table_name, column_name
    `.execute(database);

    expect(result.rows).toEqual([
      {
        table_name: "accounts",
        column_name: "birth_year_month_algorithm",
      },
      { table_name: "auth_identities", column_name: "email_algorithm" },
      {
        table_name: "auth_identities",
        column_name: "normalized_email_index_key_version",
      },
    ]);
  });

  it("creates the platform outbox needed for reliable email dispatch", async () => {
    await migrateToLatest(database);

    const result = await sql<{ table_name: string }>`
      select table_name
      from information_schema.tables
      where table_schema = 'platform'
      order by table_name
    `.execute(database);

    expect(result.rows.map((row) => row.table_name)).toEqual([
      "audit_events",
      "outbox_events",
    ]);
  });

  it("creates encrypted transient safety operations and identified cases", async () => {
    await migrateToLatest(database);

    const result = await sql<{ table_name: string }>`
      select table_name
      from information_schema.tables
      where table_schema = 'safety'
      order by table_name
    `.execute(database);
    expect(result.rows.map((row) => row.table_name)).toEqual([
      "check_operations",
      "deidentified_versions",
      "manual_review_requests",
      "review_decisions",
      "safety_cases",
    ]);

    const columns = await sql<{ column_name: string }>`
      select column_name
      from information_schema.columns
      where table_schema = 'safety' and table_name = 'check_operations'
    `.execute(database);
    expect(columns.rows.map((row) => row.column_name)).toContain(
      "payload_ciphertext",
    );
    expect(columns.rows.map((row) => row.column_name)).not.toContain("text");
  });

  it("creates an isolated admin identity authority with digest-only sessions", async () => {
    await migrateToLatest(database);

    const tables = await sql<{ table_name: string }>`
      select table_name from information_schema.tables
      where table_schema='admin' order by table_name
    `.execute(database);
    expect(tables.rows.map((row) => row.table_name)).toEqual([
      "accounts",
      "sessions",
    ]);

    const columns = await sql<{ table_name: string; column_name: string }>`
      select table_name, column_name from information_schema.columns
      where table_schema='admin' order by table_name, column_name
    `.execute(database);
    const names = columns.rows.map(
      (row) => `${row.table_name}.${row.column_name}`,
    );
    expect(names).toEqual(
      expect.arrayContaining([
        "accounts.email_ciphertext",
        "accounts.email_index",
        "accounts.password_hash",
        "accounts.totp_secret_ciphertext",
        "accounts.last_accepted_totp_counter",
        "accounts.mfa_enrollment_confirmed_at",
        "sessions.bearer_digest",
        "sessions.expires_at",
        "sessions.revoked_at",
      ]),
    );
    expect(names).not.toContain("accounts.email");
    expect(names).not.toContain("accounts.totp_secret");
    expect(names).not.toContain("sessions.bearer");
  });

  it("creates the single authoritative settings snapshot per account", async () => {
    await migrateToLatest(database);

    const result = await sql<{ column_name: string }>`
      select column_name
      from information_schema.columns
      where table_schema = 'settings' and table_name = 'user_settings'
      order by column_name
    `.execute(database);

    expect(result.rows.map((row) => row.column_name)).toEqual(
      expect.arrayContaining([
        "user_id",
        "formality_level",
        "purpose_vocabularies",
        "speech_speed_level",
        "row_version",
      ]),
    );
  });

  it("tracks verification email requests without changing the protected payload", async () => {
    await migrateToLatest(database);

    const result = await sql<{ column_name: string }>`
      select column_name
      from information_schema.columns
      where table_schema = 'identity'
        and table_name = 'verification_challenges'
        and column_name in ('last_email_requested_at', 'email_request_count')
      order by column_name
    `.execute(database);

    expect(result.rows.map((row) => row.column_name)).toEqual([
      "email_request_count",
      "last_email_requested_at",
    ]);
  });

  it("rejects an account without confirmed age eligibility", async () => {
    await migrateToLatest(database);

    await expect(
      sql`
        insert into identity.accounts (
          account_id,
          birth_year_month_ciphertext,
          birth_year_month_key_version,
          age_eligible,
          age_eligibility_confirmed_at,
          email_verified_at
        ) values (
          gen_random_uuid(),
          decode('01', 'hex'),
          1,
          false,
          now(),
          now()
        )
      `.execute(database),
    ).rejects.toThrow();
  });

  it("can roll the initial migration back to an empty schema", async () => {
    await migrateToLatest(database);
    await migrateToEmpty(database);

    const result = await sql<{ schema_name: string }>`
      select schema_name
      from information_schema.schemata
      where schema_name in ('identity', 'platform')
    `.execute(database);

    expect(result.rows).toEqual([]);
  });
});
