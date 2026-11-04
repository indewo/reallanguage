import type { Kysely } from "kysely";
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
}
