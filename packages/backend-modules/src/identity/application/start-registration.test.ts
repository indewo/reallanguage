import { describe, expect, it, vi } from "vitest";
import {
  StartRegistration,
  type EligibleRegistrationStarter,
  type PasswordAssessor,
  type YearMonthClock,
} from "./start-registration.js";

const command = {
  requestId: "request-1",
  email: "learner@example.com",
  password: "not-a-real-password",
  birthYearMonth: { year: 2012, month: 10 },
  acceptedTerms: true,
  acceptedPrivacyPolicy: true,
} as const;

describe("StartRegistration", () => {
  it("does not send verification or create an account when under fourteen", async () => {
    const startEligibleRegistration = vi.fn();
    const assess = vi.fn();
    const useCase = new StartRegistration(
      fixedClock({ year: 2026, month: 9 }),
      { assess },
      { startEligibleRegistration },
    );

    await expect(useCase.execute(command)).resolves.toEqual({
      status: "AGE_INELIGIBLE",
    });
    expect(assess).not.toHaveBeenCalled();
    expect(startEligibleRegistration).not.toHaveBeenCalled();
  });

  it("rejects an unacceptable password without starting registration", async () => {
    const startEligibleRegistration = vi.fn();
    const useCase = new StartRegistration(
      fixedClock({ year: 2026, month: 9 }),
      passwordAssessor({ accepted: false, reason: "BLOCKED" }),
      { startEligibleRegistration },
    );

    await expect(
      useCase.execute({
        ...command,
        birthYearMonth: { year: 2012, month: 9 },
      }),
    ).resolves.toEqual({
      status: "PASSWORD_REJECTED",
      reason: "BLOCKED",
    });
    expect(startEligibleRegistration).not.toHaveBeenCalled();
  });

  it("continues to email verification at the fourteen-year boundary", async () => {
    const starter: EligibleRegistrationStarter = {
      startEligibleRegistration: vi.fn().mockResolvedValue({
        registrationId: "registration-1",
      }),
    };
    const useCase = new StartRegistration(
      fixedClock({ year: 2026, month: 9 }),
      passwordAssessor({
        accepted: true,
        normalizedPassword: command.password.normalize("NFC"),
      }),
      starter,
    );

    await expect(
      useCase.execute({
        ...command,
        birthYearMonth: { year: 2012, month: 9 },
      }),
    ).resolves.toEqual({
      status: "EMAIL_VERIFICATION_REQUIRED",
      registrationId: "registration-1",
    });
    expect(starter.startEligibleRegistration).toHaveBeenCalledOnce();
  });
});

const fixedClock = (current: {
  year: number;
  month: number;
}): YearMonthClock => ({ current: () => current });

const passwordAssessor = (
  assessment: Awaited<ReturnType<PasswordAssessor["assess"]>>,
): PasswordAssessor => ({ assess: vi.fn().mockResolvedValue(assessment) });
