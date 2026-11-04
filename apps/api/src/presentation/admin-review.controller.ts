import {
  BadRequestException,
  Body,
  Controller,
  Get,
  Header,
  Headers,
  HttpCode,
  Inject,
  NotFoundException,
  Param,
  Post,
  UnauthorizedException,
} from "@nestjs/common";
import type {
  AdminReviewDecision,
  DecideAdminReview,
  GetPendingAdminReview,
  ListPendingAdminReviews,
} from "@reallanguage/backend-modules/admin";

import { ADMIN_SESSION_AUTHENTICATOR } from "./admin-session.controller.js";

export const ADMIN_REVIEW_LIST_APPLICATION = Symbol(
  "ADMIN_REVIEW_LIST_APPLICATION",
);
export const ADMIN_REVIEW_DETAILS_APPLICATION = Symbol(
  "ADMIN_REVIEW_DETAILS_APPLICATION",
);
export const ADMIN_REVIEW_DECISION_APPLICATION = Symbol(
  "ADMIN_REVIEW_DECISION_APPLICATION",
);

type AdminAuthenticator = {
  execute(
    bearer: string,
  ): Promise<
    | { status: "AUTHENTICATED"; adminId: string }
    | { status: "INVALID_OR_EXPIRED" }
  >;
};

@Controller("admin/manual-reviews")
export class AdminReviewController {
  public constructor(
    @Inject(ADMIN_SESSION_AUTHENTICATOR)
    private readonly authenticate: AdminAuthenticator,
    @Inject(ADMIN_REVIEW_LIST_APPLICATION)
    private readonly listApplication: Pick<ListPendingAdminReviews, "execute">,
    @Inject(ADMIN_REVIEW_DETAILS_APPLICATION)
    private readonly detailsApplication: Pick<GetPendingAdminReview, "execute">,
    @Inject(ADMIN_REVIEW_DECISION_APPLICATION)
    private readonly decisionApplication: Pick<DecideAdminReview, "execute">,
  ) {}

  @Get()
  @Header("Cache-Control", "no-store")
  public async list(@Headers("authorization") authorization?: string) {
    await this.admin(authorization);
    return { items: await this.listApplication.execute() };
  }

  @Get(":reviewRequestId")
  @Header("Cache-Control", "no-store")
  public async details(
    @Param("reviewRequestId") reviewRequestId: string,
    @Headers("authorization") authorization: string | undefined,
    @Headers("x-request-id") requestId: string,
  ) {
    const adminId = await this.admin(authorization);
    const result = await this.detailsApplication.execute(
      adminId,
      reviewRequestId,
      requestId,
    );
    if (!result) throw new NotFoundException();
    return result;
  }

  @Post(":reviewRequestId/decisions")
  @HttpCode(200)
  @Header("Cache-Control", "no-store")
  public async decide(
    @Param("reviewRequestId") reviewRequestId: string,
    @Body() body: unknown,
    @Headers("authorization") authorization: string | undefined,
    @Headers("x-request-id") requestId: string,
  ) {
    const adminId = await this.admin(authorization);
    if (!isDecisionBody(body)) throw new BadRequestException();
    const result = await this.decisionApplication.execute({
      adminId,
      reviewRequestId,
      decision: body.decision,
      reasonCode: body.reasonCode,
      reasonZh: body.reasonZh,
      requestId,
    });
    if (result.status === "INVALID_REASON") throw new BadRequestException();
    if (result.status === "NOT_PENDING") throw new NotFoundException();
    return result;
  }

  private async admin(authorization?: string): Promise<string> {
    const bearer = parseBearer(authorization);
    if (!bearer) throw new UnauthorizedException();
    const result = await this.authenticate.execute(bearer);
    if (result.status !== "AUTHENTICATED") throw new UnauthorizedException();
    return result.adminId;
  }
}

const decisions = new Set<AdminReviewDecision>([
  "APPROVE",
  "KEEP_BLOCKED",
  "REQUIRES_DEIDENTIFICATION",
]);

const isDecisionBody = (
  value: unknown,
): value is {
  decision: AdminReviewDecision;
  reasonCode: string;
  reasonZh: string;
} =>
  typeof value === "object" &&
  value !== null &&
  "decision" in value &&
  decisions.has(value.decision as AdminReviewDecision) &&
  "reasonCode" in value &&
  typeof value.reasonCode === "string" &&
  value.reasonCode.length <= 64 &&
  "reasonZh" in value &&
  typeof value.reasonZh === "string" &&
  value.reasonZh.length <= 500;

const parseBearer = (value?: string): string | null => {
  if (!value?.startsWith("Bearer ")) return null;
  const bearer = value.slice("Bearer ".length);
  return /^[A-Za-z0-9_-]{8,256}$/u.test(bearer) ? bearer : null;
};
