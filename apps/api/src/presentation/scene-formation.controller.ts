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
  GenerateSceneDraft,
  GenerateSceneRecommendations,
  GetNextSceneQuestion,
  ReviewSceneEdit,
} from "@reallanguage/backend-modules/sources";
import {
  apiErrorResponseSchema,
  sceneDraftRequestSchema,
  sceneDraftResultSchema,
  sceneEditReviewRequestSchema,
  sceneEditReviewResultSchema,
  sceneNextQuestionRequestSchema,
  sceneNextQuestionResultSchema,
  sceneRecommendationRequestSchema,
  sceneRecommendationResultSchema,
} from "@reallanguage/contracts";
import {
  AUTHENTICATE_ACCESS_TOKEN,
  type AccessTokenAuthenticator,
} from "./initial-settings.controller.js";

export const GENERATE_SCENE_RECOMMENDATIONS_APPLICATION = Symbol(
  "GENERATE_SCENE_RECOMMENDATIONS_APPLICATION",
);
export const GET_NEXT_SCENE_QUESTION_APPLICATION = Symbol(
  "GET_NEXT_SCENE_QUESTION_APPLICATION",
);
export const GENERATE_SCENE_DRAFT_APPLICATION = Symbol(
  "GENERATE_SCENE_DRAFT_APPLICATION",
);
export const REVIEW_SCENE_EDIT_APPLICATION = Symbol(
  "REVIEW_SCENE_EDIT_APPLICATION",
);

@Controller("sources/scenes")
export class SceneFormationController {
  public constructor(
    @Inject(AUTHENTICATE_ACCESS_TOKEN)
    private readonly accessTokens: AccessTokenAuthenticator,
    @Inject(GENERATE_SCENE_RECOMMENDATIONS_APPLICATION)
    private readonly recommendations: Pick<
      GenerateSceneRecommendations,
      "execute"
    >,
    @Inject(GET_NEXT_SCENE_QUESTION_APPLICATION)
    private readonly questions: Pick<GetNextSceneQuestion, "execute">,
    @Inject(GENERATE_SCENE_DRAFT_APPLICATION)
    private readonly drafts: Pick<GenerateSceneDraft, "execute">,
    @Inject(REVIEW_SCENE_EDIT_APPLICATION)
    private readonly reviews: Pick<ReviewSceneEdit, "execute">,
  ) {}
  @Post("recommendations")
  @HttpCode(200)
  public async recommend(
    @Body() body: unknown,
    @Headers("authorization") auth: string | undefined,
    @Headers("x-request-id") requestId: string,
  ) {
    const accountId = await this.account(auth);
    const parsed = sceneRecommendationRequestSchema.safeParse(body);
    if (!parsed.success) throw invalid(requestId);
    return sceneRecommendationResultSchema.parse(
      await this.recommendations.execute({ accountId, ...parsed.data }),
    );
  }
  @Post("next-question")
  @HttpCode(200)
  public async nextQuestion(
    @Body() body: unknown,
    @Headers("authorization") auth: string | undefined,
    @Headers("x-request-id") requestId: string,
  ) {
    const accountId = await this.account(auth);
    const parsed = sceneNextQuestionRequestSchema.safeParse(body);
    if (!parsed.success) throw invalid(requestId);
    return sceneNextQuestionResultSchema.parse(
      await this.questions.execute({ accountId, ...parsed.data }),
    );
  }
  @Post("drafts")
  @HttpCode(200)
  public async draft(
    @Body() body: unknown,
    @Headers("authorization") auth: string | undefined,
    @Headers("x-request-id") requestId: string,
  ) {
    const accountId = await this.account(auth);
    const parsed = sceneDraftRequestSchema.safeParse(body);
    if (!parsed.success) throw invalid(requestId);
    return sceneDraftResultSchema.parse(
      await this.drafts.execute({ accountId, ...parsed.data }),
    );
  }
  @Post("edit-reviews")
  @HttpCode(200)
  public async review(
    @Body() body: unknown,
    @Headers("authorization") auth: string | undefined,
    @Headers("x-request-id") requestId: string,
  ) {
    const accountId = await this.account(auth);
    const parsed = sceneEditReviewRequestSchema.safeParse(body);
    if (!parsed.success) throw invalid(requestId);
    return sceneEditReviewResultSchema.parse(
      await this.reviews.execute({ accountId, ...parsed.data }),
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
const invalid = (requestId: string) =>
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
