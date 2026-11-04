import { sql } from "kysely";
import type { Migration } from "kysely/migration";

export const aiResultDeliveryMigration: Migration = {
  async up(database) {
    await sql`create schema if not exists ai`.execute(database);
    await sql`create table ai.result_deliveries (
      user_id uuid not null references identity.accounts(account_id) on delete cascade,
      task text not null,
      command_id text not null,
      provider_attempt_id text not null unique,
      request_digest text not null,
      status text not null check (status in ('PROCESSING','COMPLETED','FAILED','EXPIRED')),
      result_ciphertext bytea,
      algorithm text,
      key_version integer,
      created_at timestamptz not null,
      expires_at timestamptz not null,
      primary key (user_id, task, command_id),
      check (expires_at <= created_at + interval '24 hours'),
      check (expires_at > created_at)
    )`.execute(database);
    await sql`create index ai_result_delivery_expiry on ai.result_deliveries(expires_at)
      where status in ('PROCESSING','COMPLETED')`.execute(database);
    // Old supplier attempts have no trustworthy cached output. Never replay them as new work.
    await sql`insert into ai.result_deliveries
      (user_id,task,command_id,provider_attempt_id,request_digest,status,created_at,expires_at)
      select subject.user_id,'LEGACY_UNAVAILABLE',reservation.provider_attempt_id,
        reservation.provider_attempt_id,'legacy-unavailable','FAILED',now(),now()+interval '24 hours'
      from billing.ai_charge_reservations reservation
      join billing.billing_subjects subject on subject.billing_subject_id=reservation.billing_subject_id
      where subject.user_id is not null`.execute(database);
  },
  async down(database) {
    await sql`drop table ai.result_deliveries`.execute(database);
    await sql`drop schema ai`.execute(database);
  },
};
