import { sql, type Kysely } from "kysely";
import type {
  PreparedRechargeOrder,
  RechargeOrderSnapshot,
  RechargeOrderStatus,
  RechargeOrderStore,
} from "../application/manage-recharge.js";
import type {
  RefundResult,
  RefundSnapshot,
  RefundStatus,
  RefundStore,
} from "../application/manage-refund.js";
import type {
  PaymentEvent,
  PaymentSettlementStore,
} from "../application/apply-payment-event.js";
import type { PendingPaymentStore } from "../application/reconcile-payments.js";
import type {
  MinorRechargeSupportStatus,
  MinorRechargeSupportStore,
} from "../application/request-minor-recharge-support.js";
import type {
  ApprovedRefundQueue,
  ApprovedRefund,
  RefundExecutionStore,
  RefundReviewDecision,
} from "../application/process-refund.js";

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
interface RechargeTable {
  order_id: string;
  billing_subject_id: string;
  wallet_id: string;
  command_id: string;
  payment_method: "WECHAT_PAY" | "ALIPAY";
  amount_fen: string;
  currency: "CNY";
  status: RechargeOrderStatus;
  provider_order_id: string | null;
  payment_launch: string | null;
  row_version: string;
  created_at: Date;
  updated_at: Date;
}
interface RefundTable {
  refund_request_id: string;
  billing_subject_id: string;
  wallet_id: string;
  command_id: string;
  amount_fen: string;
  currency: "CNY";
  refund_method_label_zh: string;
  status: RefundStatus;
  reviewed_by: string | null;
  row_version: string;
  created_at: Date;
  updated_at: Date;
}
interface RefundAllocationTable {
  refund_allocation_id: string;
  refund_request_id: string;
  order_id: string;
  amount_fen: string;
  status: "PENDING" | "REFUNDED";
  provider_refund_id: string | null;
  refunded_at: Date | null;
  created_at: Date;
}
interface PaymentEventTable {
  payment_method: "WECHAT_PAY" | "ALIPAY";
  provider_event_id: string;
  order_id: string;
  received_at: Date;
}
interface LedgerTable {
  ledger_entry_id: string;
  billing_subject_id: string;
  wallet_id: string;
  entry_type: "RECHARGE" | "AI_CHARGE" | "REFUND" | "GRANT" | "ADJUSTMENT";
  amount_fen: string;
  currency: "CNY";
  business_ref_type: string;
  business_ref_id: string;
  idempotency_key: string;
  posted_at: Date;
}
interface MinorRechargeSupportTable {
  support_case_id: string;
  billing_subject_id: string;
  command_id: string;
  status: MinorRechargeSupportStatus;
  row_version: string;
  created_at: Date;
  updated_at: Date;
}
export interface BillingCommandDatabase {
  "billing.billing_subjects": SubjectTable;
  "billing.wallets": WalletTable;
  "billing.recharge_orders": RechargeTable;
  "billing.refund_requests": RefundTable;
  "billing.refund_allocations": RefundAllocationTable;
  "billing.payment_provider_events": PaymentEventTable;
  "billing.ledger_entries": LedgerTable;
  "billing.minor_recharge_support_cases": MinorRechargeSupportTable;
}
export interface BillingIdentifierFactory {
  next(): string;
}

export class KyselyBillingCommandStore
  implements
    RechargeOrderStore,
    RefundStore,
    PaymentSettlementStore,
    PendingPaymentStore,
    MinorRechargeSupportStore,
    RefundExecutionStore,
    ApprovedRefundQueue
{
  public constructor(
    private readonly database: Kysely<BillingCommandDatabase>,
    private readonly identifiers: BillingIdentifierFactory,
  ) {}

  public async read(
    accountId: string,
    orderId: string,
  ): Promise<RechargeOrderSnapshot | null>;
  public async read(accountId: string): Promise<RefundSnapshot | null>;
  public async read(
    accountId: string,
    orderId?: string,
  ): Promise<RechargeOrderSnapshot | RefundSnapshot | null> {
    if (!orderId) return this.readRefund(accountId);
    const row = await this.database
      .selectFrom("billing.recharge_orders as order")
      .innerJoin(
        "billing.billing_subjects as subject",
        "subject.billing_subject_id",
        "order.billing_subject_id",
      )
      .select(["order.order_id", "order.status", "order.payment_launch"])
      .where("subject.user_id", "=", accountId)
      .where("order.order_id", "=", orderId)
      .executeTakeFirst();
    return row
      ? {
          orderId: row.order_id,
          status: row.status,
          paymentLaunch: row.payment_launch,
        }
      : null;
  }

  public async prepare(
    input: Parameters<RechargeOrderStore["prepare"]>[0],
  ): Promise<PreparedRechargeOrder | null>;
  public async prepare(requestId: string): Promise<ApprovedRefund | null>;
  public async prepare(
    input: Parameters<RechargeOrderStore["prepare"]>[0] | string,
  ): Promise<PreparedRechargeOrder | ApprovedRefund | null> {
    if (typeof input === "string") return this.prepareRefund(input);
    const owned = await this.ownedWallet(input.accountId);
    if (!owned) return null;
    const existing = await this.database
      .selectFrom("billing.recharge_orders")
      .selectAll()
      .where("billing_subject_id", "=", owned.subjectId)
      .where("command_id", "=", input.commandId)
      .executeTakeFirst();
    if (existing)
      return Number(existing.amount_fen) === input.amountFen &&
        existing.payment_method === input.paymentMethod
        ? toPrepared(existing, true)
        : null;
    try {
      const created = await this.database
        .insertInto("billing.recharge_orders")
        .values({
          order_id: this.identifiers.next(),
          billing_subject_id: owned.subjectId,
          wallet_id: owned.walletId,
          command_id: input.commandId,
          payment_method: input.paymentMethod,
          amount_fen: String(input.amountFen),
          currency: "CNY",
          status: "CREATED",
          provider_order_id: null,
          payment_launch: null,
          row_version: "0",
          created_at: new Date(),
          updated_at: new Date(),
        })
        .returningAll()
        .executeTakeFirstOrThrow();
      return toPrepared(created, false);
    } catch {
      const raced = await this.database
        .selectFrom("billing.recharge_orders")
        .selectAll()
        .where("billing_subject_id", "=", owned.subjectId)
        .where("command_id", "=", input.commandId)
        .executeTakeFirst();
      return raced &&
        Number(raced.amount_fen) === input.amountFen &&
        raced.payment_method === input.paymentMethod
        ? toPrepared(raced, true)
        : null;
    }
  }

  public async markPending(
    input: Parameters<RechargeOrderStore["markPending"]>[0],
  ): Promise<RechargeOrderSnapshot | null> {
    const row = await this.database
      .updateTable("billing.recharge_orders")
      .set({
        provider_order_id: input.providerOrderId,
        payment_launch: input.paymentLaunch,
        status: "PENDING_PAYMENT",
        updated_at: new Date(),
        row_version: sql`row_version + 1`,
      })
      .where("order_id", "=", input.orderId)
      .where("status", "=", "CREATED")
      .returningAll()
      .executeTakeFirst();
    return row ? toOrder(row) : null;
  }

  public async markFailed(orderId: string): Promise<void> {
    await this.database
      .updateTable("billing.recharge_orders")
      .set({
        status: "FAILED",
        updated_at: new Date(),
        row_version: sql`row_version + 1`,
      })
      .where("order_id", "=", orderId)
      .where("status", "=", "CREATED")
      .execute();
  }

  public async apply(event: PaymentEvent): Promise<boolean> {
    return this.database.transaction().execute(async (transaction) => {
      const replay = await transaction
        .selectFrom("billing.payment_provider_events")
        .select("order_id")
        .where("payment_method", "=", event.paymentMethod)
        .where("provider_event_id", "=", event.providerEventId)
        .executeTakeFirst();
      if (replay) return replay.order_id === event.merchantOrderId;
      const order = await transaction
        .selectFrom("billing.recharge_orders")
        .selectAll()
        .where("order_id", "=", event.merchantOrderId)
        .forUpdate()
        .executeTakeFirst();
      if (
        !order ||
        order.provider_order_id !== event.providerOrderId ||
        order.payment_method !== event.paymentMethod ||
        Number(order.amount_fen) !== event.amountFen ||
        order.currency !== event.currency
      )
        return false;
      if (
        (order.status === "FAILED" || order.status === "CANCELLED") &&
        event.status !== order.status
      )
        return false;
      await transaction
        .insertInto("billing.payment_provider_events")
        .values({
          payment_method: event.paymentMethod,
          provider_event_id: event.providerEventId,
          order_id: order.order_id,
          received_at: new Date(),
        })
        .execute();
      if (event.status === "PAID" && order.status !== "PAID") {
        await transaction
          .insertInto("billing.ledger_entries")
          .values({
            ledger_entry_id: this.identifiers.next(),
            billing_subject_id: order.billing_subject_id,
            wallet_id: order.wallet_id,
            entry_type: "RECHARGE",
            amount_fen: String(event.amountFen),
            currency: "CNY",
            business_ref_type: "RECHARGE_ORDER",
            business_ref_id: order.order_id,
            idempotency_key: `payment:${event.paymentMethod}:${event.providerOrderId}`,
            posted_at: new Date(),
          })
          .execute();
        await transaction
          .updateTable("billing.wallets")
          .set({
            posted_balance_fen: sql`posted_balance_fen + ${event.amountFen}`,
            updated_at: new Date(),
            row_version: sql`row_version + 1`,
          })
          .where("wallet_id", "=", order.wallet_id)
          .executeTakeFirstOrThrow();
      }
      if (order.status !== event.status)
        await transaction
          .updateTable("billing.recharge_orders")
          .set({
            status: event.status,
            updated_at: new Date(),
            row_version: sql`row_version + 1`,
          })
          .where("order_id", "=", order.order_id)
          .execute();
      return true;
    });
  }

  public async listPendingPayments(limit: number) {
    const rows = await this.database
      .selectFrom("billing.recharge_orders")
      .select(["order_id", "provider_order_id", "payment_method", "amount_fen"])
      .where("status", "in", ["PENDING_PAYMENT", "IN_PROGRESS"])
      .where("provider_order_id", "is not", null)
      .orderBy("updated_at", "asc")
      .limit(limit)
      .execute();
    return rows.map((row) => ({
      merchantOrderId: row.order_id,
      providerOrderId: row.provider_order_id!,
      paymentMethod: row.payment_method,
      amountFen: Number(row.amount_fen),
    }));
  }

  public async requestMinorRechargeSupport(input: {
    accountId: string;
    commandId: string;
  }) {
    const owned = await this.ownedWallet(input.accountId);
    if (!owned) return null;
    const existing = await this.database
      .selectFrom("billing.minor_recharge_support_cases")
      .select(["support_case_id", "status"])
      .where("billing_subject_id", "=", owned.subjectId)
      .where("command_id", "=", input.commandId)
      .executeTakeFirst();
    if (existing)
      return {
        supportCaseId: existing.support_case_id,
        status: existing.status,
      };
    try {
      const created = await this.database
        .insertInto("billing.minor_recharge_support_cases")
        .values({
          support_case_id: this.identifiers.next(),
          billing_subject_id: owned.subjectId,
          command_id: input.commandId,
          status: "REQUESTED",
          row_version: "0",
          created_at: new Date(),
          updated_at: new Date(),
        })
        .returning(["support_case_id", "status"])
        .executeTakeFirstOrThrow();
      return {
        supportCaseId: created.support_case_id,
        status: created.status,
      };
    } catch {
      const active = await this.database
        .selectFrom("billing.minor_recharge_support_cases")
        .select(["support_case_id", "status"])
        .where("billing_subject_id", "=", owned.subjectId)
        .where("status", "in", ["REQUESTED", "REVIEWING"])
        .executeTakeFirst();
      return active
        ? { supportCaseId: active.support_case_id, status: active.status }
        : null;
    }
  }

  private async readRefund(accountId: string): Promise<RefundSnapshot | null> {
    const owned = await this.ownedWallet(accountId);
    if (!owned) return null;
    const [recharge, activeRefunds, latestRequest, latestPaid] =
      await Promise.all([
        this.database
          .selectFrom("billing.recharge_orders")
          .select((expression) =>
            expression.fn.sum<string>("amount_fen").as("total"),
          )
          .where("billing_subject_id", "=", owned.subjectId)
          .where("status", "=", "PAID")
          .executeTakeFirst(),
        this.database
          .selectFrom("billing.refund_requests")
          .select((expression) =>
            expression.fn.sum<string>("amount_fen").as("total"),
          )
          .where("billing_subject_id", "=", owned.subjectId)
          .where("status", "in", [
            "REQUESTED",
            "REVIEWING",
            "APPROVED",
            "REFUNDED",
          ])
          .executeTakeFirst(),
        this.database
          .selectFrom("billing.refund_requests")
          .selectAll()
          .where("billing_subject_id", "=", owned.subjectId)
          .orderBy("created_at", "desc")
          .executeTakeFirst(),
        this.database
          .selectFrom("billing.recharge_orders")
          .select("payment_method")
          .where("billing_subject_id", "=", owned.subjectId)
          .where("status", "=", "PAID")
          .orderBy("updated_at", "desc")
          .executeTakeFirst(),
      ]);
    const available = Number(owned.posted) - Number(owned.reserved);
    const refundable = Math.max(
      0,
      Math.min(
        available,
        Number(recharge?.total ?? 0) - Number(activeRefunds?.total ?? 0),
      ),
    );
    return {
      refundableAmountFen: refundable,
      refundMethodLabelZh:
        latestPaid?.payment_method === "WECHAT_PAY"
          ? "原路退回至微信支付"
          : latestPaid?.payment_method === "ALIPAY"
            ? "原路退回至支付宝"
            : "原路退回",
      requestId: latestRequest?.refund_request_id ?? null,
      requestStatus: latestRequest?.status ?? null,
    };
  }

  public async request(
    input: Parameters<RefundStore["request"]>[0],
  ): Promise<RefundResult | null> {
    const owned = await this.ownedWallet(input.accountId);
    if (!owned) return null;
    const existing = await this.database
      .selectFrom("billing.refund_requests")
      .selectAll()
      .where("billing_subject_id", "=", owned.subjectId)
      .where("command_id", "=", input.commandId)
      .executeTakeFirst();
    if (existing)
      return Number(existing.amount_fen) === input.amountFen
        ? { requestId: existing.refund_request_id, status: existing.status }
        : null;
    const created = await this.database
      .insertInto("billing.refund_requests")
      .values({
        refund_request_id: this.identifiers.next(),
        billing_subject_id: owned.subjectId,
        wallet_id: owned.walletId,
        command_id: input.commandId,
        amount_fen: String(input.amountFen),
        currency: "CNY",
        refund_method_label_zh: "原路退回",
        status: "REQUESTED",
        reviewed_by: null,
        row_version: "0",
        created_at: new Date(),
        updated_at: new Date(),
      })
      .returningAll()
      .executeTakeFirst();
    return created
      ? { requestId: created.refund_request_id, status: created.status }
      : null;
  }

  public async review(input: {
    requestId: string;
    decision: RefundReviewDecision;
    reviewerId: string;
  }): Promise<RefundResult | null> {
    return this.database.transaction().execute(async (transaction) => {
      const request = await transaction
        .selectFrom("billing.refund_requests")
        .selectAll()
        .where("refund_request_id", "=", input.requestId)
        .forUpdate()
        .executeTakeFirst();
      if (!request) return null;
      const target = input.decision === "APPROVE" ? "APPROVED" : "REJECTED";
      if (request.status === target)
        return { requestId: request.refund_request_id, status: request.status };
      if (request.status !== "REQUESTED" && request.status !== "REVIEWING")
        return null;
      if (input.decision === "REJECT") {
        await transaction
          .updateTable("billing.refund_requests")
          .set({
            status: "REJECTED",
            reviewed_by: input.reviewerId,
            updated_at: new Date(),
            row_version: sql`row_version + 1`,
          })
          .where("refund_request_id", "=", request.refund_request_id)
          .execute();
        return { requestId: request.refund_request_id, status: "REJECTED" };
      }
      const reserved = await transaction
        .updateTable("billing.wallets")
        .set({
          reserved_balance_fen: sql`reserved_balance_fen + ${Number(request.amount_fen)}`,
          updated_at: new Date(),
          row_version: sql`row_version + 1`,
        })
        .where("wallet_id", "=", request.wallet_id)
        .where(
          sql<boolean>`posted_balance_fen - reserved_balance_fen >= ${Number(request.amount_fen)}`,
        )
        .executeTakeFirst();
      if (Number(reserved.numUpdatedRows) !== 1) return null;
      const orders = await sql<{
        order_id: string;
        provider_order_id: string;
        payment_method: "WECHAT_PAY" | "ALIPAY";
        available_fen: string;
      }>`select o.order_id, o.provider_order_id, o.payment_method,
          o.amount_fen - coalesce(sum(a.amount_fen), 0) as available_fen
        from billing.recharge_orders o
        left join billing.refund_allocations a on a.order_id = o.order_id
        where o.billing_subject_id = ${request.billing_subject_id}
          and o.status = 'PAID' and o.provider_order_id is not null
        group by o.order_id, o.provider_order_id, o.payment_method, o.amount_fen, o.updated_at
        having o.amount_fen - coalesce(sum(a.amount_fen), 0) > 0
        order by o.updated_at desc`.execute(transaction);
      let remaining = Number(request.amount_fen);
      for (const order of orders.rows) {
        if (remaining === 0) break;
        const amountFen = Math.min(remaining, Number(order.available_fen));
        await transaction
          .insertInto("billing.refund_allocations")
          .values({
            refund_allocation_id: this.identifiers.next(),
            refund_request_id: request.refund_request_id,
            order_id: order.order_id,
            amount_fen: String(amountFen),
            status: "PENDING",
            provider_refund_id: null,
            refunded_at: null,
            created_at: new Date(),
          })
          .execute();
        remaining -= amountFen;
      }
      if (remaining !== 0)
        throw new Error("Refund allocation invariant failed");
      await transaction
        .updateTable("billing.refund_requests")
        .set({
          status: "APPROVED",
          reviewed_by: input.reviewerId,
          updated_at: new Date(),
          row_version: sql`row_version + 1`,
        })
        .where("refund_request_id", "=", request.refund_request_id)
        .execute();
      return { requestId: request.refund_request_id, status: "APPROVED" };
    });
  }

  public async listApproved(limit: number): Promise<readonly string[]> {
    const rows = await this.database
      .selectFrom("billing.refund_requests")
      .select("refund_request_id")
      .where("status", "=", "APPROVED")
      .orderBy("updated_at", "asc")
      .limit(limit)
      .execute();
    return rows.map((row) => row.refund_request_id);
  }

  private async prepareRefund(
    requestId: string,
  ): Promise<ApprovedRefund | null> {
    const request = await this.database
      .selectFrom("billing.refund_requests")
      .select(["refund_request_id", "amount_fen"])
      .where("refund_request_id", "=", requestId)
      .where("status", "in", ["APPROVED", "REFUNDED"])
      .executeTakeFirst();
    if (!request) return null;
    const allocations = await this.database
      .selectFrom("billing.refund_allocations as allocation")
      .innerJoin(
        "billing.recharge_orders as order",
        "order.order_id",
        "allocation.order_id",
      )
      .select([
        "allocation.refund_allocation_id",
        "allocation.amount_fen",
        "allocation.status",
        "order.order_id",
        "order.provider_order_id",
        "order.payment_method",
      ])
      .where("allocation.refund_request_id", "=", requestId)
      .orderBy("allocation.created_at", "asc")
      .execute();
    if (allocations.some((allocation) => !allocation.provider_order_id))
      return null;
    return {
      requestId: request.refund_request_id,
      amountFen: Number(request.amount_fen),
      allocations: allocations.map((allocation) => ({
        allocationId: allocation.refund_allocation_id,
        merchantOrderId: allocation.order_id,
        providerOrderId: allocation.provider_order_id!,
        paymentMethod: allocation.payment_method,
        amountFen: Number(allocation.amount_fen),
        refunded: allocation.status === "REFUNDED",
      })),
    };
  }

  public async markAllocationRefunded(input: {
    allocationId: string;
    providerRefundId: string;
  }): Promise<boolean> {
    const existing = await this.database
      .selectFrom("billing.refund_allocations")
      .select(["status", "provider_refund_id"])
      .where("refund_allocation_id", "=", input.allocationId)
      .executeTakeFirst();
    if (!existing) return false;
    if (existing.status === "REFUNDED")
      return existing.provider_refund_id === input.providerRefundId;
    const updated = await this.database
      .updateTable("billing.refund_allocations")
      .set({
        status: "REFUNDED",
        provider_refund_id: input.providerRefundId,
        refunded_at: new Date(),
      })
      .where("refund_allocation_id", "=", input.allocationId)
      .where("status", "=", "PENDING")
      .executeTakeFirst();
    return Number(updated.numUpdatedRows) === 1;
  }

  public async complete(requestId: string): Promise<boolean> {
    return this.database.transaction().execute(async (transaction) => {
      const request = await transaction
        .selectFrom("billing.refund_requests")
        .selectAll()
        .where("refund_request_id", "=", requestId)
        .forUpdate()
        .executeTakeFirst();
      if (!request) return false;
      if (request.status === "REFUNDED") return true;
      if (request.status !== "APPROVED") return false;
      const pending = await transaction
        .selectFrom("billing.refund_allocations")
        .select((expression) => expression.fn.countAll<string>().as("count"))
        .where("refund_request_id", "=", requestId)
        .where("status", "!=", "REFUNDED")
        .executeTakeFirstOrThrow();
      if (Number(pending.count) !== 0) return false;
      await transaction
        .insertInto("billing.ledger_entries")
        .values({
          ledger_entry_id: this.identifiers.next(),
          billing_subject_id: request.billing_subject_id,
          wallet_id: request.wallet_id,
          entry_type: "REFUND",
          amount_fen: String(-Number(request.amount_fen)),
          currency: "CNY",
          business_ref_type: "REFUND_REQUEST",
          business_ref_id: request.refund_request_id,
          idempotency_key: `refund:${request.refund_request_id}`,
          posted_at: new Date(),
        })
        .execute();
      const wallet = await transaction
        .updateTable("billing.wallets")
        .set({
          posted_balance_fen: sql`posted_balance_fen - ${Number(request.amount_fen)}`,
          reserved_balance_fen: sql`reserved_balance_fen - ${Number(request.amount_fen)}`,
          updated_at: new Date(),
          row_version: sql`row_version + 1`,
        })
        .where("wallet_id", "=", request.wallet_id)
        .where("reserved_balance_fen", ">=", String(request.amount_fen))
        .executeTakeFirst();
      if (Number(wallet.numUpdatedRows) !== 1)
        throw new Error("Refund wallet reservation invariant failed");
      await transaction
        .updateTable("billing.refund_requests")
        .set({
          status: "REFUNDED",
          updated_at: new Date(),
          row_version: sql`row_version + 1`,
        })
        .where("refund_request_id", "=", requestId)
        .execute();
      return true;
    });
  }

  private async ownedWallet(accountId: string) {
    return this.database
      .selectFrom("billing.billing_subjects as subject")
      .innerJoin(
        "billing.wallets as wallet",
        "wallet.billing_subject_id",
        "subject.billing_subject_id",
      )
      .select([
        "subject.billing_subject_id as subjectId",
        "wallet.wallet_id as walletId",
        "wallet.posted_balance_fen as posted",
        "wallet.reserved_balance_fen as reserved",
      ])
      .where("subject.user_id", "=", accountId)
      .executeTakeFirst();
  }
}

const toPrepared = (
  row: RechargeTable,
  existing: boolean,
): PreparedRechargeOrder => ({
  orderId: row.order_id,
  amountFen: Number(row.amount_fen),
  paymentMethod: row.payment_method,
  status: row.status,
  paymentLaunch: row.payment_launch,
  existing,
});
const toOrder = (row: RechargeTable): RechargeOrderSnapshot => ({
  orderId: row.order_id,
  status: row.status,
  paymentLaunch: row.payment_launch,
});
