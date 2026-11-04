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
  InvalidLearningTargetInputError,
  type ExtractLearningTargets,
} from "@reallanguage/backend-modules/content-formation";
import {
  apiErrorResponseSchema,
  learningTargetExtractionRequestSchema,
  learningTargetExtractionResultSchema,
} from "@reallanguage/contracts";
import {
  AUTHENTICATE_ACCESS_TOKEN,
  type AccessTokenAuthenticator,
} from "./initial-settings.controller.js";
export const EXTRACT_LEARNING_TARGETS_APPLICATION = Symbol(
  "EXTRACT_LEARNING_TARGETS_APPLICATION",
);
@Controller("content-formation")
export class LearningTargetController {
  public constructor(
    @Inject(AUTHENTICATE_ACCESS_TOKEN)
    private readonly accessTokens: AccessTokenAuthenticator,
    @Inject(EXTRACT_LEARNING_TARGETS_APPLICATION)
    private readonly application: Pick<ExtractLearningTargets, "execute">,
  ) {}
  @Post("learning-targets")
  @HttpCode(200)
  public async extract(
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
    const parsed = learningTargetExtractionRequestSchema.safeParse(body);
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
    const command = parsed.data.requestedExpression
      ? {
          accountId,
          ...parsed.data,
          requestedExpression: parsed.data.requestedExpression,
        }
      : {
          accountId,
          chapters: parsed.data.chapters,
          commandId: parsed.data.commandId,
        };
    try {
      return learningTargetExtractionResultSchema.parse(
        await this.application.execute(command),
      );
    } catch (error) {
      if (error instanceof InvalidLearningTargetInputError)
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
