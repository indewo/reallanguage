import {
  BadRequestException,
  Body,
  Controller,
  Headers,
  HttpCode,
  Inject,
  Post,
  UnauthorizedException,
} from "@nestjs/common";
import type { CheckContentSafety } from "@reallanguage/backend-modules/safety";
import {
  apiErrorResponseSchema,
  safetyCheckRequestSchema,
  safetyCheckResultSchema,
} from "@reallanguage/contracts";

import {
  AUTHENTICATE_ACCESS_TOKEN,
  type AccessTokenAuthenticator,
} from "./initial-settings.controller.js";

export const CHECK_CONTENT_SAFETY_APPLICATION = Symbol(
  "CHECK_CONTENT_SAFETY_APPLICATION",
);

type CheckContentSafetyApplication = Pick<CheckContentSafety, "execute">;

@Controller("safety/checks")
export class SafetyCheckController {
  public constructor(
    @Inject(AUTHENTICATE_ACCESS_TOKEN)
    private readonly accessTokens: AccessTokenAuthenticator,
    @Inject(CHECK_CONTENT_SAFETY_APPLICATION)
    private readonly checkContentSafety: CheckContentSafetyApplication,
  ) {}

  @Post()
  @HttpCode(200)
  public async check(
    @Body() body: unknown,
    @Headers("authorization") authorization: string | undefined,
    @Headers("x-request-id") requestId: string,
  ) {
    const bearer = parseBearer(authorization);
    if (!bearer) throw new UnauthorizedException();
    const accountId = await this.accessTokens.authenticate(bearer);
    if (!accountId) throw new UnauthorizedException();
    const parsed = safetyCheckRequestSchema.safeParse(body);
    if (!parsed.success) throw validationError(requestId);
    return safetyCheckResultSchema.parse(
      await this.checkContentSafety.execute({ accountId, ...parsed.data }),
    );
  }
}

const parseBearer = (authorization: string | undefined): string | null => {
  if (!authorization?.startsWith("Bearer ")) return null;
  const bearer = authorization.slice("Bearer ".length).trim();
  return bearer || null;
};

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
