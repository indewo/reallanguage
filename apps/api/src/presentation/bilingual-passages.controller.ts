import {
  BadRequestException,
  Body,
  Controller,
  Delete,
  Get,
  Headers,
  HttpCode,
  Inject,
  Post,
  Query,
  Res,
  UnauthorizedException,
  type OnModuleDestroy,
} from "@nestjs/common";
import {
  bilingualPassagesRequestSchema,
  bilingualPassagesResponseSchema,
} from "@reallanguage/contracts";
import type { FastifyReply } from "fastify";
import {
  AUTHENTICATE_ACCESS_TOKEN,
  type AccessTokenAuthenticator,
} from "./initial-settings.controller.js";
import {
  BilingualJobs,
  type BilingualGenerator,
} from "../production/bilingual-jobs.js";

export const BILINGUAL_PASSAGES_APPLICATION = Symbol(
  "BILINGUAL_PASSAGES_APPLICATION",
);
@Controller("content-formation")
export class BilingualPassagesController implements OnModuleDestroy {
  private readonly jobs: BilingualJobs;
  constructor(
    @Inject(AUTHENTICATE_ACCESS_TOKEN)
    private readonly auth: AccessTokenAuthenticator,
    @Inject(BILINGUAL_PASSAGES_APPLICATION) generate: BilingualGenerator,
  ) {
    this.jobs = new BilingualJobs(generate);
  }
  onModuleDestroy() {
    this.jobs.close();
  }
  private async account(auth?: string) {
    const token = auth?.startsWith("Bearer ") ? auth.slice(7).trim() : "";
    if (!token) throw new UnauthorizedException();
    const account = await this.auth.authenticate(token);
    if (!account) throw new UnauthorizedException();
    return account;
  }
  private pending(reply: FastifyReply, commandId: string) {
    reply
      .code(202)
      .header("Retry-After", "2")
      .header("Cache-Control", "no-store");
    return { status: "PROCESSING", commandId };
  }
  @Post("bilingual-passages")
  @HttpCode(200)
  async passages(
    @Body() body: unknown,
    @Headers("authorization") auth: string | undefined,
    @Headers("prefer") prefer: string | undefined,
    @Res({ passthrough: true }) reply: FastifyReply,
  ) {
    const accountId = await this.account(auth),
      parsed = bilingualPassagesRequestSchema.safeParse(body);
    if (!parsed.success) throw new BadRequestException("输入内容无效。");
    const job = this.jobs.start({ ...parsed.data, accountId });
    if (
      prefer
        ?.split(",")
        .some((v) => v.trim().toLowerCase() === "respond-async") &&
      !job.done
    )
      return this.pending(reply, parsed.data.commandId);
    return bilingualPassagesResponseSchema.parse(await job.promise);
  }
  @Get("bilingual-passages")
  async poll(
    @Query("commandId") commandId: string,
    @Headers("authorization") auth: string | undefined,
    @Res({ passthrough: true }) reply: FastifyReply,
  ) {
    const accountId = await this.account(auth);
    if (!commandId) throw new BadRequestException();
    const job = this.jobs.poll(accountId, commandId);
    return job.done
      ? bilingualPassagesResponseSchema.parse(await job.promise)
      : this.pending(reply, commandId);
  }
  @Delete("bilingual-passages")
  @HttpCode(204)
  async cancel(
    @Query("commandId") commandId: string,
    @Headers("authorization") auth: string | undefined,
  ) {
    const accountId = await this.account(auth);
    if (!commandId) throw new BadRequestException();
    this.jobs.cancel(accountId, commandId);
  }
}
