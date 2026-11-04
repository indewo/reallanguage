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
import type { GenerateKnowledgeExamples } from "@reallanguage/backend-modules/knowledge";
import {
  apiErrorResponseSchema,
  knowledgeExampleGenerationRequestSchema,
  knowledgeExampleGenerationResultSchema,
} from "@reallanguage/contracts";
import {
  AUTHENTICATE_ACCESS_TOKEN,
  type AccessTokenAuthenticator,
} from "./initial-settings.controller.js";

export const GENERATE_KNOWLEDGE_EXAMPLES_APPLICATION = Symbol(
  "GENERATE_KNOWLEDGE_EXAMPLES_APPLICATION",
);

@Controller("knowledge")
export class KnowledgeExampleController {
  public constructor(
    @Inject(AUTHENTICATE_ACCESS_TOKEN)
    private readonly accessTokens: AccessTokenAuthenticator,
    @Inject(GENERATE_KNOWLEDGE_EXAMPLES_APPLICATION)
    private readonly application: Pick<GenerateKnowledgeExamples, "execute">,
  ) {}

  @Post("examples")
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
    const parsed = knowledgeExampleGenerationRequestSchema.safeParse(body);
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
    return knowledgeExampleGenerationResultSchema.parse(
      await this.application.execute({ accountId, ...parsed.data }),
    );
  }
}
