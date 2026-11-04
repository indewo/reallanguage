export type RegistrationChallengeStatus =
  "ACTIVE" | "CONSUMED" | "SUPERSEDED" | "EXPIRED";

export type ProtectedPendingRegistration = Readonly<{
  ciphertext: Uint8Array;
  algorithm: string;
  keyVersion: number;
}>;

export type PendingRegistrationChallenge = Readonly<{
  challengeId: string;
  accountId: string | null;
  status: RegistrationChallengeStatus;
  expiresAt: Date;
  subjectIndex: Uint8Array;
  subjectIndexKeyVersion: number;
  secretDigest: Uint8Array;
  secretDigestKeyVersion: number;
  protectedPayload: ProtectedPendingRegistration;
}>;

export interface RegistrationChallengeReader {
  findById(challengeId: string): Promise<PendingRegistrationChallenge | null>;
}

export interface RegistrationSecretVerifier {
  verify(
    candidate: Readonly<{
      bearer: string;
      digest: Uint8Array;
      digestKeyVersion: number;
    }>,
  ): boolean;
}

export interface VerifiedAccountCreator {
  create(
    input: Readonly<{
      challenge: PendingRegistrationChallenge;
      verifiedAt: Date;
    }>,
  ): Promise<Readonly<{ accountId: string }> | null>;
}

export interface RegistrationVerificationClock {
  now(): Date;
}

export type VerifyRegistrationResult =
  | Readonly<{ status: "INVALID_OR_EXPIRED" }>
  | Readonly<{ status: "ACCOUNT_CREATED"; accountId: string }>;

export type GetRegistrationVerificationStatusResult =
  | Readonly<{ status: "WAITING" }>
  | Readonly<{ status: "VERIFIED" }>
  | Readonly<{ status: "NOT_AVAILABLE" }>;

export class GetRegistrationVerificationStatus {
  public constructor(
    private readonly clock: RegistrationVerificationClock,
    private readonly challenges: RegistrationChallengeReader,
  ) {}

  public async execute(
    command: Readonly<{ registrationId: string }>,
  ): Promise<GetRegistrationVerificationStatusResult> {
    const challenge = await this.challenges.findById(command.registrationId);
    if (
      !challenge ||
      challenge.status === "SUPERSEDED" ||
      challenge.status === "EXPIRED"
    ) {
      return { status: "NOT_AVAILABLE" };
    }
    if (challenge.status === "CONSUMED") {
      return challenge.accountId
        ? { status: "VERIFIED" }
        : { status: "NOT_AVAILABLE" };
    }
    return challenge.expiresAt.getTime() > this.clock.now().getTime()
      ? { status: "WAITING" }
      : { status: "NOT_AVAILABLE" };
  }
}

export class VerifyRegistration {
  public constructor(
    private readonly clock: RegistrationVerificationClock,
    private readonly challenges: RegistrationChallengeReader,
    private readonly secretVerifier: RegistrationSecretVerifier,
    private readonly accountCreator: VerifiedAccountCreator,
  ) {}

  public async execute(
    command: Readonly<{
      registrationId: string;
      bearer: string;
    }>,
  ): Promise<VerifyRegistrationResult> {
    const challenge = await this.challenges.findById(command.registrationId);
    const verifiedAt = this.clock.now();
    if (
      !challenge ||
      challenge.status === "SUPERSEDED" ||
      challenge.status === "EXPIRED"
    ) {
      return { status: "INVALID_OR_EXPIRED" };
    }
    if (
      challenge.status === "ACTIVE" &&
      challenge.expiresAt.getTime() <= verifiedAt.getTime()
    ) {
      return { status: "INVALID_OR_EXPIRED" };
    }

    const verified = this.secretVerifier.verify({
      bearer: command.bearer,
      digest: challenge.secretDigest,
      digestKeyVersion: challenge.secretDigestKeyVersion,
    });
    if (!verified) return { status: "INVALID_OR_EXPIRED" };

    if (challenge.status === "CONSUMED") {
      return challenge.accountId
        ? { status: "ACCOUNT_CREATED", accountId: challenge.accountId }
        : { status: "INVALID_OR_EXPIRED" };
    }

    const created = await this.accountCreator.create({ challenge, verifiedAt });
    if (!created) {
      const consumed = await this.challenges.findById(command.registrationId);
      return consumed?.status === "CONSUMED" && consumed.accountId
        ? { status: "ACCOUNT_CREATED", accountId: consumed.accountId }
        : { status: "INVALID_OR_EXPIRED" };
    }

    return { status: "ACCOUNT_CREATED", accountId: created.accountId };
  }
}
