import {
  isRegistrationAgeEligible,
  type YearMonth,
} from "../domain/registration-age.js";
import type {
  PasswordAssessment,
  PasswordRejectionReason,
} from "../domain/password-policy.js";

export type StartRegistrationCommand = Readonly<{
  requestId: string;
  email: string;
  password: string;
  birthYearMonth: YearMonth;
  acceptedTerms: true;
  acceptedPrivacyPolicy: true;
}>;

export type StartRegistrationResult =
  | Readonly<{ status: "AGE_INELIGIBLE" }>
  | Readonly<{
      status: "PASSWORD_REJECTED";
      reason: PasswordRejectionReason;
    }>
  | Readonly<{
      status: "EMAIL_VERIFICATION_REQUIRED";
      registrationId: string;
    }>;

export interface YearMonthClock {
  current(): YearMonth;
}

export interface EligibleRegistrationStarter {
  startEligibleRegistration(
    command: StartRegistrationCommand,
  ): Promise<Readonly<{ registrationId: string }>>;
}

export interface PasswordAssessor {
  assess(password: string): Promise<PasswordAssessment>;
}

export class StartRegistration {
  public constructor(
    private readonly clock: YearMonthClock,
    private readonly passwordAssessor: PasswordAssessor,
    private readonly starter: EligibleRegistrationStarter,
  ) {}

  public async execute(
    command: StartRegistrationCommand,
  ): Promise<StartRegistrationResult> {
    if (
      !isRegistrationAgeEligible(command.birthYearMonth, this.clock.current())
    ) {
      return { status: "AGE_INELIGIBLE" };
    }

    const passwordAssessment = await this.passwordAssessor.assess(
      command.password,
    );
    if (!passwordAssessment.accepted) {
      return {
        status: "PASSWORD_REJECTED",
        reason: passwordAssessment.reason,
      };
    }

    const started = await this.starter.startEligibleRegistration({
      ...command,
      password: passwordAssessment.normalizedPassword,
    });
    return {
      status: "EMAIL_VERIFICATION_REQUIRED",
      registrationId: started.registrationId,
    };
  }
}
