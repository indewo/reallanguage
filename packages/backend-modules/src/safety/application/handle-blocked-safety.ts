import type { SafetyRiskSpan } from "../infrastructure/kysely-content-safety-gateway.js";

export type DeidentificationEdit = Readonly<{
  originalSpan: Readonly<{ startCodePoint: number; endCodePoint: number }>;
  originalText: string;
  replacementText: string;
  reasonCode: string;
  explanationZh: string;
}>;

export type BlockedSafetySource = Readonly<{
  safetyCaseId: string;
  text: string;
  sourceType: "DIARY" | "RECORDING" | "AI_SCENE";
  contentShape: "ARTICLE" | "DIALOGUE";
  reasonCode: string;
  userReasonZh: string;
  riskSpans: readonly SafetyRiskSpan[];
}>;

export type DeidentifiedCandidate = Readonly<{
  sanitizedText: string;
  edits: readonly DeidentificationEdit[];
  preservedIntentSummaryZh: string;
  internalSafetyValid: true;
  providerOperationId: string;
}>;

export interface ContentDeidentificationProvider {
  deidentify(
    source: BlockedSafetySource,
    accountId: string,
  ): Promise<DeidentifiedCandidate>;
}

export type ManualReviewResult = Readonly<{
  outcome: "APPROVED" | "NOT_APPROVED";
  source: BlockedSafetySource;
}>;

export interface SafetyHandlingStore {
  loadManualReviewStatus(
    accountId: string,
    safetyCaseId: string,
    reviewRequestId: string,
  ): Promise<"PENDING" | "APPROVED" | "NOT_APPROVED" | null>;
  loadBlocked(
    accountId: string,
    safetyCaseId: string,
  ): Promise<BlockedSafetySource | null>;
  loadCandidate(
    accountId: string,
    safetyCaseId: string,
  ): Promise<DeidentifiedCandidate | null>;
  hasExpiredCandidate(
    accountId: string,
    safetyCaseId: string,
  ): Promise<boolean>;
  saveCandidate(
    accountId: string,
    safetyCaseId: string,
    candidate: DeidentifiedCandidate,
  ): Promise<void>;
  acceptCandidate(
    accountId: string,
    safetyCaseId: string,
    commandId: string,
  ): Promise<boolean>;
  returnToEdit(
    accountId: string,
    safetyCaseId: string,
    commandId: string,
  ): Promise<boolean>;
  submitManualReview(
    accountId: string,
    safetyCaseId: string,
    commandId: string,
  ): Promise<string | null>;
  loadManualReviewResult(
    accountId: string,
    reviewRequestId: string,
  ): Promise<ManualReviewResult | null>;
}

export class HandleBlockedSafety {
  public constructor(
    private readonly store: SafetyHandlingStore,
    private readonly provider: ContentDeidentificationProvider,
  ) {}

  public async prepare(accountId: string, safetyCaseId: string) {
    const source = await this.store.loadBlocked(accountId, safetyCaseId);
    if (!source) return null;
    let candidate = await this.store.loadCandidate(accountId, safetyCaseId);
    if (!candidate) {
      // An expired retained version is not the same as never generated. Until
      // its retention policy permits replacement, do not pay for an unsavable result.
      if (await this.store.hasExpiredCandidate(accountId, safetyCaseId))
        return null;
      candidate = await this.provider.deidentify(source, accountId);
      validateCandidate(source.text, candidate);
      await this.store.saveCandidate(accountId, safetyCaseId, candidate);
    }
    return { source, candidate };
  }

  public accept(accountId: string, safetyCaseId: string, commandId: string) {
    return this.store.acceptCandidate(accountId, safetyCaseId, commandId);
  }

  public returnToEdit(
    accountId: string,
    safetyCaseId: string,
    commandId: string,
  ) {
    return this.store.returnToEdit(accountId, safetyCaseId, commandId);
  }

  public async submitManualReview(
    accountId: string,
    safetyCaseId: string,
    commandId: string,
  ) {
    const reviewRequestId = await this.store.submitManualReview(
      accountId,
      safetyCaseId,
      commandId,
    );
    if (!reviewRequestId) return null;
    // A lost receipt may be retried after the review has already been decided.
    // Read saved state instead of treating every idempotent replay as pending.
    const reviewStatus = await this.store.loadManualReviewStatus(
      accountId,
      safetyCaseId,
      reviewRequestId,
    );
    return reviewStatus ? { reviewRequestId, reviewStatus } : null;
  }

  public manualReviewResult(accountId: string, reviewRequestId: string) {
    return this.store.loadManualReviewResult(accountId, reviewRequestId);
  }
}

const validateCandidate = (
  sourceText: string,
  candidate: DeidentifiedCandidate,
): void => {
  if (
    !candidate.sanitizedText ||
    !candidate.internalSafetyValid ||
    !candidate.preservedIntentSummaryZh ||
    !candidate.providerOperationId
  ) {
    throw new Error("Invalid deidentification result");
  }
  const source = Array.from(sourceText);
  if (candidate.edits.length === 0)
    throw new Error("Deidentification requires visible edits");
  for (const edit of candidate.edits) {
    const { startCodePoint, endCodePoint } = edit.originalSpan;
    if (
      !Number.isInteger(startCodePoint) ||
      !Number.isInteger(endCodePoint) ||
      startCodePoint < 0 ||
      endCodePoint <= startCodePoint ||
      endCodePoint > source.length ||
      source.slice(startCodePoint, endCodePoint).join("") !==
        edit.originalText ||
      !edit.replacementText ||
      !edit.reasonCode ||
      !edit.explanationZh
    ) {
      throw new Error("Deidentification edit does not reference source");
    }
  }
};
