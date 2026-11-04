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
  EvaluateFullSentenceSpeaking,
  GenerateSpeakingTranscription,
} from "@reallanguage/backend-modules/learning";
import {
  apiErrorResponseSchema,
  diaryDictationRequestSchema,
  diaryDictationResultSchema,
  fullSentenceSpeakingEvaluationRequestSchema,
  fullSentenceSpeakingEvaluationResultSchema,
  speakingTranscriptionRequestSchema,
  speakingTranscriptionResultSchema,
} from "@reallanguage/contracts";
import {
  AUTHENTICATE_ACCESS_TOKEN,
  type AccessTokenAuthenticator,
} from "./initial-settings.controller.js";

export const GENERATE_SPEAKING_TRANSCRIPTION_APPLICATION = Symbol(
  "GENERATE_SPEAKING_TRANSCRIPTION_APPLICATION",
);
export const EVALUATE_FULL_SENTENCE_SPEAKING_APPLICATION = Symbol(
  "EVALUATE_FULL_SENTENCE_SPEAKING_APPLICATION",
);

@Controller("learning")
export class FullSentenceSpeakingController {
  public constructor(
    @Inject(AUTHENTICATE_ACCESS_TOKEN)
    private readonly accessTokens: AccessTokenAuthenticator,
    @Inject(GENERATE_SPEAKING_TRANSCRIPTION_APPLICATION)
    private readonly transcription: Pick<
      GenerateSpeakingTranscription,
      "execute"
    >,
    @Inject(EVALUATE_FULL_SENTENCE_SPEAKING_APPLICATION)
    private readonly evaluation: Pick<EvaluateFullSentenceSpeaking, "execute">,
  ) {}

  @Post("speaking-transcriptions")
  @HttpCode(200)
  public async transcribe(
    @Body() body: unknown,
    @Headers("authorization") authorization: string | undefined,
    @Headers("x-request-id") requestId: string,
  ) {
    const accountId = await this.authenticate(authorization);
    const parsed = speakingTranscriptionRequestSchema.safeParse(body);
    if (!parsed.success) this.invalid(requestId);
    return speakingTranscriptionResultSchema.parse(
      await this.transcription.execute({ accountId, ...parsed.data! }),
    );
  }

  @Post("diary-dictations")
  @HttpCode(200)
  public async transcribeDiary(
    @Body() body: unknown,
    @Headers("authorization") authorization: string | undefined,
    @Headers("x-request-id") requestId: string,
  ) {
    const accountId = await this.authenticate(authorization);
    const parsed = diaryDictationRequestSchema.safeParse(body);
    if (!parsed.success) this.invalid(requestId);
    return diaryDictationResultSchema.parse(
      await this.transcription.execute({
        accountId,
        chapterId: "diary-input",
        sentenceId: parsed.data!.commandId,
        ...parsed.data!,
      }),
    );
  }

  @Post("full-sentence-speaking-evaluations")
  @HttpCode(200)
  public async evaluate(
    @Body() body: unknown,
    @Headers("authorization") authorization: string | undefined,
    @Headers("x-request-id") requestId: string,
  ) {
    const accountId = await this.authenticate(authorization);
    const parsed = fullSentenceSpeakingEvaluationRequestSchema.safeParse(body);
    if (!parsed.success) this.invalid(requestId);
    return fullSentenceSpeakingEvaluationResultSchema.parse(
      await this.evaluation.execute({ accountId, ...parsed.data! }),
    );
  }

  private async authenticate(authorization: string | undefined) {
    const bearer = authorization?.startsWith("Bearer ")
      ? authorization.slice(7).trim()
      : "";
    if (!bearer) throw new UnauthorizedException();
    const accountId = await this.accessTokens.authenticate(bearer);
    if (!accountId) throw new UnauthorizedException();
    return accountId;
  }

  private invalid(requestId: string): never {
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
}
