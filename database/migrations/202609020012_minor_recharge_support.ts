import { sql } from "kysely";
import type { Migration } from "kysely/migration";

export const minorRechargeSupportMigration: Migration = {
  async up(database) {
    await sql
      .raw(
        `create table billing.minor_recharge_support_cases (
      support_case_id uuid primary key,
      billing_subject_id uuid not null references billing.billing_subjects(billing_subject_id),
      command_id varchar(128) not null,
      status varchar(32) not null check (status in ('REQUESTED', 'REVIEWING', 'RESOLVED', 'REJECTED')),
      row_version bigint not null default 0 check (row_version >= 0),
      created_at timestamptz not null default now(),
      updated_at timestamptz not null default now(),
      unique (billing_subject_id, command_id)
    )`,
      )
      .execute(database);
    await sql
      .raw(
        `create unique index minor_recharge_one_active
      on billing.minor_recharge_support_cases (billing_subject_id)
      where status in ('REQUESTED', 'REVIEWING')`,
      )
      .execute(database);
  },
  async down(database) {
    await sql
      .raw("drop table if exists billing.minor_recharge_support_cases")
      .execute(database);
  },
};
