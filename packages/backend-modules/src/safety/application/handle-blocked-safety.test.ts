import { describe, expect, it, vi } from "vitest";
import {
  HandleBlockedSafety,
  type SafetyHandlingStore,
} from "./handle-blocked-safety.js";

const source = {
  safetyCaseId: "case",
  text: "联系张三",
  sourceType: "DIARY" as const,
  contentShape: "ARTICLE" as const,
  reasonCode: "RISK",
  userReasonZh: "包含具体身份",
  riskSpans: [
    { startCodePoint: 2, endCodePoint: 4, text: "张三", reasonCode: "RISK" },
  ],
};
const candidate = {
  sanitizedText: "联系某人",
  edits: [
    {
      originalSpan: { startCodePoint: 2, endCodePoint: 4 },
      originalText: "张三",
      replacementText: "某人",
      reasonCode: "RISK",
      explanationZh: "隐藏具体姓名",
    },
  ],
  preservedIntentSummaryZh: "保留联系某人的意图",
  internalSafetyValid: true as const,
  providerOperationId: "provider-1",
};

describe("HandleBlockedSafety", () => {
  it.each(["PENDING", "APPROVED", "NOT_APPROVED"] as const)(
    "reads %s after submitting or replaying the stable command",
    async (reviewStatus) => {
      const store = fakeStore();
      vi.mocked(store.loadManualReviewStatus).mockResolvedValue(reviewStatus);
      const provider = { deidentify: vi.fn() };
      expect(
        await new HandleBlockedSafety(store, provider).submitManualReview(
          "account",
          "case",
          "stable-command",
        ),
      ).toEqual({ reviewRequestId: "review-1", reviewStatus });
      expect(store.loadManualReviewStatus).toHaveBeenCalledWith(
        "account",
        "case",
        "review-1",
      );
      expect(provider.deidentify).not.toHaveBeenCalled();
    },
  );
  it("does not report a pending submission when the saved status cannot be read", async () => {
    const store = fakeStore();
    vi.mocked(store.loadManualReviewStatus).mockResolvedValue(null);
    expect(
      await new HandleBlockedSafety(store, {
        deidentify: vi.fn(),
      }).submitManualReview("account", "case", "stable-command"),
    ).toBeNull();
  });
  it("does not pay to replace an expired retained candidate", async () => {
    const store = fakeStore();
    vi.mocked(store.hasExpiredCandidate).mockResolvedValue(true);
    const provider = { deidentify: vi.fn() };
    expect(
      await new HandleBlockedSafety(store, provider).prepare("account", "case"),
    ).toBeNull();
    expect(provider.deidentify).not.toHaveBeenCalled();
    expect(store.saveCandidate).not.toHaveBeenCalled();
  });

  it("creates and reuses one internally validated candidate", async () => {
    const store = fakeStore();
    const provider = { deidentify: vi.fn().mockResolvedValue(candidate) };
    const handling = new HandleBlockedSafety(store, provider);
    await expect(handling.prepare("account", "case")).resolves.toEqual({
      source,
      candidate,
    });
    expect(store.saveCandidate).toHaveBeenCalledWith(
      "account",
      "case",
      candidate,
    );
  });

  it("rejects edits that do not point to the exact original text", async () => {
    const store = fakeStore();
    const provider = {
      deidentify: vi.fn().mockResolvedValue({
        ...candidate,
        edits: [{ ...candidate.edits[0], originalText: "李四" }],
      }),
    };
    await expect(
      new HandleBlockedSafety(store, provider).prepare("account", "case"),
    ).rejects.toThrow("reference source");
  });

  it("never creates manual review without an explicit command", async () => {
    const store = fakeStore();
    const handling = new HandleBlockedSafety(store, {
      deidentify: async () => candidate,
    });
    await handling.prepare("account", "case");
    expect(store.submitManualReview).not.toHaveBeenCalled();
    await handling.submitManualReview("account", "case", "review-command");
    expect(store.submitManualReview).toHaveBeenCalledWith(
      "account",
      "case",
      "review-command",
    );
  });

  it("reads an owned adjudicated review result for a controlled message target", async () => {
    const store = fakeStore();
    vi.mocked(store.loadManualReviewResult).mockResolvedValue({
      outcome: "APPROVED",
      source,
    });
    await expect(
      new HandleBlockedSafety(store, {
        deidentify: async () => candidate,
      }).manualReviewResult("account", "review-1"),
    ).resolves.toEqual({ outcome: "APPROVED", source });
    expect(store.loadManualReviewResult).toHaveBeenCalledWith(
      "account",
      "review-1",
    );
  });
});

const fakeStore = (): SafetyHandlingStore => ({
  loadManualReviewStatus: vi.fn().mockResolvedValue("PENDING"),
  loadBlocked: vi.fn().mockResolvedValue(source),
  loadCandidate: vi.fn().mockResolvedValue(null),
  hasExpiredCandidate: vi.fn().mockResolvedValue(false),
  saveCandidate: vi.fn().mockResolvedValue(undefined),
  acceptCandidate: vi.fn().mockResolvedValue(true),
  returnToEdit: vi.fn().mockResolvedValue(true),
  submitManualReview: vi.fn().mockResolvedValue("review-1"),
  loadManualReviewResult: vi.fn().mockResolvedValue(null),
});
