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
  EvaluateSceneResponse,
  GenerateSceneResponse,
  GenerateSceneResponseFeedback,
} from "@reallanguage/backend-modules/learning";
import {
  apiErrorResponseSchema,
  sceneResponseEvaluationRequestSchema,
  sceneResponseEvaluationResultSchema,
  sceneResponseGenerationRequestSchema,
  sceneResponseGenerationResultSchema,
  sceneResponseTrainingFeedbackRequestSchema,
  sceneResponseTrainingFeedbackResultSchema,
} from "@reallanguage/contracts";
import {
  AUTHENTICATE_ACCESS_TOKEN,
  type AccessTokenAuthenticator,
} from "./initial-settings.controller.js";

export const GENERATE_SCENE_RESPONSE_APPLICATION = Symbol(
  "GENERATE_SCENE_RESPONSE_APPLICATION",
);
export const GENERATE_SCENE_RESPONSE_FEEDBACK_APPLICATION = Symbol(
  "GENERATE_SCENE_RESPONSE_FEEDBACK_APPLICATION",
);
export const EVALUATE_SCENE_RESPONSE_APPLICATION = Symbol(
  "EVALUATE_SCENE_RESPONSE_APPLICATION",
);

@Controller("learning")
export class SceneResponseController {
  public constructor(
    @Inject(AUTHENTICATE_ACCESS_TOKEN)
    private readonly accessTokens: AccessTokenAuthenticator,
    @Inject(GENERATE_SCENE_RESPONSE_APPLICATION)
    private readonly generation: Pick<GenerateSceneResponse, "execute">,
    @Inject(GENERATE_SCENE_RESPONSE_FEEDBACK_APPLICATION)
    private readonly feedback: Pick<GenerateSceneResponseFeedback, "execute">,
    @Inject(EVALUATE_SCENE_RESPONSE_APPLICATION)
    private readonly evaluation: Pick<EvaluateSceneResponse, "execute">,
  ) {}

  @Post("scene-response-tasks")
  @HttpCode(200)
  public async generate(
    @Body() body: unknown,
    @Headers("authorization") authorization: string | undefined,
    @Headers("x-request-id") requestId: string,
  ) {
    const accountId = await this.account(authorization);
    const parsed = sceneResponseGenerationRequestSchema.safeParse(body);
    if (!parsed.success) throw validation(requestId);
    return sceneResponseGenerationResultSchema.parse(
      await this.generation.execute({ accountId, ...parsed.data }),
    );
  }

  @Post("scene-response-training-feedback")
  @HttpCode(200)
  public async train(
    @Body() body: unknown,
    @Headers("authorization") authorization: string | undefined,
    @Headers("x-request-id") requestId: string,
  ) {
    const accountId = await this.account(authorization);
    const parsed = sceneResponseTrainingFeedbackRequestSchema.safeParse(body);
    if (!parsed.success) throw validation(requestId);
    return sceneResponseTrainingFeedbackResultSchema.parse(
      await this.feedback.execute({ accountId, ...parsed.data }),
    );
  }

  @Post("scene-response-evaluations")
  @HttpCode(200)
  public async evaluate(
    @Body() body: unknown,
    @Headers("authorization") authorization: string | undefined,
    @Headers("x-request-id") requestId: string,
  ) {
    const accountId = await this.account(authorization);
    const parsed = sceneResponseEvaluationRequestSchema.safeParse(body);
    if (!parsed.success) throw validation(requestId);
    return sceneResponseEvaluationResultSchema.parse(
      await this.evaluation.execute({ accountId, ...parsed.data }),
    );
  }

  private async account(authorization: string | undefined) {
    const bearer = authorization?.startsWith("Bearer ")
      ? authorization.slice(7).trim()
      : "";
    if (!bearer) throw new UnauthorizedException();
    const accountId = await this.accessTokens.authenticate(bearer);
    if (!accountId) throw new UnauthorizedException();
    return accountId;
  }
}

const validation = (requestId: string) =>
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
