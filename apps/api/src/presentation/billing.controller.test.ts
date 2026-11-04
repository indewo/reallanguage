import { Module } from "@nestjs/common";
import type { NestFastifyApplication } from "@nestjs/platform-fastify";
import { afterEach, describe, expect, it, vi } from "vitest";
import { createHttpApp } from "../create-app.js";
import {
  APPLY_PAYMENT_EVENT_APPLICATION,
  BILLING_SUMMARY_APPLICATION,
  BillingController,
  CREATE_RECHARGE_ORDER_APPLICATION,
  GET_RECHARGE_ORDER_APPLICATION,
  GET_REFUND_APPLICATION,
  REQUEST_REFUND_APPLICATION,
  REQUEST_MINOR_RECHARGE_SUPPORT_APPLICATION,
} from "./billing.controller.js";
import { AUTHENTICATE_ACCESS_TOKEN } from "./initial-settings.controller.js";

const execute = vi.fn();
const authenticate = vi.fn();
const createRecharge = vi.fn();
const getRecharge = vi.fn();
const getRefund = vi.fn();
const requestRefund = vi.fn();
const applyPayment = vi.fn();
const requestMinorSupport = vi.fn();
@Module({
  controllers: [BillingController],
  providers: [
    { provide: BILLING_SUMMARY_APPLICATION, useValue: { execute } },
    {
      provide: CREATE_RECHARGE_ORDER_APPLICATION,
      useValue: { execute: createRecharge },
    },
    {
      provide: GET_RECHARGE_ORDER_APPLICATION,
      useValue: { execute: getRecharge },
    },
    { provide: GET_REFUND_APPLICATION, useValue: { execute: getRefund } },
    {
      provide: REQUEST_REFUND_APPLICATION,
      useValue: { execute: requestRefund },
    },
    {
      provide: APPLY_PAYMENT_EVENT_APPLICATION,
      useValue: { execute: applyPayment },
    },
    {
      provide: REQUEST_MINOR_RECHARGE_SUPPORT_APPLICATION,
      useValue: { execute: requestMinorSupport },
    },
    { provide: AUTHENTICATE_ACCESS_TOKEN, useValue: { authenticate } },
  ],
})
class TestModule {}
let app: NestFastifyApplication | undefined;
afterEach(async () => {
  await app?.close();
  app = undefined;
  [
    execute,
    authenticate,
    createRecharge,
    getRecharge,
    getRefund,
    requestRefund,
    applyPayment,
    requestMinorSupport,
  ].forEach((mock) => mock.mockReset());
});

describe("billing HTTP adapter", () => {
  it("authenticates and returns CNY balance with actual charge facts", async () => {
    authenticate.mockResolvedValue("account-1");
    execute.mockResolvedValue({
      balanceAmountFen: 800,
      charges: [
        {
          chargeId: "attempt-1",
          amountFen: 25,
          occurredAt: new Date("2026-09-02T00:00:00Z"),
          modelSource: "provider/model",
          callType: "USER_RETRY",
          operationLabelZh: "生成英语表达",
        },
      ],
    });
    app = await createHttpApp(TestModule);
    const response = await app.inject({
      method: "GET",
      url: "/api/v1/billing/summary",
      headers: { authorization: "Bearer token" },
    });
    expect(response.statusCode).toBe(200);
    expect(response.json()).toMatchObject({
      balanceAmountFen: 800,
      charges: [
        { chargeId: "attempt-1", occurredAt: "2026-09-02T00:00:00.000Z" },
      ],
    });
    expect(execute).toHaveBeenCalledWith({ accountId: "account-1" });
  });

  it("does not expose billing data for an expired session", async () => {
    authenticate.mockResolvedValue(null);
    app = await createHttpApp(TestModule);
    const response = await app.inject({
      method: "GET",
      url: "/api/v1/billing/summary",
      headers: { authorization: "Bearer expired" },
    });
    expect(response.statusCode).toBe(401);
    expect(execute).not.toHaveBeenCalled();
  });

  it("publishes recharge configuration and authenticated order creation", async () => {
    authenticate.mockResolvedValue("account-1");
    createRecharge.mockResolvedValue({
      orderId: "order-1",
      status: "PENDING_PAYMENT",
      paymentLaunch: "launch",
    });
    app = await createHttpApp(TestModule);
    expect(
      (
        await app.inject({
          method: "GET",
          url: "/api/v1/billing/recharge-config",
        })
      ).json().recommendedOptions,
    ).toHaveLength(3);
    const response = await app.inject({
      method: "POST",
      url: "/api/v1/billing/recharge-orders",
      headers: { authorization: "Bearer token" },
      payload: { amountFen: 5000, paymentMethod: "ALIPAY", commandId: "pay-1" },
    });
    expect(response.statusCode).toBe(201);
    expect(response.json()).toEqual({
      orderId: "order-1",
      status: "PENDING_PAYMENT",
      paymentLaunch: "launch",
    });
  });

  it("loads and requests server-calculated full refunds", async () => {
    authenticate.mockResolvedValue("account-1");
    getRefund.mockResolvedValue({
      refundableAmountFen: 3000,
      refundMethodLabelZh: "原路退回",
      requestId: null,
      requestStatus: null,
    });
    requestRefund.mockResolvedValue({
      requestId: "refund-1",
      status: "REQUESTED",
    });
    app = await createHttpApp(TestModule);
    expect(
      (
        await app.inject({
          method: "GET",
          url: "/api/v1/billing/refunds/current",
          headers: { authorization: "Bearer token" },
        })
      ).json().refundableAmountFen,
    ).toBe(3000);
    const response = await app.inject({
      method: "POST",
      url: "/api/v1/billing/refunds",
      headers: { authorization: "Bearer token" },
      payload: { amountFen: 3000, commandId: "refund-command" },
    });
    expect(response.statusCode).toBe(201);
    expect(response.json()).toEqual({
      requestId: "refund-1",
      status: "REQUESTED",
    });
  });

  it("accepts only a verified bounded payment event envelope", async () => {
    applyPayment.mockResolvedValue(true);
    app = await createHttpApp(TestModule);
    const payload = {
      payloadBase64: "e30=",
      signature: "YWJj",
      timestamp: "1788300000",
    };
    const response = await app.inject({
      method: "POST",
      url: "/api/v1/billing/payment-events",
      payload,
    });
    expect(response.statusCode).toBe(201);
    expect(response.json()).toEqual({ status: "ACCEPTED" });
    expect(applyPayment).toHaveBeenCalledWith(payload);
  });

  it("submits an authenticated minor recharge support case", async () => {
    authenticate.mockResolvedValue("account-1");
    requestMinorSupport.mockResolvedValue({
      supportCaseId: "case-1",
      status: "REQUESTED",
    });
    app = await createHttpApp(TestModule);
    const response = await app.inject({
      method: "POST",
      url: "/api/v1/billing/minor-recharge-support",
      headers: { authorization: "Bearer token" },
      payload: { commandId: "minor-command-1" },
    });
    expect(response.statusCode).toBe(201);
    expect(response.json()).toEqual({
      supportCaseId: "case-1",
      status: "REQUESTED",
    });
  });
});
