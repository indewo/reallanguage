import type { IssuedSession } from "./create-session.js";

export type RotatedSession = Readonly<{
  accountId: string;
  settingsCompleted: boolean;
}> &
  IssuedSession;

export interface SessionLifecycleRepository {
  rotate(
    input: Readonly<{ refreshToken: string; deviceId: string }>,
  ): Promise<RotatedSession | null>;
  revoke(refreshToken: string): Promise<void>;
}

export type RefreshSessionResult =
  | Readonly<{ status: "INVALID_OR_EXPIRED" }>
  | (Readonly<{ status: "AUTHENTICATED" }> & RotatedSession);

export class RefreshSession {
  public constructor(private readonly sessions: SessionLifecycleRepository) {}

  public async execute(input: {
    refreshToken: string;
    deviceId: string;
  }): Promise<RefreshSessionResult> {
    const rotated = await this.sessions.rotate(input);
    return rotated
      ? { status: "AUTHENTICATED", ...rotated }
      : { status: "INVALID_OR_EXPIRED" };
  }
}

export class RevokeSession {
  public constructor(private readonly sessions: SessionLifecycleRepository) {}

  public async execute(input: {
    refreshToken: string;
  }): Promise<Readonly<{ status: "REVOKED" }>> {
    await this.sessions.revoke(input.refreshToken);
    return { status: "REVOKED" };
  }
}
