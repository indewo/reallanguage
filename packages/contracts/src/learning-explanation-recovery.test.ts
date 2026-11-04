import { describe, expect, it } from "vitest";
import {
  learningExplanationRecoveryQuerySchema,
  learningExplanationRecoveryResultSchema,
} from "./index.js";
describe("read-only explanation recovery contract", () => {
  it.each([
    { kind: "WORD" },
    { kind: "EXPRESSION", accountId: "other" },
    { kind: "SENTENCE", lockedTargets: [] },
    {},
  ])("rejects invalid lookup or caller-supplied account/input %j", (value) => {
    expect(
      learningExplanationRecoveryQuerySchema.safeParse(value).success,
    ).toBe(false);
  });
  it.each(["SENTENCE", "EXPRESSION"])(
    "accepts only the named explanation kind %s",
    (kind) => {
      expect(learningExplanationRecoveryQuerySchema.parse({ kind })).toEqual({
        kind,
      });
    },
  );
  it.each([
    { status: "COMPLETED" },
    { status: "CLAIMED" },
    { status: "NO_DELIVERY", result: "private" },
    {
      status: "COMPLETED",
      commandId: "c",
      provenance: "EXISTING_DELIVERY",
      result: { kind: "SENTENCE", explanationZh: " " },
    },
    {
      status: "COMPLETED",
      commandId: "c",
      provenance: "LOCKED_VERIFIED",
      result: { kind: "SENTENCE", explanationZh: "说明" },
    },
  ])("rejects unsafe recovery responses %j", (value) => {
    expect(
      learningExplanationRecoveryResultSchema.safeParse(value).success,
    ).toBe(false);
  });
  it.each([
    "NO_DELIVERY",
    "NO_DELIVERY_WITH_ATTEMPT",
    "PROCESSING",
    "FAILED",
    "EXPIRED",
    "LEGACY_UNAVAILABLE",
    "PENDING_SETTLEMENT",
    "RELEASED",
    "RECONCILIATION_REQUIRED",
    "INVALID_DELIVERY",
    "INVALID_RESULT",
    "BILLING_UNVERIFIED",
  ])("keeps unavailable state %s explicit", (status) => {
    expect(learningExplanationRecoveryResultSchema.parse({ status })).toEqual({
      status,
    });
  });
});
