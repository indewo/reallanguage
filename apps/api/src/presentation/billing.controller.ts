import {
  BadRequestException,
  Body,
  Controller,
  Get,
  Headers,
  Inject,
  NotFoundException,
  Param,
  Post,
  UnauthorizedException,
} from "@nestjs/common";
import {
  rechargeConfiguration,
  type ApplyPaymentEvent,
  type CreateRechargeOrder,
  type GetBillingSummary,
  type GetRechargeOrder,
  type GetRefundSnapshot,
  type RequestRefund,
  type RequestMinorRechargeSupport,
} from "@reallanguage/backend-modules/billing";
import {
  billingSummarySchema,
  rechargeConfigSchema,
  rechargeOrderCommandSchema,
  rechargeOrderResultSchema,
  refundCommandSchema,
  refundPageSchema,
  refundResultSchema,
  paymentEventEnvelopeSchema,
  minorRechargeSupportCommandSchema,
  minorRechargeSupportResultSchema,
} from "@reallanguage/contracts";
import {
  AUTHENTICATE_ACCESS_TOKEN,
  type AccessTokenAuthenticator,
} from "./initial-settings.controller.js";

export const BILLING_SUMMARY_APPLICATION = Symbol(
  "BILLING_SUMMARY_APPLICATION",
);
export const CREATE_RECHARGE_ORDER_APPLICATION = Symbol(
  "CREATE_RECHARGE_ORDER_APPLICATION",
);
export const GET_RECHARGE_ORDER_APPLICATION = Symbol(
  "GET_RECHARGE_ORDER_APPLICATION",
);
export const GET_REFUND_APPLICATION = Symbol("GET_REFUND_APPLICATION");
export const REQUEST_REFUND_APPLICATION = Symbol("REQUEST_REFUND_APPLICATION");
export const APPLY_PAYMENT_EVENT_APPLICATION = Symbol(
  "APPLY_PAYMENT_EVENT_APPLICATION",
);
export const REQUEST_MINOR_RECHARGE_SUPPORT_APPLICATION = Symbol(
  "REQUEST_MINOR_RECHARGE_SUPPORT_APPLICATION",
);

@Controller("billing")
export class BillingController {
  public constructor(
    @Inject(AUTHENTICATE_ACCESS_TOKEN)
    private readonly accessTokens: AccessTokenAuthenticator,
    @Inject(BILLING_SUMMARY_APPLICATION)
    private readonly billing: Pick<GetBillingSummary, "execute">,
    @Inject(CREATE_RECHARGE_ORDER_APPLICATION)
    private readonly createRecharge: Pick<CreateRechargeOrder, "execute">,
    @Inject(GET_RECHARGE_ORDER_APPLICATION)
    private readonly getRecharge: Pick<GetRechargeOrder, "execute">,
    @Inject(GET_REFUND_APPLICATION)
    private readonly getRefund: Pick<GetRefundSnapshot, "execute">,
    @Inject(REQUEST_REFUND_APPLICATION)
    private readonly requestRefund: Pick<RequestRefund, "execute">,
    @Inject(APPLY_PAYMENT_EVENT_APPLICATION)
    private readonly applyPayment: Pick<ApplyPaymentEvent, "execute">,
    @Inject(REQUEST_MINOR_RECHARGE_SUPPORT_APPLICATION)
    private readonly requestMinorRechargeSupport: Pick<
      RequestMinorRechargeSupport,
      "execute"
    >,
  ) {}

  @Get("recharge-config")
  public rechargeConfig() {
    return rechargeConfigSchema.parse(rechargeConfiguration);
  }

  @Get("summary")
  public async summary(
    @Headers("authorization") authorization: string | undefined,
  ) {
    const accountId = await this.accountId(authorization);
    const summary = await this.billing.execute({ accountId });
    if (!summary) throw new NotFoundException();
    return billingSummarySchema.parse({
      ...summary,
      charges: summary.charges.map((charge) => ({
        ...charge,
        occurredAt: charge.occurredAt.toISOString(),
      })),
    });
  }

  @Post("recharge-orders")
  public async createOrder(
    @Body() body: unknown,
    @Headers("authorization") authorization: string | undefined,
  ) {
    const accountId = await this.accountId(authorization);
    const command = rechargeOrderCommandSchema.safeParse(body);
    if (!command.success) throw new BadRequestException();
    const result = await this.createRecharge.execute({
      accountId,
      ...command.data,
    });
    if (!result) throw new BadRequestException();
    return rechargeOrderResultSchema.parse(result);
  }

  @Get("recharge-orders/:orderId")
  public async order(
    @Param("orderId") orderId: string,
    @Headers("authorization") authorization: string | undefined,
  ) {
    const accountId = await this.accountId(authorization);
    const result = await this.getRecharge.execute({ accountId, orderId });
    if (!result) throw new NotFoundException();
    return rechargeOrderResultSchema.parse(result);
  }

  @Get("refunds/current")
  public async refund(
    @Headers("authorization") authorization: string | undefined,
  ) {
    const accountId = await this.accountId(authorization);
    const result = await this.getRefund.execute({ accountId });
    if (!result) throw new NotFoundException();
    return refundPageSchema.parse(result);
  }

  @Post("refunds")
  public async request(
    @Body() body: unknown,
    @Headers("authorization") authorization: string | undefined,
  ) {
    const accountId = await this.accountId(authorization);
    const command = refundCommandSchema.safeParse(body);
    if (!command.success) throw new BadRequestException();
    const result = await this.requestRefund.execute({
      accountId,
      ...command.data,
    });
    if (!result) throw new BadRequestException();
    return refundResultSchema.parse(result);
  }

  @Post("payment-events")
  public async paymentEvent(@Body() body: unknown) {
    const envelope = paymentEventEnvelopeSchema.safeParse(body);
    if (!envelope.success) throw new BadRequestException();
    if (!(await this.applyPayment.execute(envelope.data)))
      throw new BadRequestException();
    return { status: "ACCEPTED" as const };
  }

  @Post("minor-recharge-support")
  public async minorRechargeSupport(
    @Body() body: unknown,
    @Headers("authorization") authorization: string | undefined,
  ) {
    const accountId = await this.accountId(authorization);
    const command = minorRechargeSupportCommandSchema.safeParse(body);
    if (!command.success) throw new BadRequestException();
    const result = await this.requestMinorRechargeSupport.execute({
      accountId,
      ...command.data,
    });
    if (!result) throw new BadRequestException();
    return minorRechargeSupportResultSchema.parse(result);
  }

  private async accountId(authorization: string | undefined) {
    const bearer = authorization?.startsWith("Bearer ")
      ? authorization.slice(7).trim()
      : "";
    if (!bearer) throw new UnauthorizedException();
    const accountId = await this.accessTokens.authenticate(bearer);
    if (!accountId) throw new UnauthorizedException();
    return accountId;
  }
}
