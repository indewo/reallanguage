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
import type { SplitSemanticUnits } from "@reallanguage/backend-modules/content-formation";
import {
  apiErrorResponseSchema,
  semanticUnitSplitRequestSchema,
  semanticUnitSplitResultSchema,
} from "@reallanguage/contracts";
import {
  AUTHENTICATE_ACCESS_TOKEN,
  type AccessTokenAuthenticator,
} from "./initial-settings.controller.js";

export const SPLIT_SEMANTIC_UNITS_APPLICATION = Symbol(
  "SPLIT_SEMANTIC_UNITS_APPLICATION",
);

@Controller("content-formation/semantic-units")
export class SemanticUnitSplitController {
  public constructor(
    @Inject(AUTHENTICATE_ACCESS_TOKEN)
    private readonly accessTokens: AccessTokenAuthenticator,
    @Inject(SPLIT_SEMANTIC_UNITS_APPLICATION)
    private readonly splitSemanticUnits: Pick<SplitSemanticUnits, "execute">,
  ) {}

  @Post("split")
  @HttpCode(200)
  public async split(
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
    const parsed = semanticUnitSplitRequestSchema.safeParse(body);
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
    return semanticUnitSplitResultSchema.parse(
      await this.splitSemanticUnits.execute({ accountId, ...parsed.data }),
    );
  }
}
