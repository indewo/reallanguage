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
import type { GenerateReadingFeedback } from "@reallanguage/backend-modules/learning";
import {
  apiErrorResponseSchema,
  readingFeedbackRequestSchema,
  readingFeedbackResultSchema,
} from "@reallanguage/contracts";
import {
  AUTHENTICATE_ACCESS_TOKEN,
  type AccessTokenAuthenticator,
} from "./initial-settings.controller.js";

export const GENERATE_READING_FEEDBACK_APPLICATION = Symbol(
  "GENERATE_READING_FEEDBACK_APPLICATION",
);

@Controller("learning")
export class ReadingFeedbackController {
  public constructor(
    @Inject(AUTHENTICATE_ACCESS_TOKEN)
    private readonly accessTokens: AccessTokenAuthenticator,
    @Inject(GENERATE_READING_FEEDBACK_APPLICATION)
    private readonly application: Pick<GenerateReadingFeedback, "execute">,
  ) {}

  @Post("reading-feedback")
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
    const parsed = readingFeedbackRequestSchema.safeParse(body);
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
    return readingFeedbackResultSchema.parse(
      await this.application.execute({ accountId, ...parsed.data }),
    );
  }
}
