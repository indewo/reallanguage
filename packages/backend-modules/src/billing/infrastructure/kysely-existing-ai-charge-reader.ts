import { sql, type Kysely } from "kysely";
import type {
  ExistingAiChargeReader,
  ExistingAiChargeAttempt,
} from "../application/read-existing-ai-charge.js";
export class KyselyExistingAiChargeReader implements ExistingAiChargeReader {
  public constructor(private readonly database: Kysely<unknown>) {}
  public async readOperation(
    accountId: string,
    operationId: string,
  ): Promise<readonly ExistingAiChargeAttempt[]> {
    const result = await sql<{
      provider_attempt_id: string;
      status: ExistingAiChargeAttempt["status"];
      model_source: string | null;
      provider_cost_micro_cny: string | null;
      charged_fen: string | null;
      ledger_amount: string | null;
      ledger_id: string | null;
      usage_operation: string | null;
      usage_subject: string | null;
      usage_wallet: string | null;
      billing_subject_id: string;
      wallet_id: string;
      ledger_subject: string | null;
      ledger_wallet: string | null;
      business_ref_id: string | null;
      business_ref_type: string | null;
      entry_type: string | null;
    }>`select r.provider_attempt_id,r.status,r.billing_subject_id,r.wallet_id,
      u.model_source,u.provider_cost_micro_cny,u.charged_fen,u.operation_id as usage_operation,
      u.billing_subject_id as usage_subject,u.wallet_id as usage_wallet,
      l.ledger_entry_id as ledger_id,l.amount_fen as ledger_amount,
      l.billing_subject_id as ledger_subject,l.wallet_id as ledger_wallet,
      l.business_ref_id,l.business_ref_type,l.entry_type
      from billing.ai_charge_reservations r
      join billing.billing_subjects s on s.billing_subject_id=r.billing_subject_id
      left join billing.ai_usage_charges u on u.provider_attempt_id=r.provider_attempt_id
      left join billing.ledger_entries l on l.ledger_entry_id=u.ledger_entry_id
      where s.user_id=${accountId} and r.operation_id=${operationId}
      order by r.provider_attempt_id`.execute(this.database);
    return result.rows.map((row) => {
      const cost =
        row.provider_cost_micro_cny === null
          ? null
          : Number(row.provider_cost_micro_cny);
      const charged = row.charged_fen === null ? null : Number(row.charged_fen);
      const validSettled =
        row.ledger_id !== null &&
        row.model_source !== null &&
        row.model_source.trim().length > 0 &&
        cost !== null &&
        Number.isSafeInteger(cost) &&
        cost > 0 &&
        charged !== null &&
        Number.isSafeInteger(charged) &&
        charged > 0 &&
        Number(row.ledger_amount) === -charged &&
        row.usage_operation === operationId &&
        row.usage_subject === row.billing_subject_id &&
        row.usage_wallet === row.wallet_id &&
        row.ledger_subject === row.billing_subject_id &&
        row.ledger_wallet === row.wallet_id &&
        row.business_ref_id === row.provider_attempt_id &&
        row.business_ref_type === "AI_PROVIDER_ATTEMPT" &&
        row.entry_type === "AI_CHARGE";
      const status =
        row.status === "SETTLED"
          ? validSettled
            ? "SETTLED"
            : "INVALID"
          : row.model_source !== null
            ? "INVALID"
            : row.status;
      return {
        providerAttemptId: row.provider_attempt_id,
        status,
        modelSource: row.model_source,
        providerCostMicroCny: cost,
      };
    });
  }
}
