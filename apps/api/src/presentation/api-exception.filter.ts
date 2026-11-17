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

export type ApiErrorLogEntry = Readonly<{
  method: string;
  path: string;
  statusCode: number;
  requestId: string;
  errorName: string;
  errorMessage: string;
  stack?: string;
}>;

@Catch()
export class ApiExceptionFilter implements ExceptionFilter {
  public constructor(
    private readonly errorLogger?: (entry: ApiErrorLogEntry) => void,
  ) {}

  public catch(exception: unknown, host: ArgumentsHost): void {
    const http = host.switchToHttp();
    const reply = http.getResponse<FastifyReply>();
    const request = http.getRequest<FastifyRequest>();
    const delivery = deliveryError(exception);
    const status =
      delivery?.status ??
      (isTimeoutFailure(exception)
        ? HttpStatus.GATEWAY_TIMEOUT
        : isBalanceInsufficient(exception)
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

    if (status >= HttpStatus.INTERNAL_SERVER_ERROR) {
      this.errorLogger?.({
        method: request.method,
        path: request.url.split("?", 1)[0] ?? request.url,
        statusCode: status,
        requestId,
        ...failureDetail(exception),
      });
    }

    void reply.status(status).send(body);
  }
}

const failureDetail = (
  exception: unknown,
): Readonly<{ errorName: string; errorMessage: string; stack?: string }> => {
  if (exception instanceof Error) {
    return {
      errorName: exception.name,
      errorMessage: exception.message,
      ...(exception.stack ? { stack: exception.stack } : {}),
    };
  }
  if (typeof exception === "object" && exception !== null) {
    const candidate = exception as { name?: unknown; message?: unknown };
    return {
      errorName:
        typeof candidate.name === "string" ? candidate.name : "UnknownError",
      errorMessage:
        typeof candidate.message === "string"
          ? candidate.message
          : "no message",
    };
  }
  return { errorName: "UnknownError", errorMessage: String(exception) };
};

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

// AbortSignal.timeout() 抛出的是名为 TimeoutError 的 DOMException，不是
// HttpException，若不单独识别会落到 500，让调用方误以为服务端有 bug。
// undici 在连接/首字节/响应体阶段超时则改用 ERR_* 错误码，同样归为超时。
const timeoutErrorNames = new Set(["TimeoutError"]);
const timeoutErrorCodes = new Set([
  "UND_ERR_CONNECT_TIMEOUT",
  "UND_ERR_HEADERS_TIMEOUT",
  "UND_ERR_BODY_TIMEOUT",
  "ETIMEDOUT",
]);

const isTimeoutFailure = (error: unknown): boolean => {
  let current: unknown = error;
  // fetch 会把底层原因挂在 cause 上，最多向下追四层，避免环形引用。
  for (let depth = 0; depth < 4; depth += 1) {
    if (typeof current !== "object" || current === null) return false;
    const candidate = current as {
      name?: unknown;
      code?: unknown;
      cause?: unknown;
    };
    if (
      typeof candidate.name === "string" &&
      timeoutErrorNames.has(candidate.name)
    )
      return true;
    if (
      typeof candidate.code === "string" &&
      timeoutErrorCodes.has(candidate.code)
    )
      return true;
    current = candidate.cause;
  }
  return false;
};
