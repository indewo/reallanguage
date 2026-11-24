import { sql, type Kysely } from "kysely";
import type {
  BillingChargeCallType,
  BillingSummaryReader,
} from "../application/get-billing-summary.js";

interface BillingSubjectsTable {
  billing_subject_id: string;
  user_id: string | null;
}
interface WalletsTable {
  wallet_id: string;
  billing_subject_id: string;
  posted_balance_fen: string;
  reserved_balance_fen: string;
}
interface UsageChargesTable {
  provider_attempt_id: string;
  billing_subject_id: string;
  charged_fen: string;
  occurred_at: Date;
  model_source: string;
  call_type: BillingChargeCallType;
  operation_label_zh: string;
}
export interface BillingSummaryDatabase {
  "billing.billing_subjects": BillingSubjectsTable;
  "billing.wallets": WalletsTable;
  "billing.ai_usage_charges": UsageChargesTable;
}
export class KyselyBillingSummaryReader implements BillingSummaryReader {
  public constructor(
    private readonly database: Kysely<BillingSummaryDatabase>,
  ) {}
  public async read(accountId: string) {
    const wallet = await this.database
      .selectFrom("billing.billing_subjects as subject")
      .innerJoin(
        "billing.wallets as wallet",
        "wallet.billing_subject_id",
        "subject.billing_subject_id",
      )
      .select([
        "subject.billing_subject_id as subjectId",
        "wallet.posted_balance_fen as posted",
        "wallet.reserved_balance_fen as reserved",
      ])
      .where("subject.user_id", "=", accountId)
      .executeTakeFirst();
    if (!wallet) return null;
    const charges = await this.database
      .selectFrom("billing.ai_usage_charges")
      .selectAll()
      .where("billing_subject_id", "=", wallet.subjectId)
      .orderBy("occurred_at", "desc")
      .limit(500)
      .execute();
    return {
      balanceAmountFen: Number(wallet.posted) - Number(wallet.reserved),
      charges: charges.map((charge) => ({
        chargeId: charge.provider_attempt_id,
        amountFen: Number(charge.charged_fen),
        occurredAt: new Date(charge.occurred_at),
        modelSource: charge.model_source,
        callType: charge.call_type,
        operationLabelZh: charge.operation_label_zh,
      })),
    };
  }
  public async readDaily(accountId: string, timeZone: string) {
    // Validate an IANA zone before using it as a bound SQL value.
    new Intl.DateTimeFormat("en", { timeZone }).format();
    const wallet = await this.database
      .selectFrom("billing.billing_subjects as subject")
      .innerJoin(
        "billing.wallets as wallet",
        "wallet.billing_subject_id",
        "subject.billing_subject_id",
      )
      .select([
        "subject.billing_subject_id as subjectId",
        "wallet.posted_balance_fen as posted",
        "wallet.reserved_balance_fen as reserved",
      ])
      .where("subject.user_id", "=", accountId)
      .executeTakeFirst();
    if (!wallet) return null;
    const day = sql<string>`to_char(occurred_at at time zone ${timeZone}, 'YYYY-MM-DD')`;
    const groups = await this.database
      .selectFrom("billing.ai_usage_charges")
      .select([
        day.as("date"),
        "operation_label_zh as operationLabelZh",
        sql<number>`count(*)::int`.as("count"),
        sql<string>`sum(charged_fen)::text`.as("amountFen"),
      ])
      .where("billing_subject_id", "=", wallet.subjectId)
      .groupBy([sql`1`, "operation_label_zh"])
      .orderBy("date", "desc")
      .orderBy("operation_label_zh", "asc")
      // Limit complete groups, never raw charges before calculating a daily total.
      .limit(500)
      .execute();
    return {
      balanceAmountFen: Number(wallet.posted) - Number(wallet.reserved),
      dailyCharges: groups.map((group) => ({
        ...group,
        amountFen: Number(group.amountFen),
      })),
    };
  }
}
