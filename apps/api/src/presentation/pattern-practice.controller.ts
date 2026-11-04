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
import type {
  GeneratePatternPractice,
  GeneratePatternPracticeFeedback,
} from "@reallanguage/backend-modules/learning";
import {
  apiErrorResponseSchema,
  patternPracticeGenerationRequestSchema,
  patternPracticeGenerationResultSchema,
  patternPracticeFeedbackRequestSchema,
  patternPracticeFeedbackResultSchema,
} from "@reallanguage/contracts";
import {
  AUTHENTICATE_ACCESS_TOKEN,
  type AccessTokenAuthenticator,
} from "./initial-settings.controller.js";

export const GENERATE_PATTERN_PRACTICE_APPLICATION = Symbol(
  "GENERATE_PATTERN_PRACTICE_APPLICATION",
);
export const GENERATE_PATTERN_PRACTICE_FEEDBACK_APPLICATION = Symbol(
  "GENERATE_PATTERN_PRACTICE_FEEDBACK_APPLICATION",
);

@Controller("learning")
export class PatternPracticeController {
  public constructor(
    @Inject(AUTHENTICATE_ACCESS_TOKEN)
    private readonly accessTokens: AccessTokenAuthenticator,
    @Inject(GENERATE_PATTERN_PRACTICE_APPLICATION)
    private readonly application: Pick<GeneratePatternPractice, "execute">,
    @Inject(GENERATE_PATTERN_PRACTICE_FEEDBACK_APPLICATION)
    private readonly feedback: Pick<GeneratePatternPracticeFeedback, "execute">,
  ) {}

  @Post("pattern-practice-sets")
  @HttpCode(200)
  public async generate(
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
    const parsed = patternPracticeGenerationRequestSchema.safeParse(body);
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
    return patternPracticeGenerationResultSchema.parse(
      await this.application.execute({ accountId, ...parsed.data }),
    );
  }

  @Post("pattern-practice-feedback")
  @HttpCode(200)
  public async generateFeedback(
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
    const parsed = patternPracticeFeedbackRequestSchema.safeParse(body);
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
    return patternPracticeFeedbackResultSchema.parse(
      await this.feedback.execute({ accountId, ...parsed.data }),
    );
  }
}
