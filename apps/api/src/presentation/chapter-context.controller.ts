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
import type { SelectChapterContext } from "@reallanguage/backend-modules/content-formation";
import {
  apiErrorResponseSchema,
  chapterContextSelectionRequestSchema,
  chapterContextSelectionResultSchema,
} from "@reallanguage/contracts";
import {
  AUTHENTICATE_ACCESS_TOKEN,
  type AccessTokenAuthenticator,
} from "./initial-settings.controller.js";

export const SELECT_CHAPTER_CONTEXT_APPLICATION = Symbol(
  "SELECT_CHAPTER_CONTEXT_APPLICATION",
);
@Controller("content-formation/chapters")
export class ChapterContextController {
  public constructor(
    @Inject(AUTHENTICATE_ACCESS_TOKEN)
    private readonly accessTokens: AccessTokenAuthenticator,
    @Inject(SELECT_CHAPTER_CONTEXT_APPLICATION)
    private readonly application: Pick<SelectChapterContext, "execute">,
  ) {}
  @Post("context-selection")
  @HttpCode(200)
  public async select(
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
    const parsed = chapterContextSelectionRequestSchema.safeParse(body);
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
    return chapterContextSelectionResultSchema.parse(
      await this.application.execute({ accountId, ...parsed.data }),
    );
  }
}
