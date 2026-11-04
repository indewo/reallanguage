import {
  BadRequestException,
  Controller,
  Get,
  Headers,
  Inject,
  Param,
  Query,
  UnauthorizedException,
  Header,
} from "@nestjs/common";
import type { RecoverLearningExplanation } from "@reallanguage/backend-modules/learning";
import {
  learningExplanationRecoveryCommandIdSchema,
  learningExplanationRecoveryQuerySchema,
  learningExplanationRecoveryResultSchema,
} from "@reallanguage/contracts";
import {
  AUTHENTICATE_ACCESS_TOKEN,
  type AccessTokenAuthenticator,
} from "./initial-settings.controller.js";
export const RECOVER_LEARNING_EXPLANATION_APPLICATION = Symbol(
  "RECOVER_LEARNING_EXPLANATION_APPLICATION",
);
@Controller("learning/explanations/deliveries")
export class LearningExplanationRecoveryController {
  public constructor(
    @Inject(AUTHENTICATE_ACCESS_TOKEN)
    private readonly accessTokens: AccessTokenAuthenticator,
    @Inject(RECOVER_LEARNING_EXPLANATION_APPLICATION)
    private readonly application: Pick<RecoverLearningExplanation, "execute">,
  ) {}
  @Get(":commandId")
  @Header("Cache-Control", "private, no-store")
  public async read(
    @Param("commandId") commandId: string,
    @Query() query: unknown,
    @Headers("authorization") authorization: string | undefined,
  ) {
    const bearer = authorization?.startsWith("Bearer ")
      ? authorization.slice(7).trim()
      : "";
    if (!bearer) throw new UnauthorizedException();
    const accountId = await this.accessTokens.authenticate(bearer);
    if (!accountId) throw new UnauthorizedException();
    const parsed = learningExplanationRecoveryQuerySchema.safeParse(query);
    if (
      !parsed.success ||
      !learningExplanationRecoveryCommandIdSchema.safeParse(commandId).success
    )
      throw new BadRequestException();
    return learningExplanationRecoveryResultSchema.parse(
      await this.application.execute({
        accountId,
        commandId,
        kind: parsed.data.kind,
      }),
    );
  }
}
