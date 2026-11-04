export interface AdminSessionLifecycleStore {
  authenticateDigest(
    digest: Uint8Array,
    keyVersion: number,
    now: Date,
  ): Promise<string | null>;
  revokeDigest(
    adminId: string,
    digest: Uint8Array,
    keyVersion: number,
    now: Date,
  ): Promise<boolean>;
}

type Dependencies = Readonly<{
  sessions: {
    digestSession(bearer: string): Readonly<{
      digest: Uint8Array;
      keyVersion: number;
    }>;
  };
  clock: { now(): Date };
}>;

export class AuthenticateAdminSession {
  public constructor(
    private readonly store: AdminSessionLifecycleStore,
    private readonly dependencies: Dependencies,
  ) {}

  public async execute(bearer: string) {
    if (!bearer) return { status: "INVALID_OR_EXPIRED" } as const;
    const session = this.dependencies.sessions.digestSession(bearer);
    const adminId = await this.store.authenticateDigest(
      session.digest,
      session.keyVersion,
      this.dependencies.clock.now(),
    );
    return adminId
      ? ({ status: "AUTHENTICATED", adminId } as const)
      : ({ status: "INVALID_OR_EXPIRED" } as const);
  }
}

export class RevokeAdminSession {
  public constructor(
    private readonly store: AdminSessionLifecycleStore,
    private readonly dependencies: Dependencies,
  ) {}

  public async execute(bearer: string) {
    if (!bearer) return { status: "INVALID_OR_EXPIRED" } as const;
    const session = this.dependencies.sessions.digestSession(bearer);
    const now = this.dependencies.clock.now();
    const adminId = await this.store.authenticateDigest(
      session.digest,
      session.keyVersion,
      now,
    );
    if (!adminId) return { status: "INVALID_OR_EXPIRED" } as const;
    const revoked = await this.store.revokeDigest(
      adminId,
      session.digest,
      session.keyVersion,
      now,
    );
    return revoked
      ? ({ status: "REVOKED" } as const)
      : ({ status: "INVALID_OR_EXPIRED" } as const);
  }
}
