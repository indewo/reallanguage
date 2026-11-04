import {
  BadRequestException,
  Body,
  Controller,
  Headers,
  Get,
  HttpCode,
  Inject,
  NotFoundException,
  Param,
  Post,
  UnauthorizedException,
} from "@nestjs/common";
import type { HandleBlockedSafety } from "@reallanguage/backend-modules/safety";
import {
  apiErrorResponseSchema,
  manualReviewSubmissionResultSchema,
  safetyHandlingCommandResultSchema,
  safetyHandlingCommandSchema,
  safetyHandlingDetailsSchema,
  manualReviewResultSchema,
} from "@reallanguage/contracts";
import {
  AUTHENTICATE_ACCESS_TOKEN,
  type AccessTokenAuthenticator,
} from "./initial-settings.controller.js";

export const HANDLE_BLOCKED_SAFETY_APPLICATION = Symbol(
  "HANDLE_BLOCKED_SAFETY_APPLICATION",
);

@Controller("safety/cases")
export class SafetyHandlingController {
  public constructor(
    @Inject(AUTHENTICATE_ACCESS_TOKEN)
    private readonly accessTokens: AccessTokenAuthenticator,
    @Inject(HANDLE_BLOCKED_SAFETY_APPLICATION)
    private readonly handling: HandleBlockedSafety,
  ) {}

  @Post(":safetyCaseId/handling")
  @HttpCode(200)
  public async prepare(
    @Param("safetyCaseId") safetyCaseId: string,
    @Headers("authorization") authorization: string | undefined,
  ) {
    const accountId = await this.account(authorization);
    const result = await this.handling.prepare(accountId, safetyCaseId);
    if (!result) throw new NotFoundException();
    return safetyHandlingDetailsSchema.parse({
      safetyCaseId,
      originalSource: {
        text: result.source.text,
        sourceType: result.source.sourceType,
        contentShape: result.source.contentShape,
      },
      userReasonZh: result.source.userReasonZh,
      deidentifiedSource: {
        text: result.candidate.sanitizedText,
        sourceType: result.source.sourceType,
        contentShape: result.source.contentShape,
      },
      edits: result.candidate.edits.map((edit) => ({
        originalText: edit.originalText,
        replacementText: edit.replacementText,
        explanationZh: edit.explanationZh,
      })),
    });
  }

  @Get("manual-reviews/:reviewRequestId")
  public async result(
    @Param("reviewRequestId") reviewRequestId: string,
    @Headers("authorization") authorization: string | undefined,
  ) {
    const accountId = await this.account(authorization);
    const result = await this.handling.manualReviewResult(
      accountId,
      reviewRequestId,
    );
    if (!result) throw new NotFoundException();
    return manualReviewResultSchema.parse({
      outcome: result.outcome,
      source: {
        safetyCaseId: result.source.safetyCaseId,
        text: result.source.text,
        sourceType: result.source.sourceType,
        contentShape: result.source.contentShape,
        userReasonZh: result.source.userReasonZh,
      },
    });
  }

  @Post(":safetyCaseId/accept-deidentified")
  @HttpCode(200)
  public async accept(
    @Param("safetyCaseId") safetyCaseId: string,
    @Body() body: unknown,
    @Headers("authorization") authorization: string | undefined,
    @Headers("x-request-id") requestId: string,
  ) {
    const accountId = await this.account(authorization);
    const command = parseCommand(body, requestId);
    if (
      !(await this.handling.accept(accountId, safetyCaseId, command.commandId))
    )
      throw new NotFoundException();
    return safetyHandlingCommandResultSchema.parse({ status: "COMPLETED" });
  }

  @Post(":safetyCaseId/return-to-edit")
  @HttpCode(200)
  public async returnToEdit(
    @Param("safetyCaseId") safetyCaseId: string,
    @Body() body: unknown,
    @Headers("authorization") authorization: string | undefined,
    @Headers("x-request-id") requestId: string,
  ) {
    const accountId = await this.account(authorization);
    const command = parseCommand(body, requestId);
    if (
      !(await this.handling.returnToEdit(
        accountId,
        safetyCaseId,
        command.commandId,
      ))
    )
      throw new NotFoundException();
    return safetyHandlingCommandResultSchema.parse({ status: "COMPLETED" });
  }

  @Post(":safetyCaseId/manual-reviews")
  @HttpCode(200)
  public async review(
    @Param("safetyCaseId") safetyCaseId: string,
    @Body() body: unknown,
    @Headers("authorization") authorization: string | undefined,
    @Headers("x-request-id") requestId: string,
  ) {
    const accountId = await this.account(authorization);
    const command = parseCommand(body, requestId);
    const result = await this.handling.submitManualReview(
      accountId,
      safetyCaseId,
      command.commandId,
    );
    if (!result) throw new NotFoundException();
    return manualReviewSubmissionResultSchema.parse({
      status: "SUBMITTED",
      ...result,
    });
  }

  private async account(authorization: string | undefined): Promise<string> {
    const bearer = authorization?.startsWith("Bearer ")
      ? authorization.slice(7).trim()
      : "";
    if (!bearer) throw new UnauthorizedException();
    const accountId = await this.accessTokens.authenticate(bearer);
    if (!accountId) throw new UnauthorizedException();
    return accountId;
  }
}

const parseCommand = (body: unknown, requestId: string) => {
  const parsed = safetyHandlingCommandSchema.safeParse(body);
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
  return parsed.data;
};
