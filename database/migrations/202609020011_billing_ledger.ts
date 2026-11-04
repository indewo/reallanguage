import { sql } from "kysely";
import type { Migration } from "kysely/migration";

export const billingLedgerMigration: Migration = {
  async up(database) {
    const statements = [
      `create schema if not exists billing`,
      `create table billing.billing_subjects (
        billing_subject_id uuid primary key,
        user_id uuid unique references identity.accounts(account_id) on delete set null,
        anonymized_at timestamptz,
        row_version bigint not null default 0 check (row_version >= 0),
        created_at timestamptz not null default now(),
        updated_at timestamptz not null default now(),
        check ((user_id is not null and anonymized_at is null) or (user_id is null and anonymized_at is not null))
      )`,
      `create table billing.wallets (
        wallet_id uuid primary key,
        billing_subject_id uuid not null unique references billing.billing_subjects(billing_subject_id),
        currency varchar(3) not null default 'CNY' check (currency = 'CNY'),
        posted_balance_fen bigint not null default 0 check (posted_balance_fen >= 0),
        reserved_balance_fen bigint not null default 0 check (reserved_balance_fen >= 0 and reserved_balance_fen <= posted_balance_fen),
        row_version bigint not null default 0 check (row_version >= 0),
        created_at timestamptz not null default now(),
        updated_at timestamptz not null default now()
      )`,
      `create table billing.pricing_rules (
        pricing_rule_id uuid primary key,
        multiplier_numerator integer not null check (multiplier_numerator > 0),
        multiplier_denominator integer not null check (multiplier_denominator > 0),
        rounding_mode varchar(32) not null,
        minimum_charge_fen bigint not null default 0 check (minimum_charge_fen >= 0),
        effective_at timestamptz not null,
        created_at timestamptz not null default now()
      )`,
      `create table billing.ai_charge_reservations (
        provider_attempt_id varchar(128) primary key,
        billing_subject_id uuid not null references billing.billing_subjects(billing_subject_id),
        wallet_id uuid not null references billing.wallets(wallet_id),
        operation_id varchar(128) not null,
        operation_label_zh varchar(200) not null,
        call_type varchar(32) not null check (call_type in ('INITIAL_CALL', 'AUTOMATIC_RETRY', 'USER_RETRY', 'PROVIDER_SWITCH')),
        reserved_fen bigint not null check (reserved_fen > 0),
        pricing_rule_id uuid not null references billing.pricing_rules(pricing_rule_id),
        status varchar(32) not null check (status in ('RESERVED', 'SETTLED', 'RELEASED', 'RECONCILIATION_REQUIRED')),
        row_version bigint not null default 0 check (row_version >= 0),
        created_at timestamptz not null default now(),
        settled_at timestamptz
      )`,
      `create index ai_charge_reservations_subject_created on billing.ai_charge_reservations (billing_subject_id, created_at desc)`,
      `create table billing.ledger_entries (
        ledger_entry_id uuid primary key,
        billing_subject_id uuid not null references billing.billing_subjects(billing_subject_id),
        wallet_id uuid not null references billing.wallets(wallet_id),
        entry_type varchar(32) not null check (entry_type in ('RECHARGE', 'AI_CHARGE', 'REFUND', 'GRANT', 'ADJUSTMENT')),
        amount_fen bigint not null check (amount_fen <> 0),
        currency varchar(3) not null default 'CNY' check (currency = 'CNY'),
        business_ref_type varchar(64) not null,
        business_ref_id varchar(128) not null,
        idempotency_key varchar(128) not null,
        posted_at timestamptz not null default now(),
        unique (wallet_id, entry_type, business_ref_type, business_ref_id),
        unique (billing_subject_id, idempotency_key)
      )`,
      `create table billing.ai_usage_charges (
        provider_attempt_id varchar(128) primary key,
        billing_subject_id uuid not null references billing.billing_subjects(billing_subject_id),
        wallet_id uuid not null references billing.wallets(wallet_id),
        ledger_entry_id uuid not null unique references billing.ledger_entries(ledger_entry_id),
        operation_id varchar(128) not null,
        operation_label_zh varchar(200) not null,
        model_source varchar(128) not null,
        call_type varchar(32) not null check (call_type in ('INITIAL_CALL', 'AUTOMATIC_RETRY', 'USER_RETRY', 'PROVIDER_SWITCH')),
        provider_cost_micro_cny bigint not null check (provider_cost_micro_cny >= 0),
        charged_fen bigint not null check (charged_fen > 0),
        pricing_rule_id uuid not null references billing.pricing_rules(pricing_rule_id),
        occurred_at timestamptz not null default now()
      )`,
      `create index ai_usage_charges_subject_occurred on billing.ai_usage_charges (billing_subject_id, occurred_at desc)`,
      `create table billing.recharge_orders (
        order_id uuid primary key,
        billing_subject_id uuid not null references billing.billing_subjects(billing_subject_id),
        wallet_id uuid not null references billing.wallets(wallet_id),
        command_id varchar(128) not null,
        payment_method varchar(32) not null check (payment_method in ('WECHAT_PAY', 'ALIPAY')),
        amount_fen bigint not null check (amount_fen > 0),
        currency varchar(3) not null default 'CNY' check (currency = 'CNY'),
        status varchar(32) not null check (status in ('CREATED', 'PENDING_PAYMENT', 'IN_PROGRESS', 'PAID', 'FAILED', 'CANCELLED')),
        provider_order_id varchar(128), payment_launch text,
        row_version bigint not null default 0 check (row_version >= 0),
        created_at timestamptz not null default now(), updated_at timestamptz not null default now(),
        unique (billing_subject_id, command_id), unique (payment_method, provider_order_id)
      )`,
      `create table billing.payment_provider_events (
        payment_method varchar(32) not null, provider_event_id varchar(128) not null,
        order_id uuid not null references billing.recharge_orders(order_id),
        received_at timestamptz not null default now(), primary key (payment_method, provider_event_id)
      )`,
      `create table billing.refund_requests (
        refund_request_id uuid primary key,
        billing_subject_id uuid not null references billing.billing_subjects(billing_subject_id),
        wallet_id uuid not null references billing.wallets(wallet_id), command_id varchar(128) not null,
        amount_fen bigint not null check (amount_fen > 0),
        currency varchar(3) not null default 'CNY' check (currency = 'CNY'),
        refund_method_label_zh varchar(200) not null,
        status varchar(32) not null check (status in ('REQUESTED', 'REVIEWING', 'APPROVED', 'REFUNDED', 'REJECTED', 'FAILED')),
        reviewed_by varchar(128),
        row_version bigint not null default 0 check (row_version >= 0),
        created_at timestamptz not null default now(), updated_at timestamptz not null default now(),
        unique (billing_subject_id, command_id)
      )`,
      `create unique index refund_requests_one_active on billing.refund_requests (billing_subject_id)
        where status in ('REQUESTED', 'REVIEWING', 'APPROVED')`,
      `create table billing.refund_allocations (
        refund_allocation_id uuid primary key,
        refund_request_id uuid not null references billing.refund_requests(refund_request_id),
        order_id uuid not null references billing.recharge_orders(order_id),
        amount_fen bigint not null check (amount_fen > 0),
        status varchar(32) not null default 'PENDING' check (status in ('PENDING', 'REFUNDED')),
        provider_refund_id varchar(128),
        refunded_at timestamptz,
        created_at timestamptz not null default now(),
        unique (refund_request_id, order_id),
        unique (provider_refund_id)
      )`,
      `create function billing.create_wallet_for_account() returns trigger language plpgsql as $$
      declare subject_id uuid := gen_random_uuid(); begin
        insert into billing.billing_subjects (billing_subject_id, user_id) values (subject_id, new.account_id);
        insert into billing.wallets (wallet_id, billing_subject_id) values (gen_random_uuid(), subject_id);
        return new; end $$`,
      `create trigger identity_account_billing_wallet after insert on identity.accounts for each row execute function billing.create_wallet_for_account()`,
      `insert into billing.billing_subjects (billing_subject_id, user_id) select gen_random_uuid(), account_id from identity.accounts on conflict (user_id) do nothing`,
      `insert into billing.wallets (wallet_id, billing_subject_id) select gen_random_uuid(), billing_subject_id from billing.billing_subjects on conflict (billing_subject_id) do nothing`,
      `create function billing.anonymize_subject_before_account_delete() returns trigger language plpgsql as $$
      begin update billing.billing_subjects set user_id = null, anonymized_at = now(), updated_at = now(), row_version = row_version + 1 where user_id = old.account_id; return old; end $$`,
      `create trigger identity_account_billing_anonymize before delete on identity.accounts for each row execute function billing.anonymize_subject_before_account_delete()`,
    ];
    for (const statement of statements)
      await sql.raw(statement).execute(database);
  },

  async down(database) {
    const statements = [
      `drop trigger if exists identity_account_billing_anonymize on identity.accounts`,
      `drop function if exists billing.anonymize_subject_before_account_delete()`,
      `drop trigger if exists identity_account_billing_wallet on identity.accounts`,
      `drop function if exists billing.create_wallet_for_account()`,
      `drop schema if exists billing cascade`,
    ];
    for (const statement of statements)
      await sql.raw(statement).execute(database);
  },
};
