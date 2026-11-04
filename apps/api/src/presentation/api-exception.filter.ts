import {
  ArgumentsHost,
  Catch,
  HttpException,
  HttpStatus,
  type ExceptionFilter,
} from "@nestjs/common";
import {
  apiErrorResponseSchema,
  type ApiErrorResponse,
} from "@reallanguage/contracts";
import type { FastifyReply, FastifyRequest } from "fastify";

@Catch()
export class ApiExceptionFilter implements ExceptionFilter {
  public catch(exception: unknown, host: ArgumentsHost): void {
    const http = host.switchToHttp();
    const reply = http.getResponse<FastifyReply>();
    const request = http.getRequest<FastifyRequest>();
    const delivery = deliveryError(exception);
    const status =
      delivery?.status ??
      (isBalanceInsufficient(exception)
        ? HttpStatus.PAYMENT_REQUIRED
        : exception instanceof HttpException
          ? exception.getStatus()
          : HttpStatus.INTERNAL_SERVER_ERROR);
    const existing =
      exception instanceof HttpException
        ? apiErrorResponseSchema.safeParse(exception.getResponse())
        : null;
    const requestIdHeader = request.headers["x-request-id"];
    const requestId =
      typeof requestIdHeader === "string" ? requestIdHeader : "unknown";
    const body = delivery
      ? apiErrorResponseSchema.parse({
          error: {
            code: delivery.code,
            message: delivery.message,
            retryable: delivery.retryable,
            requestId,
            details: {},
          },
        })
      : existing?.success === true
        ? existing.data
        : errorForStatus(status, requestId);

    void reply.status(status).send(body);
  }
}

const deliveryError = (error: unknown) => {
  if (
    !error ||
    typeof error !== "object" ||
    !("code" in error) ||
    typeof error.code !== "string"
  )
    return null;
  const code = error.code;
  switch (code) {
    case "AI_OPERATION_PROCESSING":
      return {
        code,
        status: 409,
        retryable: true,
        message: "同一请求仍在处理中，请稍后重试；不会重复生成。",
      };
    case "IDEMPOTENCY_KEY_REUSED":
      return {
        code,
        status: 409,
        retryable: false,
        message: "请求编号已用于不同内容，本次未再次生成。",
      };
    case "AI_OPERATION_EXPIRED":
      return {
        code,
        status: 410,
        retryable: false,
        message: "临时结果已到期，未再次调用 AI；已保存在本机的内容不受影响。",
      };
    case "AI_OPERATION_FAILED":
    case "AI_CHARGE_RECONCILIATION_REQUIRED":
      return {
        code,
        status: 409,
        retryable: false,
        message: "本次调用结果需要核对，未重复调用 AI。",
      };
    default:
      return null;
  }
};

const errorForStatus = (
  status: number,
  requestId: string,
): ApiErrorResponse => {
  const classification = classify(status);
  return apiErrorResponseSchema.parse({
    error: {
      ...classification,
      requestId,
      details: {},
    },
  });
};

const classify = (
  status: number,
): Readonly<{ code: string; message: string; retryable: boolean }> => {
  switch (status) {
    case HttpStatus.BAD_REQUEST:
      return {
        code: "VALIDATION_FAILED",
        message: "请求内容无效。",
        retryable: false,
      };
    case HttpStatus.UNAUTHORIZED:
      return {
        code: "AUTHENTICATION_REQUIRED",
        message: "请重新登录。",
        retryable: false,
      };
    case HttpStatus.FORBIDDEN:
      return {
        code: "FORBIDDEN",
        message: "当前账号不能执行此操作。",
        retryable: false,
      };
    case HttpStatus.NOT_FOUND:
      return {
        code: "NOT_FOUND",
        message: "请求的内容不存在。",
        retryable: false,
      };
    case HttpStatus.CONFLICT:
      return {
        code: "CONFLICT",
        message: "状态已发生变化，请刷新后重试。",
        retryable: false,
      };
    case HttpStatus.PAYMENT_REQUIRED:
      return {
        code: "AI_BALANCE_INSUFFICIENT",
        message: "余额不足，请先充值。",
        retryable: false,
      };
    case HttpStatus.TOO_MANY_REQUESTS:
      return {
        code: "RATE_LIMITED",
        message: "请求过于频繁，请稍后重试。",
        retryable: true,
      };
    case HttpStatus.BAD_GATEWAY:
    case HttpStatus.SERVICE_UNAVAILABLE:
    case HttpStatus.GATEWAY_TIMEOUT:
      return {
        code: "TEMPORARILY_UNAVAILABLE",
        message: "服务暂时不可用，请稍后重试。",
        retryable: true,
      };
    default:
      return {
        code: "INTERNAL_ERROR",
        message: "服务暂时出现问题。",
        retryable: false,
      };
  }
};

const isBalanceInsufficient = (
  value: unknown,
): value is { code: "AI_BALANCE_INSUFFICIENT" } =>
  typeof value === "object" &&
  value !== null &&
  "code" in value &&
  value.code === "AI_BALANCE_INSUFFICIENT";
