import {
  Controller,
  Get,
  Headers,
  HttpCode,
  Inject,
  Post,
  UnauthorizedException,
} from "@nestjs/common";
import type {
  ListAppMessages,
  MarkAllAppMessagesRead,
} from "@reallanguage/backend-modules/messaging";
import {
  appMessagesReadAllResponseSchema,
  appMessagesResponseSchema,
  type AppMessagesReadAllResponse,
  type AppMessagesResponse,
} from "@reallanguage/contracts";
import {
  AUTHENTICATE_ACCESS_TOKEN,
  type AccessTokenAuthenticator,
} from "./initial-settings.controller.js";

export const APP_MESSAGES_APPLICATION = Symbol("APP_MESSAGES_APPLICATION");
export const MARK_ALL_APP_MESSAGES_READ_APPLICATION = Symbol(
  "MARK_ALL_APP_MESSAGES_READ_APPLICATION",
);
type AppMessagesApplication = Pick<ListAppMessages, "execute">;
type MarkAllMessagesApplication = Pick<MarkAllAppMessagesRead, "execute">;

@Controller("messages")
export class AppMessagesController {
  public constructor(
    @Inject(AUTHENTICATE_ACCESS_TOKEN)
    private readonly accessTokens: AccessTokenAuthenticator,
    @Inject(APP_MESSAGES_APPLICATION)
    private readonly messages: AppMessagesApplication,
    @Inject(MARK_ALL_APP_MESSAGES_READ_APPLICATION)
    private readonly markAllMessagesRead: MarkAllMessagesApplication,
  ) {}
  @Get()
  public async list(
    @Headers("authorization") authorization: string | undefined,
  ): Promise<AppMessagesResponse> {
    const bearer = authorization?.startsWith("Bearer ")
      ? authorization.slice(7).trim()
      : "";
    if (!bearer) throw new UnauthorizedException();
    const accountId = await this.accessTokens.authenticate(bearer);
    if (!accountId) throw new UnauthorizedException();
    const messages = await this.messages.execute({ accountId });
    return appMessagesResponseSchema.parse({
      messages: messages.map((message) => ({
        ...message,
        createdAt: message.createdAt.toISOString(),
      })),
    });
  }

  @Post("read-all")
  @HttpCode(200)
  public async markAllRead(
    @Headers("authorization") authorization: string | undefined,
  ): Promise<AppMessagesReadAllResponse> {
    const bearer = authorization?.startsWith("Bearer ")
      ? authorization.slice(7).trim()
      : "";
    if (!bearer) throw new UnauthorizedException();
    const accountId = await this.accessTokens.authenticate(bearer);
    if (!accountId) throw new UnauthorizedException();
    return appMessagesReadAllResponseSchema.parse(
      await this.markAllMessagesRead.execute({ accountId }),
    );
  }
}
