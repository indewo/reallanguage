export type AdminIdentity = Readonly<{
  adminId: string;
  canonicalEmail: string;
  passwordHash: string;
  totpSecret: string;
  lastAcceptedTotpCounter: number | null;
  enabled: boolean;
}>;

export interface AdminIdentityStore {
  loadByCanonicalEmail(email: string): Promise<AdminIdentity | null>;
  issueSession(input: {
    adminId: string;
    acceptedTotpCounter: number;
    previousTotpCounter: number | null;
    sessionDigest: Uint8Array;
    sessionDigestKeyVersion: number;
    expiresAt: Date;
    now: Date;
  }): Promise<boolean>;
}

export type CreateAdminSessionCommand = Readonly<{
  email: string;
  password: string;
  totpCode: string;
}>;

export type CreateAdminSessionResult =
  | Readonly<{ status: "INVALID_CREDENTIALS" }>
  | Readonly<{
      status: "AUTHENTICATED";
      accessToken: string;
      expiresAt: Date;
    }>;

export class CreateAdminSession {
  public constructor(
    private readonly store: AdminIdentityStore,
    private readonly dependencies: Readonly<{
      passwords: {
        verify(encodedHash: string, password: string): Promise<boolean>;
      };
      dummyPasswordHash: string;
      totp: {
        verify(
          secretBase32: string,
          candidate: string,
          now: Date,
          lastAcceptedCounter: number | null,
        ): number | null;
      };
      sessions: {
        create(): Readonly<{
          bearer: string;
          digest: Uint8Array;
          keyVersion: number;
        }>;
      };
      clock: { now(): Date };
      sessionTtlMilliseconds: number;
    }>,
  ) {
    if (
      !dependencies.dummyPasswordHash ||
      !Number.isSafeInteger(dependencies.sessionTtlMilliseconds) ||
      dependencies.sessionTtlMilliseconds < 60_000 ||
      dependencies.sessionTtlMilliseconds > 8 * 60 * 60 * 1_000
    ) {
      throw new Error("Invalid admin session configuration");
    }
  }

  public async execute(
    command: CreateAdminSessionCommand,
  ): Promise<CreateAdminSessionResult> {
    const canonicalEmail = command.email.trim().toLowerCase();
    if (!canonicalEmail || !command.password || !command.totpCode) {
      return { status: "INVALID_CREDENTIALS" };
    }
    const account = await this.store.loadByCanonicalEmail(canonicalEmail);
    const passwordMatches = await this.dependencies.passwords.verify(
      account?.passwordHash ?? this.dependencies.dummyPasswordHash,
      command.password,
    );
    if (!account?.enabled || !passwordMatches) {
      return { status: "INVALID_CREDENTIALS" };
    }
    const now = this.dependencies.clock.now();
    const acceptedTotpCounter = this.dependencies.totp.verify(
      account.totpSecret,
      command.totpCode,
      now,
      account.lastAcceptedTotpCounter,
    );
    if (acceptedTotpCounter === null) {
      return { status: "INVALID_CREDENTIALS" };
    }
    const session = this.dependencies.sessions.create();
    const expiresAt = new Date(
      now.getTime() + this.dependencies.sessionTtlMilliseconds,
    );
    const issued = await this.store.issueSession({
      adminId: account.adminId,
      acceptedTotpCounter,
      previousTotpCounter: account.lastAcceptedTotpCounter,
      sessionDigest: session.digest,
      sessionDigestKeyVersion: session.keyVersion,
      expiresAt,
      now,
    });
    return issued
      ? {
          status: "AUTHENTICATED",
          accessToken: session.bearer,
          expiresAt,
        }
      : { status: "INVALID_CREDENTIALS" };
  }
}
