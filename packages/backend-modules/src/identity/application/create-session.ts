export type CreateSessionCommand = Readonly<{
  email: string;
  password: string;
  deviceId: string;
}>;

export type VerifiedCredentials = Readonly<{
  accountId: string;
  settingsCompleted: boolean;
}>;

export interface CredentialVerifier {
  verify(
    credentials: Readonly<{ email: string; password: string }>,
  ): Promise<VerifiedCredentials | null>;
}

export type IssuedSession = Readonly<{
  accessToken: string;
  refreshToken: string;
  accessTokenExpiresInSeconds: number;
}>;

export interface SessionIssuer {
  issue(
    input: Readonly<{ accountId: string; deviceId: string }>,
  ): Promise<IssuedSession>;
}

export type CreateSessionResult =
  | Readonly<{ status: "INVALID_CREDENTIALS" }>
  | (Readonly<{
      status: "AUTHENTICATED";
      accountId: string;
      settingsCompleted: boolean;
    }> &
      IssuedSession);

export class CreateSession {
  public constructor(
    private readonly credentials: CredentialVerifier,
    private readonly sessions: SessionIssuer,
  ) {}

  public async execute(
    command: CreateSessionCommand,
  ): Promise<CreateSessionResult> {
    const verified = await this.credentials.verify({
      email: command.email,
      password: command.password,
    });
    if (!verified) return { status: "INVALID_CREDENTIALS" };

    const session = await this.sessions.issue({
      accountId: verified.accountId,
      deviceId: command.deviceId,
    });
    return {
      status: "AUTHENTICATED",
      accountId: verified.accountId,
      settingsCompleted: verified.settingsCompleted,
      ...session,
    };
  }
}
