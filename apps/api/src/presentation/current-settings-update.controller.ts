import {
  BadRequestException,
  Body,
  ConflictException,
  Controller,
  Get,
  Headers,
  HttpCode,
  Inject,
  NotFoundException,
  Put,
  UnauthorizedException,
} from "@nestjs/common";
import type {
  ReadVersionedSettings,
  UpdateCurrentSettings,
} from "@reallanguage/backend-modules/settings";
import {
  updateCurrentSettingsRequestSchema,
  updateCurrentSettingsResultSchema,
  versionedSettingsSchema,
} from "@reallanguage/contracts";
import {
  AUTHENTICATE_ACCESS_TOKEN,
  type AccessTokenAuthenticator,
} from "./initial-settings.controller.js";

export const READ_VERSIONED_SETTINGS_APPLICATION = Symbol(
  "READ_VERSIONED_SETTINGS_APPLICATION",
);
export const UPDATE_CURRENT_SETTINGS_APPLICATION = Symbol(
  "UPDATE_CURRENT_SETTINGS_APPLICATION",
);

@Controller("settings/current/revision")
export class CurrentSettingsUpdateController {
  public constructor(
    @Inject(AUTHENTICATE_ACCESS_TOKEN)
    private readonly accessTokens: AccessTokenAuthenticator,
    @Inject(READ_VERSIONED_SETTINGS_APPLICATION)
    private readonly reader: Pick<ReadVersionedSettings, "execute">,
    @Inject(UPDATE_CURRENT_SETTINGS_APPLICATION)
    private readonly updater: Pick<UpdateCurrentSettings, "execute">,
  ) {}
  @Get()
  public async current(
    @Headers("authorization") authorization: string | undefined,
  ) {
    const accountId = await this.owner(authorization);
    const current = await this.reader.execute({ accountId });
    if (!current) throw new NotFoundException();
    return versionedSettingsSchema.parse(current);
  }
  @Put()
  @HttpCode(200)
  public async update(
    @Headers("authorization") authorization: string | undefined,
    @Headers("x-request-id") requestId: string,
    @Body() body: unknown,
  ) {
    const accountId = await this.owner(authorization);
    const parsed = updateCurrentSettingsRequestSchema.safeParse(body);
    if (!parsed.success) throw new BadRequestException();
    const result = await this.updater.execute({ accountId, ...parsed.data });
    if (result.status === "NOT_FOUND") throw new NotFoundException();
    if (result.status === "CONFLICT")
      throw new ConflictException({
        error: {
          code: "SETTINGS_VERSION_CONFLICT",
          message: "设置已发生变化，请返回并重新读取后修改。",
          retryable: false,
          requestId,
          details: { rowVersion: result.rowVersion },
        },
      });
    return updateCurrentSettingsResultSchema.parse(result);
  }
  private async owner(authorization: string | undefined): Promise<string> {
    const bearer = authorization?.startsWith("Bearer ")
      ? authorization.slice(7).trim()
      : "";
    if (!bearer) throw new UnauthorizedException();
    const accountId = await this.accessTokens.authenticate(bearer);
    if (!accountId) throw new UnauthorizedException();
    return accountId;
  }
}
