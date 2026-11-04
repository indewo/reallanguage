import {
  BadRequestException,
  Body,
  Controller,
  Headers,
  HttpCode,
  Inject,
  Param,
  Post,
} from "@nestjs/common";
import type {
  CompletePasswordReset,
  RequestPasswordReset,
  VerifyPasswordReset,
} from "@reallanguage/backend-modules/identity";
import {
  apiErrorResponseSchema,
  passwordResetCompletionRequestSchema,
  passwordResetCompletionResultSchema,
  passwordResetRequestResultSchema,
  passwordResetRequestSchema,
  passwordResetVerificationRequestSchema,
  passwordResetVerificationResultSchema,
} from "@reallanguage/contracts";

export const REQUEST_PASSWORD_RESET_APPLICATION = Symbol(
  "REQUEST_PASSWORD_RESET_APPLICATION",
);
export const VERIFY_PASSWORD_RESET_APPLICATION = Symbol(
  "VERIFY_PASSWORD_RESET_APPLICATION",
);
export const COMPLETE_PASSWORD_RESET_APPLICATION = Symbol(
  "COMPLETE_PASSWORD_RESET_APPLICATION",
);

type RequestPasswordResetApplication = Pick<RequestPasswordReset, "execute">;
type VerifyPasswordResetApplication = Pick<VerifyPasswordReset, "execute">;
type CompletePasswordResetApplication = Pick<CompletePasswordReset, "execute">;

@Controller("auth/password-resets")
export class PasswordResetController {
  public constructor(
    @Inject(REQUEST_PASSWORD_RESET_APPLICATION)
    private readonly requestPasswordReset: RequestPasswordResetApplication,
    @Inject(VERIFY_PASSWORD_RESET_APPLICATION)
    private readonly verifyPasswordReset: VerifyPasswordResetApplication,
    @Inject(COMPLETE_PASSWORD_RESET_APPLICATION)
    private readonly completePasswordReset: CompletePasswordResetApplication,
  ) {}

  @Post()
  @HttpCode(200)
  public async request(
    @Body() body: unknown,
    @Headers("x-request-id") requestId: string,
  ) {
    const parsed = passwordResetRequestSchema.safeParse(body);
    if (!parsed.success) throw validationError(requestId);
    return passwordResetRequestResultSchema.parse(
      await this.requestPasswordReset.execute(parsed.data),
    );
  }

  @Post(":challengeId/verification")
  @HttpCode(200)
  public async verify(
    @Param("challengeId") challengeId: string,
    @Body() body: unknown,
    @Headers("x-request-id") requestId: string,
  ) {
    const parsed = passwordResetVerificationRequestSchema.safeParse(body);
    if (!parsed.success || !UUID_PATTERN.test(challengeId)) {
      throw validationError(requestId);
    }
    return passwordResetVerificationResultSchema.parse(
      await this.verifyPasswordReset.execute({
        challengeId,
        bearer: parsed.data.bearer,
      }),
    );
  }

  @Post("completion")
  @HttpCode(200)
  public async complete(
    @Body() body: unknown,
    @Headers("x-request-id") requestId: string,
  ) {
    const parsed = passwordResetCompletionRequestSchema.safeParse(body);
    if (!parsed.success) throw validationError(requestId);
    return passwordResetCompletionResultSchema.parse(
      await this.completePasswordReset.execute(parsed.data),
    );
  }
}

const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

const validationError = (requestId: string): BadRequestException =>
  new BadRequestException(
    apiErrorResponseSchema.parse({
      error: {
        code: "VALIDATION_FAILED",
        message: "请求内容无效。",
        retryable: false,
        requestId,
        details: {},
      },
    }),
  );
