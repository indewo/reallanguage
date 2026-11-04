import { describe, expect, it } from "vitest";
import { isRegistrationAgeEligible } from "./registration-age.js";

describe("registration age eligibility", () => {
  it("accepts the birth month exactly fourteen years later", () => {
    expect(
      isRegistrationAgeEligible(
        { year: 2012, month: 9 },
        { year: 2026, month: 9 },
      ),
    ).toBe(true);
  });

  it("rejects the month immediately before the fourteen-year boundary", () => {
    expect(
      isRegistrationAgeEligible(
        { year: 2012, month: 10 },
        { year: 2026, month: 9 },
      ),
    ).toBe(false);
  });

  it("accepts users older than fourteen", () => {
    expect(
      isRegistrationAgeEligible(
        { year: 2000, month: 1 },
        { year: 2026, month: 9 },
      ),
    ).toBe(true);
  });
});
