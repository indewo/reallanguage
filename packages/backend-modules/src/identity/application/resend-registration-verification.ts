export type ResendRegistrationVerificationResult =
  | Readonly<{ status: "NOT_AVAILABLE" }>
  | Readonly<{ status: "COOLDOWN"; retryAfterSeconds: number }>
  | Readonly<{ status: "RESENT"; retryAfterSeconds: number }>;

export interface RegistrationVerificationEmailResender {
  resend(
    command: Readonly<{
      registrationId: string;
      requestId: string;
      requestedAt: Date;
      cooldownSeconds: number;
    }>,
  ): Promise<ResendRegistrationVerificationResult>;
}

export interface RegistrationVerificationResendClock {
  now(): Date;
}

export class ResendRegistrationVerification {
  public constructor(
    private readonly clock: RegistrationVerificationResendClock,
    private readonly resender: RegistrationVerificationEmailResender,
    private readonly cooldownSeconds: number,
  ) {
    if (!Number.isSafeInteger(cooldownSeconds) || cooldownSeconds <= 0) {
      throw new Error("cooldownSeconds must be a positive integer");
    }
  }

  public execute(
    command: Readonly<{ registrationId: string; requestId: string }>,
  ): Promise<ResendRegistrationVerificationResult> {
    return this.resender.resend({
      ...command,
      requestedAt: this.clock.now(),
      cooldownSeconds: this.cooldownSeconds,
    });
  }
}
