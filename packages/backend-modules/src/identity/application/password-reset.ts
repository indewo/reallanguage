import type {
  PasswordAssessment,
  PasswordRejectionReason,
} from "../domain/password-policy.js";

export type RequestPasswordResetCommand = Readonly<{
  email: string;
  commandId: string;
}>;

export interface PasswordResetRequester {
  request(
    command: RequestPasswordResetCommand,
  ): Promise<Readonly<{ challengeId: string }>>;
}

export class RequestPasswordReset {
  public constructor(private readonly requester: PasswordResetRequester) {}

  public async execute(
    command: RequestPasswordResetCommand,
  ): Promise<Readonly<{ status: "SENT"; challengeId: string }>> {
    const requested = await this.requester.request(command);
    return { status: "SENT", challengeId: requested.challengeId };
  }
}

export interface PasswordResetLinkVerifier {
  verify(
    command: Readonly<{ challengeId: string; bearer: string }>,
  ): Promise<Readonly<{ resetToken: string }> | null>;
}

export type VerifyPasswordResetResult =
  | Readonly<{ status: "INVALID_OR_EXPIRED" }>
  | Readonly<{ status: "VERIFIED"; resetToken: string }>;

export class VerifyPasswordReset {
  public constructor(private readonly verifier: PasswordResetLinkVerifier) {}

  public async execute(command: {
    challengeId: string;
    bearer: string;
  }): Promise<VerifyPasswordResetResult> {
    const verified = await this.verifier.verify(command);
    return verified
      ? { status: "VERIFIED", resetToken: verified.resetToken }
      : { status: "INVALID_OR_EXPIRED" };
  }
}

export interface PasswordResetPasswordAssessor {
  assess(password: string): Promise<PasswordAssessment>;
}

export interface PasswordResetCompleter {
  complete(
    command: Readonly<{
      resetToken: string;
      normalizedPassword: string;
      commandId: string;
    }>,
  ): Promise<Readonly<{ initialSettingsComplete: boolean }> | null>;
}

export type CompletePasswordResetResult =
  | Readonly<{
      status: "PASSWORD_REJECTED";
      reason: PasswordRejectionReason;
    }>
  | Readonly<{ status: "INVALID_OR_EXPIRED" }>
  | Readonly<{
      status: "COMPLETED";
      sessionEstablished: false;
      initialSettingsComplete: boolean;
    }>;

export class CompletePasswordReset {
  public constructor(
    private readonly passwordAssessor: PasswordResetPasswordAssessor,
    private readonly completer: PasswordResetCompleter,
  ) {}

  public async execute(command: {
    resetToken: string;
    newPassword: string;
    commandId: string;
  }): Promise<CompletePasswordResetResult> {
    const assessment = await this.passwordAssessor.assess(command.newPassword);
    if (!assessment.accepted) {
      return { status: "PASSWORD_REJECTED", reason: assessment.reason };
    }

    const completed = await this.completer.complete({
      resetToken: command.resetToken,
      normalizedPassword: assessment.normalizedPassword,
      commandId: command.commandId,
    });
    return completed
      ? {
          status: "COMPLETED",
          sessionEstablished: false,
          initialSettingsComplete: completed.initialSettingsComplete,
        }
      : { status: "INVALID_OR_EXPIRED" };
  }
}
