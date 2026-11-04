import { describe, expect, it, vi } from "vitest";

import {
  DecideAdminReview,
  GetPendingAdminReview,
  ListPendingAdminReviews,
} from "./review-manual-content.js";

const store = {
  listPending: vi.fn(),
  loadPendingDetails: vi.fn(),
  decide: vi.fn(),
};

describe("manual review admin applications", () => {
  it("keeps list and sensitive detail access as separate operations", async () => {
    store.listPending.mockResolvedValue([{ reviewRequestId: "review-1" }]);
    store.loadPendingDetails.mockResolvedValue({
      reviewRequestId: "review-1",
      text: "necessary content",
    });

    await expect(
      new ListPendingAdminReviews(store).execute(),
    ).resolves.toHaveLength(1);
    await expect(
      new GetPendingAdminReview(store).execute(
        "admin-1",
        "review-1",
        "request-1",
      ),
    ).resolves.toMatchObject({ text: "necessary content" });
    expect(store.loadPendingDetails).toHaveBeenCalledWith(
      "admin-1",
      "review-1",
      "request-1",
    );
  });

  it("requires a structured reason for every immutable decision", async () => {
    store.decide.mockResolvedValue(true);
    const application = new DecideAdminReview(store);
    await expect(
      application.execute({
        adminId: "admin-1",
        reviewRequestId: "review-1",
        decision: "APPROVE",
        reasonCode: "CONTEXT_ACCEPTABLE",
        reasonZh: "  结合上下文可继续处理。  ",
        requestId: "request-1",
      }),
    ).resolves.toEqual({ status: "COMPLETED" });
    expect(store.decide).toHaveBeenCalledWith(
      expect.objectContaining({ reasonZh: "结合上下文可继续处理。" }),
    );
    await expect(
      application.execute({
        adminId: "admin-1",
        reviewRequestId: "review-1",
        decision: "KEEP_BLOCKED",
        reasonCode: "bad code",
        reasonZh: "x",
        requestId: "request-2",
      }),
    ).resolves.toEqual({ status: "INVALID_REASON" });
  });
});
