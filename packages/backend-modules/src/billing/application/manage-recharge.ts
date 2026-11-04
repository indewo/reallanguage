export type PaymentMethod = "WECHAT_PAY" | "ALIPAY";
export type RechargeOrderStatus =
  | "CREATED"
  | "PENDING_PAYMENT"
  | "IN_PROGRESS"
  | "PAID"
  | "FAILED"
  | "CANCELLED";
export type RechargeOrderSnapshot = Readonly<{
  orderId: string;
  status: RechargeOrderStatus;
  paymentLaunch: string | null;
}>;
export type PreparedRechargeOrder = Readonly<{
  orderId: string;
  amountFen: number;
  paymentMethod: PaymentMethod;
  status: RechargeOrderStatus;
  paymentLaunch: string | null;
  existing: boolean;
}>;
export interface RechargeOrderStore {
  read(
    accountId: string,
    orderId: string,
  ): Promise<RechargeOrderSnapshot | null>;
  prepare(input: {
    accountId: string;
    commandId: string;
    amountFen: number;
    paymentMethod: PaymentMethod;
  }): Promise<PreparedRechargeOrder | null>;
  markPending(input: {
    orderId: string;
    providerOrderId: string;
    paymentLaunch: string;
  }): Promise<RechargeOrderSnapshot | null>;
  markFailed(orderId: string): Promise<void>;
}
export class GetRechargeOrder {
  public constructor(private readonly orders: RechargeOrderStore) {}
  public execute(query: { accountId: string; orderId: string }) {
    return this.orders.read(query.accountId, query.orderId);
  }
}
export interface PaymentOrderProvider {
  create(input: {
    merchantOrderId: string;
    amountFen: number;
    currency: "CNY";
    paymentMethod: PaymentMethod;
  }): Promise<{ providerOrderId: string; paymentLaunch: string }>;
}
export const rechargeConfiguration = {
  recommendedOptions: [
    { amountFen: 5_000, visionZh: "开始把真正需要的英语练熟" },
    { amountFen: 20_000, visionZh: "持续积累一批能够自信使用的表达" },
    { amountFen: 50_000, visionZh: "支持更长期、更深入的真实表达训练" },
  ],
  minimumAmountFen: 100,
  maximumAmountFen: 1_000_000,
} as const;

export class CreateRechargeOrder {
  public constructor(
    private readonly orders: RechargeOrderStore,
    private readonly payments: PaymentOrderProvider,
  ) {}
  public async execute(command: {
    accountId: string;
    commandId: string;
    amountFen: number;
    paymentMethod: PaymentMethod;
  }): Promise<RechargeOrderSnapshot | null> {
    if (
      command.amountFen < rechargeConfiguration.minimumAmountFen ||
      command.amountFen > rechargeConfiguration.maximumAmountFen
    )
      return null;
    const order = await this.orders.prepare(command);
    if (!order) return null;
    if (order.existing)
      return {
        orderId: order.orderId,
        status: order.status,
        paymentLaunch: order.paymentLaunch,
      };
    try {
      const provider = await this.payments.create({
        merchantOrderId: order.orderId,
        amountFen: order.amountFen,
        currency: "CNY",
        paymentMethod: order.paymentMethod,
      });
      return await this.orders.markPending({
        orderId: order.orderId,
        ...provider,
      });
    } catch (error) {
      await this.orders.markFailed(order.orderId);
      throw error;
    }
  }
}
