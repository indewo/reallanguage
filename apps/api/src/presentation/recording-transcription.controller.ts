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
  RepairTranscript,
  TranscribeRecording,
} from "@reallanguage/backend-modules/media";
import {
  apiErrorResponseSchema,
  recordingTranscriptionRequestSchema,
  recordingTranscriptionResultSchema,
  transcriptRepairRequestSchema,
  transcriptRepairResultSchema,
} from "@reallanguage/contracts";
import {
  AUTHENTICATE_ACCESS_TOKEN,
  type AccessTokenAuthenticator,
} from "./initial-settings.controller.js";

export const RECORDING_TRANSCRIPTION_APPLICATION = Symbol(
  "RECORDING_TRANSCRIPTION_APPLICATION",
);
export const TRANSCRIPT_REPAIR_APPLICATION = Symbol(
  "TRANSCRIPT_REPAIR_APPLICATION",
);

@Controller("media")
export class RecordingTranscriptionController {
  public constructor(
    @Inject(AUTHENTICATE_ACCESS_TOKEN)
    private readonly accessTokens: AccessTokenAuthenticator,
    @Inject(RECORDING_TRANSCRIPTION_APPLICATION)
    private readonly transcription: Pick<TranscribeRecording, "execute">,
    @Inject(TRANSCRIPT_REPAIR_APPLICATION)
    private readonly repair: Pick<RepairTranscript, "execute">,
  ) {}

  @Post("recording-transcriptions")
  @HttpCode(200)
  public async transcribe(
    @Body() body: unknown,
    @Headers("authorization") authorization: string | undefined,
    @Headers("x-request-id") requestId: string,
  ) {
    const accountId = await this.authenticate(authorization);
    const parsed = recordingTranscriptionRequestSchema.safeParse(body);
    if (!parsed.success) throw this.badRequest(requestId);
    return recordingTranscriptionResultSchema.parse(
      await this.transcription.execute({ accountId, ...parsed.data }),
    );
  }

  @Post("transcript-repairs")
  @HttpCode(200)
  public async repairTranscript(
    @Body() body: unknown,
    @Headers("authorization") authorization: string | undefined,
    @Headers("x-request-id") requestId: string,
  ) {
    const accountId = await this.authenticate(authorization);
    const parsed = transcriptRepairRequestSchema.safeParse(body);
    if (!parsed.success) throw this.badRequest(requestId);
    return transcriptRepairResultSchema.parse(
      await this.repair.execute({ accountId, ...parsed.data }),
    );
  }

  private async authenticate(
    authorization: string | undefined,
  ): Promise<string> {
    const bearer = authorization?.startsWith("Bearer ")
      ? authorization.slice(7).trim()
      : "";
    if (!bearer) throw new UnauthorizedException();
    const accountId = await this.accessTokens.authenticate(bearer);
    if (!accountId) throw new UnauthorizedException();
    return accountId;
  }

  private badRequest(requestId: string): BadRequestException {
    return new BadRequestException(
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
