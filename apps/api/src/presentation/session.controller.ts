import {
  BadRequestException,
  Body,
  Controller,
  Delete,
  Headers,
  HttpCode,
  Inject,
  Post,
} from "@nestjs/common";
import type {
  CreateSession,
  RefreshSession,
  RevokeSession,
} from "@reallanguage/backend-modules/identity";
import {
  apiErrorResponseSchema,
  loginRequestSchema,
  loginResultSchema,
  refreshSessionRequestSchema,
  refreshSessionResultSchema,
  revokeSessionRequestSchema,
  revokeSessionResultSchema,
  type LoginResult,
  type RefreshSessionResult,
  type RevokeSessionResult,
} from "@reallanguage/contracts";

export const CREATE_SESSION_APPLICATION = Symbol("CREATE_SESSION_APPLICATION");
export const REFRESH_SESSION_APPLICATION = Symbol(
  "REFRESH_SESSION_APPLICATION",
);
export const REVOKE_SESSION_APPLICATION = Symbol("REVOKE_SESSION_APPLICATION");

type CreateSessionApplication = Pick<CreateSession, "execute">;
type RefreshSessionApplication = Pick<RefreshSession, "execute">;
type RevokeSessionApplication = Pick<RevokeSession, "execute">;

@Controller("auth/sessions")
export class SessionController {
  public constructor(
    @Inject(CREATE_SESSION_APPLICATION)
    private readonly createSession: CreateSessionApplication,
    @Inject(REFRESH_SESSION_APPLICATION)
    private readonly refreshSession: RefreshSessionApplication,
    @Inject(REVOKE_SESSION_APPLICATION)
    private readonly revokeSession: RevokeSessionApplication,
  ) {}

  @Post()
  @HttpCode(200)
  public async create(
    @Body() body: unknown,
    @Headers("x-request-id") requestId: string,
  ): Promise<LoginResult> {
    const parsed = loginRequestSchema.safeParse(body);
    if (!parsed.success) throw validationError(requestId);
    return loginResultSchema.parse(
      await this.createSession.execute(parsed.data),
    );
  }

  @Post("refresh")
  @HttpCode(200)
  public async refresh(
    @Body() body: unknown,
    @Headers("x-request-id") requestId: string,
  ): Promise<RefreshSessionResult> {
    const parsed = refreshSessionRequestSchema.safeParse(body);
    if (!parsed.success) throw validationError(requestId);
    return refreshSessionResultSchema.parse(
      await this.refreshSession.execute(parsed.data),
    );
  }

  @Delete("current")
  @HttpCode(200)
  public async revoke(
    @Body() body: unknown,
    @Headers("x-request-id") requestId: string,
  ): Promise<RevokeSessionResult> {
    const parsed = revokeSessionRequestSchema.safeParse(body);
    if (!parsed.success) throw validationError(requestId);
    return revokeSessionResultSchema.parse(
      await this.revokeSession.execute(parsed.data),
    );
  }
}

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
