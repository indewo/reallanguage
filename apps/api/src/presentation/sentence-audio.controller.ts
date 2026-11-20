import { BadRequestException, Body, Controller, Get, Header, Headers, Inject, NotFoundException, Param, Post, StreamableFile, UnauthorizedException } from "@nestjs/common";
import { sentenceAudioRequest, type SentenceAudioRequest } from "@reallanguage/contracts";
export type { SentenceAudioRequest } from "@reallanguage/contracts";
import { AUTHENTICATE_ACCESS_TOKEN, type AccessTokenAuthenticator } from "./initial-settings.controller.js";

export const SENTENCE_AUDIO_APPLICATION = Symbol("SENTENCE_AUDIO_APPLICATION");
export interface SentenceAudioApplication {
  render(accountId: string, request: SentenceAudioRequest): Promise<unknown>;
  read(accountId: string, objectRef: string): Promise<Buffer | null>;
}

@Controller("media/sentence-audio")
export class SentenceAudioController {
  constructor(
    @Inject(AUTHENTICATE_ACCESS_TOKEN) private readonly access: AccessTokenAuthenticator,
    @Inject(SENTENCE_AUDIO_APPLICATION) private readonly application: SentenceAudioApplication,
  ) {}
  private async owner(authorization: string | undefined) {
    const bearer = authorization?.startsWith("Bearer ") ? authorization.slice(7).trim() : "";
    if (!bearer) throw new UnauthorizedException();
    const owner = await this.access.authenticate(bearer);
    if (!owner) throw new UnauthorizedException();
    return owner;
  }
  @Post()
  async render(@Body() body: unknown, @Headers("authorization") authorization: string | undefined) {
    const account = await this.owner(authorization);
    const parsed = sentenceAudioRequest.safeParse(body);
    if (!parsed.success) throw new BadRequestException("Unsupported sentence audio request");
    return this.application.render(account, parsed.data);
  }
  @Get(":objectRef")
  @Header("Cache-Control", "private, no-store")
  async read(@Param("objectRef") objectRef: string, @Headers("authorization") authorization: string | undefined) {
    const account = await this.owner(authorization);
    if (!/^tts_[0-9a-f-]{36}$/u.test(objectRef)) throw new NotFoundException();
    const bytes = await this.application.read(account, objectRef);
    if (!bytes) throw new NotFoundException();
    return new StreamableFile(bytes, { type: "audio/wav", length: bytes.length, disposition: "inline" });
  }
  /** Existing mobile HTTP boundary carries JSON and performs account/connection checks. */
  @Get(":objectRef/payload")
  @Header("Cache-Control", "private, no-store")
  async payload(@Param("objectRef") objectRef: string, @Headers("authorization") authorization: string | undefined) {
    const account = await this.owner(authorization);
    if (!/^tts_[0-9a-f-]{36}$/u.test(objectRef)) throw new NotFoundException();
    const bytes = await this.application.read(account, objectRef);
    if (!bytes) throw new NotFoundException();
    return { audioBase64: bytes.toString("base64"), audioMimeType: "audio/wav" as const };
  }
}
