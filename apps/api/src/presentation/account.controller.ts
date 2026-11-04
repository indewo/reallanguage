import {
  BadRequestException,
  Body,
  Controller,
  Delete,
  Get,
  Headers,
  HttpCode,
  Inject,
  NotFoundException,
  UnauthorizedException,
} from "@nestjs/common";
import type {
  DeleteAccount,
  GetAccountProfile,
} from "@reallanguage/backend-modules/identity";
import {
  accountDeletionCommandSchema,
  accountDeletionResultSchema,
  accountProfileSchema,
} from "@reallanguage/contracts";
import {
  AUTHENTICATE_ACCESS_TOKEN,
  type AccessTokenAuthenticator,
} from "./initial-settings.controller.js";
export const ACCOUNT_PROFILE_APPLICATION = Symbol(
  "ACCOUNT_PROFILE_APPLICATION",
);
export const ACCOUNT_DELETION_APPLICATION = Symbol(
  "ACCOUNT_DELETION_APPLICATION",
);
type AccountProfileApplication = Pick<GetAccountProfile, "execute">;
type AccountDeletionApplication = Pick<DeleteAccount, "execute">;
@Controller("account/current")
export class AccountController {
  public constructor(
    @Inject(AUTHENTICATE_ACCESS_TOKEN)
    private readonly accessTokens: AccessTokenAuthenticator,
    @Inject(ACCOUNT_PROFILE_APPLICATION)
    private readonly profile: AccountProfileApplication,
    @Inject(ACCOUNT_DELETION_APPLICATION)
    private readonly deletion: AccountDeletionApplication,
  ) {}
  @Get()
  public async current(
    @Headers("authorization") authorization: string | undefined,
  ) {
    const bearer = authorization?.startsWith("Bearer ")
      ? authorization.slice(7).trim()
      : "";
    if (!bearer) throw new UnauthorizedException();
    const accountId = await this.accessTokens.authenticate(bearer);
    if (!accountId) throw new UnauthorizedException();
    const profile = await this.profile.execute({ accountId });
    if (!profile) throw new NotFoundException();
    return accountProfileSchema.parse(profile);
  }
  @Delete()
  @HttpCode(200)
  public async delete(
    @Body() body: unknown,
    @Headers("authorization") authorization: string | undefined,
  ) {
    const bearer = authorization?.startsWith("Bearer ")
      ? authorization.slice(7).trim()
      : "";
    if (!bearer) throw new UnauthorizedException();
    const accountId = await this.accessTokens.authenticate(bearer);
    if (!accountId) throw new UnauthorizedException();
    const command = accountDeletionCommandSchema.safeParse(body);
    if (!command.success) throw new BadRequestException();
    const result = await this.deletion.execute({
      accountId,
      commandId: command.data.commandId,
    });
    if (!result) throw new NotFoundException();
    return accountDeletionResultSchema.parse(result);
  }
}
