import { describe, expect, it } from "vitest";
import { manualReviewSubmissionResultSchema } from "./index.js";

const legacy = {
  status: "SUBMITTED",
  reviewRequestId: "50000000-0000-4000-8000-000000000001",
};

describe("manual review submission wire compatibility", () => {
  it("retains the old response shape for old service compatibility", () => {
    expect(manualReviewSubmissionResultSchema.parse(legacy)).toEqual(legacy);
  });
  it.each(["PENDING", "APPROVED", "NOT_APPROVED"])(
    "preserves the explicit saved %s status",
    (reviewStatus) => {
      const response = { ...legacy, reviewStatus };
      expect(manualReviewSubmissionResultSchema.parse(response)).toEqual(
        response,
      );
    },
  );
  it.each([null, "SUBMITTED", 1, true])(
    "rejects invalid explicit status %s instead of dropping it",
    (reviewStatus) => {
      expect(
        manualReviewSubmissionResultSchema.safeParse({
          ...legacy,
          reviewStatus,
        }).success,
      ).toBe(false);
    },
  );
});
