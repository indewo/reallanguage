export type AdminReviewDecision =
  "APPROVE" | "KEEP_BLOCKED" | "REQUIRES_DEIDENTIFICATION";

export type PendingAdminReview = Readonly<{
  reviewRequestId: string;
  sourceType: "DIARY" | "RECORDING" | "AI_SCENE";
  reasonCode: string;
  userReasonZh: string;
  createdAt: Date;
}>;

export type PendingAdminReviewDetails = PendingAdminReview &
  Readonly<{ contentShape: "ARTICLE" | "DIALOGUE"; text: string }>;

export interface AdminReviewStore {
  listPending(): Promise<readonly PendingAdminReview[]>;
  loadPendingDetails(
    adminId: string,
    reviewRequestId: string,
    requestId: string,
  ): Promise<PendingAdminReviewDetails | null>;
  decide(input: {
    adminId: string;
    reviewRequestId: string;
    decision: AdminReviewDecision;
    reasonCode: string;
    reasonZh: string;
    requestId: string;
  }): Promise<boolean>;
}

export class ListPendingAdminReviews {
  public constructor(private readonly store: AdminReviewStore) {}
  public execute() {
    return this.store.listPending();
  }
}

export class GetPendingAdminReview {
  public constructor(private readonly store: AdminReviewStore) {}
  public execute(adminId: string, reviewRequestId: string, requestId: string) {
    return this.store.loadPendingDetails(adminId, reviewRequestId, requestId);
  }
}

export class DecideAdminReview {
  public constructor(private readonly store: AdminReviewStore) {}

  public async execute(input: {
    adminId: string;
    reviewRequestId: string;
    decision: AdminReviewDecision;
    reasonCode: string;
    reasonZh: string;
    requestId: string;
  }) {
    const reasonCode = input.reasonCode.trim();
    const reasonZh = input.reasonZh.trim();
    if (
      !/^[A-Z][A-Z0-9_]{1,63}$/u.test(reasonCode) ||
      reasonZh.length < 2 ||
      reasonZh.length > 500
    ) {
      return { status: "INVALID_REASON" } as const;
    }
    const completed = await this.store.decide({
      ...input,
      reasonCode,
      reasonZh,
    });
    return completed
      ? ({ status: "COMPLETED" } as const)
      : ({ status: "NOT_PENDING" } as const);
  }
}
