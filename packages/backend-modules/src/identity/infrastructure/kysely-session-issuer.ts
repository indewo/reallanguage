import type { Kysely } from "kysely";

import type {
  IssuedSession,
  SessionIssuer,
} from "../application/create-session.js";
import type { IdentitySessionDatabase } from "./kysely-credential-verifier.js";

export interface SessionInstantClock {
  now(): Date;
}

export interface SessionIdentifierFactory {
  next(): string;
}

export interface SessionSecretFactory {
  create(): Readonly<{
    accessToken: string;
    accessTokenDigest: Uint8Array;
    accessTokenDigestKeyVersion: number;
    refreshToken: string;
    refreshTokenDigest: Uint8Array;
    refreshTokenDigestKeyVersion: number;
  }>;
}

export interface DeviceIdentifierDigester {
  digest(
    deviceId: string,
  ): Readonly<{ digest: Uint8Array; keyVersion: number }>;
}

export type KyselySessionIssuerDependencies = Readonly<{
  clock: SessionInstantClock;
  identifiers: SessionIdentifierFactory;
  secrets: SessionSecretFactory;
  devices: DeviceIdentifierDigester;
}>;

const ACCESS_TOKEN_TTL_SECONDS = 15 * 60;
const REFRESH_SESSION_TTL_MILLISECONDS = 30 * 24 * 60 * 60 * 1000;

export class KyselySessionIssuer implements SessionIssuer {
  public constructor(
    private readonly database: Kysely<IdentitySessionDatabase>,
    private readonly dependencies: KyselySessionIssuerDependencies,
  ) {}

  public async issue(input: {
    accountId: string;
    deviceId: string;
  }): Promise<IssuedSession> {
    const issuedAt = this.dependencies.clock.now();
    const sessionId = this.dependencies.identifiers.next();
    const secrets = this.dependencies.secrets.create();
    const device = this.dependencies.devices.digest(input.deviceId);
    const expiresAt = new Date(
      issuedAt.getTime() + REFRESH_SESSION_TTL_MILLISECONDS,
    );
    const accessExpiresAt = new Date(
      issuedAt.getTime() + ACCESS_TOKEN_TTL_SECONDS * 1000,
    );

    await this.database
      .insertInto("identity.user_sessions")
      .values({
        session_id: sessionId,
        account_id: input.accountId,
        access_token_digest: secrets.accessTokenDigest,
        access_token_digest_key_version: secrets.accessTokenDigestKeyVersion,
        access_token_expires_at: accessExpiresAt,
        refresh_token_digest: secrets.refreshTokenDigest,
        refresh_token_digest_key_version: secrets.refreshTokenDigestKeyVersion,
        device_id_digest: device.digest,
        device_id_digest_key_version: device.keyVersion,
        expires_at: expiresAt,
        last_used_at: issuedAt,
        revoked_at: null,
        created_at: issuedAt,
        updated_at: issuedAt,
      })
      .execute();

    return {
      accessToken: secrets.accessToken,
      refreshToken: secrets.refreshToken,
      accessTokenExpiresInSeconds: ACCESS_TOKEN_TTL_SECONDS,
    };
  }
}
