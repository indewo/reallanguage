import { sql, type Kysely } from "kysely";

import type {
  AiChargeStore,
  AiReservationResult,
  AiSettlementResult,
} from "../application/manage-ai-charge.js";
import type { BillingChargeCallType } from "../application/get-billing-summary.js";
import type { BillingIdentifierFactory } from "./kysely-billing-command-store.js";

interface SubjectTable {
  billing_subject_id: string;
  user_id: string | null;
}
interface WalletTable {
  wallet_id: string;
  billing_subject_id: string;
  posted_balance_fen: string;
  reserved_balance_fen: string;
  row_version: string;
  updated_at: Date;
}
interface PricingRuleTable {
  pricing_rule_id: string;
  multiplier_numerator: number;
  multiplier_denominator: number;
  rounding_mode: string;
  minimum_charge_fen: string;
  effective_at: Date;
  created_at: Date;
}
interface ReservationTable {
  provider_attempt_id: string;
  billing_subject_id: string;
  wallet_id: string;
  operation_id: string;
  operation_label_zh: string;
  call_type: BillingChargeCallType;
  reserved_fen: string;
  pricing_rule_id: string;
  status: AiReservationResult["status"];
  row_version: string;
  created_at: Date;
  settled_at: Date | null;
}
interface LedgerTable {
  ledger_entry_id: string;
  billing_subject_id: string;
  wallet_id: string;
  entry_type: "AI_CHARGE";
  amount_fen: string;
  currency: "CNY";
  business_ref_type: string;
  business_ref_id: string;
  idempotency_key: string;
  posted_at: Date;
}
interface UsageTable {
  provider_attempt_id: string;
  billing_subject_id: string;
  wallet_id: string;
  ledger_entry_id: string;
  operation_id: string;
  operation_label_zh: string;
  model_source: string;
  call_type: BillingChargeCallType;
  provider_cost_micro_cny: string;
  charged_fen: string;
  pricing_rule_id: string;
  occurred_at: Date;
}
export interface AiChargeDatabase {
  "billing.billing_subjects": SubjectTable;
  "billing.wallets": WalletTable;
  "billing.pricing_rules": PricingRuleTable;
  "billing.ai_charge_reservations": ReservationTable;
  "billing.ledger_entries": LedgerTable;
  "billing.ai_usage_charges": UsageTable;
}

export class KyselyAiChargeStore implements AiChargeStore {
  public constructor(
    private readonly database: Kysely<AiChargeDatabase>,
    private readonly identifiers: BillingIdentifierFactory,
    private readonly clock: { now(): Date } = { now: () => new Date() },
  ) {}

  public async reserve(
    input: Parameters<AiChargeStore["reserve"]>[0],
  ): Promise<AiReservationResult | null> {
    const existing = await this.readReservation(input.providerAttemptId);
    if (existing)
      return matchesReservation(existing, input)
        ? {
            status: existing.status,
            reservedFen: Number(existing.reserved_fen),
          }
        : null;
    try {
      return await this.database.transaction().execute(async (transaction) => {
        const owned = await transaction
          .selectFrom("billing.billing_subjects as subject")
          .innerJoin(
            "billing.wallets as wallet",
            "wallet.billing_subject_id",
            "subject.billing_subject_id",
          )
          .select([
            "subject.billing_subject_id as subjectId",
            "wallet.wallet_id as walletId",
          ])
          .where("subject.user_id", "=", input.accountId)
          .forUpdate()
          .executeTakeFirst();
        const pricing = await transaction
          .selectFrom("billing.pricing_rules")
          .select("pricing_rule_id")
          .where("effective_at", "<=", this.clock.now())
          .orderBy("effective_at", "desc")
          .executeTakeFirst();
        if (!owned || !pricing) return null;
        const wallet = await transaction
          .updateTable("billing.wallets")
          .set({
            reserved_balance_fen: sql`reserved_balance_fen + ${input.maximumChargeFen}`,
            updated_at: this.clock.now(),
            row_version: sql`row_version + 1`,
          })
          .where("wallet_id", "=", owned.walletId)
          .where(
            sql<boolean>`posted_balance_fen - reserved_balance_fen >= ${input.maximumChargeFen}`,
          )
          .executeTakeFirst();
        if (Number(wallet.numUpdatedRows) !== 1) return null;
        await transaction
          .insertInto("billing.ai_charge_reservations")
          .values({
            provider_attempt_id: input.providerAttemptId,
            billing_subject_id: owned.subjectId,
            wallet_id: owned.walletId,
            operation_id: input.operationId,
            operation_label_zh: input.operationLabelZh,
            call_type: input.callType,
            reserved_fen: String(input.maximumChargeFen),
            pricing_rule_id: pricing.pricing_rule_id,
            status: "RESERVED",
            row_version: "0",
            created_at: this.clock.now(),
            settled_at: null,
          })
          .execute();
        return { status: "RESERVED", reservedFen: input.maximumChargeFen };
      });
    } catch {
      const raced = await this.readReservation(input.providerAttemptId);
      return raced && matchesReservation(raced, input)
        ? { status: raced.status, reservedFen: Number(raced.reserved_fen) }
        : null;
    }
  }

  public async settle(
    input: Parameters<AiChargeStore["settle"]>[0],
  ): Promise<AiSettlementResult | null> {
    return this.database.transaction().execute(async (transaction) => {
      const reservation = await transaction
        .selectFrom("billing.ai_charge_reservations as reservation")
        .innerJoin(
          "billing.pricing_rules as pricing",
          "pricing.pricing_rule_id",
          "reservation.pricing_rule_id",
        )
        .selectAll("reservation")
        .select([
          "pricing.multiplier_numerator",
          "pricing.multiplier_denominator",
          "pricing.rounding_mode",
          "pricing.minimum_charge_fen",
        ])
        .where("reservation.provider_attempt_id", "=", input.providerAttemptId)
        .forUpdate()
        .executeTakeFirst();
      if (!reservation || reservation.status === "RELEASED") return null;
      if (reservation.status === "RECONCILIATION_REQUIRED")
        return { status: "RECONCILIATION_REQUIRED", chargedFen: null };
      if (reservation.status === "SETTLED") {
        const usage = await transaction
          .selectFrom("billing.ai_usage_charges")
          .select("charged_fen")
          .where("provider_attempt_id", "=", input.providerAttemptId)
          .executeTakeFirstOrThrow();
        return { status: "SETTLED", chargedFen: Number(usage.charged_fen) };
      }
      const chargedFen = calculateChargeFen({
        providerCostMicroCny: input.providerCostMicroCny,
        numerator: reservation.multiplier_numerator,
        denominator: reservation.multiplier_denominator,
        roundingMode: reservation.rounding_mode,
        minimumChargeFen: Number(reservation.minimum_charge_fen),
      });
      if (chargedFen > Number(reservation.reserved_fen)) {
        await transaction
          .updateTable("billing.ai_charge_reservations")
          .set({
            status: "RECONCILIATION_REQUIRED",
            row_version: sql`row_version + 1`,
          })
          .where("provider_attempt_id", "=", input.providerAttemptId)
          .execute();
        return { status: "RECONCILIATION_REQUIRED", chargedFen: null };
      }
      const ledgerEntryId = this.identifiers.next();
      await transaction
        .insertInto("billing.ledger_entries")
        .values({
          ledger_entry_id: ledgerEntryId,
          billing_subject_id: reservation.billing_subject_id,
          wallet_id: reservation.wallet_id,
          entry_type: "AI_CHARGE",
          amount_fen: String(-chargedFen),
          currency: "CNY",
          business_ref_type: "AI_PROVIDER_ATTEMPT",
          business_ref_id: reservation.provider_attempt_id,
          idempotency_key: `ai-charge:${reservation.provider_attempt_id}`,
          posted_at: this.clock.now(),
        })
        .execute();
      await transaction
        .insertInto("billing.ai_usage_charges")
        .values({
          provider_attempt_id: reservation.provider_attempt_id,
          billing_subject_id: reservation.billing_subject_id,
          wallet_id: reservation.wallet_id,
          ledger_entry_id: ledgerEntryId,
          operation_id: reservation.operation_id,
          operation_label_zh: reservation.operation_label_zh,
          model_source: input.modelSource,
          call_type: reservation.call_type,
          provider_cost_micro_cny: String(input.providerCostMicroCny),
          charged_fen: String(chargedFen),
          pricing_rule_id: reservation.pricing_rule_id,
          occurred_at: this.clock.now(),
        })
        .execute();
      const wallet = await transaction
        .updateTable("billing.wallets")
        .set({
          posted_balance_fen: sql`posted_balance_fen - ${chargedFen}`,
          reserved_balance_fen: sql`reserved_balance_fen - ${Number(reservation.reserved_fen)}`,
          updated_at: this.clock.now(),
          row_version: sql`row_version + 1`,
        })
        .where("wallet_id", "=", reservation.wallet_id)
        .where("posted_balance_fen", ">=", String(chargedFen))
        .where("reserved_balance_fen", ">=", reservation.reserved_fen)
        .executeTakeFirst();
      if (Number(wallet.numUpdatedRows) !== 1)
        throw new Error("AI charge wallet invariant failed");
      await transaction
        .updateTable("billing.ai_charge_reservations")
        .set({
          status: "SETTLED",
          settled_at: this.clock.now(),
          row_version: sql`row_version + 1`,
        })
        .where("provider_attempt_id", "=", input.providerAttemptId)
        .execute();
      return { status: "SETTLED", chargedFen };
    });
  }

  public async release(providerAttemptId: string): Promise<boolean> {
    return this.database.transaction().execute(async (transaction) => {
      const reservation = await transaction
        .selectFrom("billing.ai_charge_reservations")
        .selectAll()
        .where("provider_attempt_id", "=", providerAttemptId)
        .forUpdate()
        .executeTakeFirst();
      if (!reservation) return false;
      if (reservation.status === "RELEASED") return true;
      if (reservation.status !== "RESERVED") return false;
      await transaction
        .updateTable("billing.wallets")
        .set({
          reserved_balance_fen: sql`reserved_balance_fen - ${Number(reservation.reserved_fen)}`,
          updated_at: this.clock.now(),
          row_version: sql`row_version + 1`,
        })
        .where("wallet_id", "=", reservation.wallet_id)
        .where("reserved_balance_fen", ">=", reservation.reserved_fen)
        .executeTakeFirstOrThrow();
      await transaction
        .updateTable("billing.ai_charge_reservations")
        .set({
          status: "RELEASED",
          settled_at: this.clock.now(),
          row_version: sql`row_version + 1`,
        })
        .where("provider_attempt_id", "=", providerAttemptId)
        .execute();
      return true;
    });
  }

  public async requireReconciliation(
    providerAttemptId: string,
  ): Promise<boolean> {
    const existing = await this.readReservation(providerAttemptId);
    if (!existing) return false;
    if (existing.status === "RECONCILIATION_REQUIRED") return true;
    if (existing.status !== "RESERVED") return false;
    const updated = await this.database
      .updateTable("billing.ai_charge_reservations")
      .set({
        status: "RECONCILIATION_REQUIRED",
        row_version: sql`row_version + 1`,
      })
      .where("provider_attempt_id", "=", providerAttemptId)
      .where("status", "=", "RESERVED")
      .executeTakeFirst();
    return Number(updated.numUpdatedRows) === 1;
  }

  private readReservation(providerAttemptId: string) {
    return this.database
      .selectFrom("billing.ai_charge_reservations")
      .selectAll()
      .where("provider_attempt_id", "=", providerAttemptId)
      .executeTakeFirst();
  }
}

const matchesReservation = (
  existing: ReservationTable,
  input: Parameters<AiChargeStore["reserve"]>[0],
) =>
  existing.operation_id === input.operationId &&
  existing.operation_label_zh === input.operationLabelZh &&
  existing.call_type === input.callType &&
  Number(existing.reserved_fen) === input.maximumChargeFen;

const calculateChargeFen = (input: {
  providerCostMicroCny: number;
  numerator: number;
  denominator: number;
  roundingMode: string;
  minimumChargeFen: number;
}) => {
  if (input.roundingMode !== "CEILING_TO_FEN")
    throw new Error("Unsupported pricing rounding mode");
  const microFen = 10_000n;
  const scaled = BigInt(input.providerCostMicroCny) * BigInt(input.numerator);
  const divisor = BigInt(input.denominator) * microFen;
  const rounded = Number((scaled + divisor - 1n) / divisor);
  return Math.max(input.minimumChargeFen, rounded);
};
