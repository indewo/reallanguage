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
import type { EvaluatePatternIndependent } from "@reallanguage/backend-modules/learning";
import {
  apiErrorResponseSchema,
  patternIndependentEvaluationRequestSchema,
  patternIndependentEvaluationResultSchema,
} from "@reallanguage/contracts";
import {
  AUTHENTICATE_ACCESS_TOKEN,
  type AccessTokenAuthenticator,
} from "./initial-settings.controller.js";

export const EVALUATE_PATTERN_INDEPENDENT_APPLICATION = Symbol(
  "EVALUATE_PATTERN_INDEPENDENT_APPLICATION",
);

@Controller("learning")
export class PatternIndependentController {
  public constructor(
    @Inject(AUTHENTICATE_ACCESS_TOKEN)
    private readonly accessTokens: AccessTokenAuthenticator,
    @Inject(EVALUATE_PATTERN_INDEPENDENT_APPLICATION)
    private readonly application: Pick<EvaluatePatternIndependent, "execute">,
  ) {}

  @Post("pattern-independent-evaluation")
  @HttpCode(200)
  public async evaluate(
    @Body() body: unknown,
    @Headers("authorization") authorization: string | undefined,
    @Headers("x-request-id") requestId: string,
  ) {
    const bearer = authorization?.startsWith("Bearer ")
      ? authorization.slice(7).trim()
      : "";
    if (!bearer) throw new UnauthorizedException();
    const accountId = await this.accessTokens.authenticate(bearer);
    if (!accountId) throw new UnauthorizedException();
    const parsed = patternIndependentEvaluationRequestSchema.safeParse(body);
    if (!parsed.success) {
      throw new BadRequestException(
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
    }
    return patternIndependentEvaluationResultSchema.parse(
      await this.application.execute({ accountId, ...parsed.data }),
    );
  }
}
