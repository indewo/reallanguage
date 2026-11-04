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
import {
  InvalidLearningExplanationInputError,
  type GenerateLearningExplanation,
} from "@reallanguage/backend-modules/learning";
import {
  apiErrorResponseSchema,
  learningExplanationRequestSchema,
  learningExplanationResultSchema,
} from "@reallanguage/contracts";
import {
  AUTHENTICATE_ACCESS_TOKEN,
  type AccessTokenAuthenticator,
} from "./initial-settings.controller.js";
export const GENERATE_LEARNING_EXPLANATION_APPLICATION = Symbol(
  "GENERATE_LEARNING_EXPLANATION_APPLICATION",
);
@Controller("learning")
export class LearningExplanationController {
  public constructor(
    @Inject(AUTHENTICATE_ACCESS_TOKEN)
    private readonly accessTokens: AccessTokenAuthenticator,
    @Inject(GENERATE_LEARNING_EXPLANATION_APPLICATION)
    private readonly application: Pick<GenerateLearningExplanation, "execute">,
  ) {}
  @Post("explanations")
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
    const parsed = learningExplanationRequestSchema.safeParse(body);
    if (!parsed.success)
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
    const optional = {
      ...(parsed.data.lockedTargets !== undefined
        ? { lockedTargets: parsed.data.lockedTargets }
        : {}),
      ...(parsed.data.existingBaseExplanationZh
        ? { existingBaseExplanationZh: parsed.data.existingBaseExplanationZh }
        : {}),
      ...(parsed.data.requestedExpression
        ? { requestedExpression: parsed.data.requestedExpression }
        : {}),
    };
    try {
      return learningExplanationResultSchema.parse(
        await this.application.execute({
          accountId,
          chapter: parsed.data.chapter,
          focusSentenceId: parsed.data.focusSentenceId,
          round: parsed.data.round,
          commandId: parsed.data.commandId,
          ...optional,
        }),
      );
    } catch (error) {
      if (error instanceof InvalidLearningExplanationInputError)
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
      throw error;
    }
  }
}
