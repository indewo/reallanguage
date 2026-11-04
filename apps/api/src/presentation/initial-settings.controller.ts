import {
  BadRequestException,
  Body,
  Controller,
  Headers,
  HttpCode,
  Inject,
  Get,
  Put,
  UnauthorizedException,
} from "@nestjs/common";
import type {
  CompleteInitialSettings,
  GetCurrentSettings,
} from "@reallanguage/backend-modules/settings";
import {
  apiErrorResponseSchema,
  initialSettingsCompletionRequestSchema,
  initialSettingsCompletionResultSchema,
  currentSettingsSchema,
  type CurrentSettings,
  type InitialSettingsCompletionResult,
} from "@reallanguage/contracts";

export const AUTHENTICATE_ACCESS_TOKEN = Symbol("AUTHENTICATE_ACCESS_TOKEN");
export const COMPLETE_INITIAL_SETTINGS_APPLICATION = Symbol(
  "COMPLETE_INITIAL_SETTINGS_APPLICATION",
);
export const GET_CURRENT_SETTINGS_APPLICATION = Symbol(
  "GET_CURRENT_SETTINGS_APPLICATION",
);

type CompleteInitialSettingsApplication = Pick<
  CompleteInitialSettings,
  "execute"
>;
type GetCurrentSettingsApplication = Pick<GetCurrentSettings, "execute">;

export interface AccessTokenAuthenticator {
  authenticate(bearer: string): Promise<string | null>;
}

@Controller("settings/current")
export class InitialSettingsController {
  public constructor(
    @Inject(AUTHENTICATE_ACCESS_TOKEN)
    private readonly accessTokens: AccessTokenAuthenticator,
    @Inject(COMPLETE_INITIAL_SETTINGS_APPLICATION)
    private readonly completeInitialSettings: CompleteInitialSettingsApplication,
    @Inject(GET_CURRENT_SETTINGS_APPLICATION)
    private readonly getCurrentSettings: GetCurrentSettingsApplication,
  ) {}

  @Get()
  public async current(
    @Headers("authorization") authorization: string | undefined,
  ): Promise<CurrentSettings> {
    const bearer = parseBearer(authorization);
    if (!bearer) throw new UnauthorizedException();
    const accountId = await this.accessTokens.authenticate(bearer);
    if (!accountId) throw new UnauthorizedException();
    return currentSettingsSchema.parse(
      await this.getCurrentSettings.execute({ accountId }),
    );
  }

  @Put("initial")
  @HttpCode(200)
  public async complete(
    @Body() body: unknown,
    @Headers("authorization") authorization: string | undefined,
    @Headers("x-request-id") requestId: string,
  ): Promise<InitialSettingsCompletionResult> {
    const bearer = parseBearer(authorization);
    if (!bearer) throw new UnauthorizedException();
    const accountId = await this.accessTokens.authenticate(bearer);
    if (!accountId) throw new UnauthorizedException();
    const parsed = initialSettingsCompletionRequestSchema.safeParse(body);
    if (!parsed.success) throw validationError(requestId);
    return initialSettingsCompletionResultSchema.parse(
      await this.completeInitialSettings.execute({
        accountId,
        settings: parsed.data,
      }),
    );
  }
}

const parseBearer = (authorization: string | undefined): string | null => {
  if (!authorization?.startsWith("Bearer ")) return null;
  const bearer = authorization.slice("Bearer ".length).trim();
  return bearer || null;
};

const validationError = (requestId: string): BadRequestException =>
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
