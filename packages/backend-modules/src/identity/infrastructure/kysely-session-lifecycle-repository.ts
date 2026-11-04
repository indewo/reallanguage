import { sql, type Kysely } from "kysely";

import type {
  RotatedSession,
  SessionLifecycleRepository,
} from "../application/manage-session.js";
import type {
  IdentitySessionDatabase,
  SettingsCompletionReader,
} from "./kysely-credential-verifier.js";
import type {
  DeviceIdentifierDigester,
  SessionIdentifierFactory,
  SessionInstantClock,
  SessionSecretFactory,
} from "./kysely-session-issuer.js";

export interface SessionBearerDigester {
  digest(bearer: string): Readonly<{ digest: Uint8Array; keyVersion: number }>;
}

export type KyselySessionLifecycleDependencies = Readonly<{
  clock: SessionInstantClock;
  identifiers: SessionIdentifierFactory;
  bearerDigester: SessionBearerDigester;
  devices: DeviceIdentifierDigester;
  secrets: SessionSecretFactory;
  settings: SettingsCompletionReader;
}>;

const ACCESS_TOKEN_TTL_SECONDS = 15 * 60;
const REFRESH_SESSION_TTL_MILLISECONDS = 30 * 24 * 60 * 60 * 1000;

export class KyselySessionLifecycleRepository implements SessionLifecycleRepository {
  public constructor(
    private readonly database: Kysely<IdentitySessionDatabase>,
    private readonly dependencies: KyselySessionLifecycleDependencies,
  ) {}

  public async rotate(input: {
    refreshToken: string;
    deviceId: string;
  }): Promise<RotatedSession | null> {
    const currentToken = this.dependencies.bearerDigester.digest(
      input.refreshToken,
    );
    const device = this.dependencies.devices.digest(input.deviceId);
    const rotatedAt = this.dependencies.clock.now();

    // Read settings before opening the rotation transaction. The local API uses
    // PGlite, whose single connection cannot service a second database query
    // while the transaction below is holding it.
    const candidate = await this.database
      .selectFrom("identity.user_sessions")
      .select("account_id")
      .where("refresh_token_digest", "=", currentToken.digest)
      .where("refresh_token_digest_key_version", "=", currentToken.keyVersion)
      .where("device_id_digest", "=", device.digest)
      .where("device_id_digest_key_version", "=", device.keyVersion)
      .where("revoked_at", "is", null)
      .where("expires_at", ">", rotatedAt)
      .executeTakeFirst();
    if (!candidate) return null;
    const settingsCompleted = await this.dependencies.settings.hasCompleted(
      candidate.account_id,
    );

    return this.database.transaction().execute(async (transaction) => {
      const current = await transaction
        .selectFrom("identity.user_sessions")
        .select(["session_id", "account_id"])
        .where("refresh_token_digest", "=", currentToken.digest)
        .where("refresh_token_digest_key_version", "=", currentToken.keyVersion)
        .where("device_id_digest", "=", device.digest)
        .where("device_id_digest_key_version", "=", device.keyVersion)
        .where("revoked_at", "is", null)
        .where("expires_at", ">", rotatedAt)
        .forUpdate()
        .executeTakeFirst();
      if (!current) return null;

      const replacement = this.dependencies.secrets.create();
      const replacementId = this.dependencies.identifiers.next();
      const expiresAt = new Date(
        rotatedAt.getTime() + REFRESH_SESSION_TTL_MILLISECONDS,
      );
      const accessExpiresAt = new Date(
        rotatedAt.getTime() + ACCESS_TOKEN_TTL_SECONDS * 1000,
      );

      const revoked = await transaction
        .updateTable("identity.user_sessions")
        .set({
          revoked_at: rotatedAt,
          updated_at: rotatedAt,
          row_version: sql`row_version + 1`,
        })
        .where("session_id", "=", current.session_id)
        .where("revoked_at", "is", null)
        .returning("session_id")
        .executeTakeFirst();
      if (!revoked) return null;

      await transaction
        .insertInto("identity.user_sessions")
        .values({
          session_id: replacementId,
          account_id: current.account_id,
          access_token_digest: replacement.accessTokenDigest,
          access_token_digest_key_version:
            replacement.accessTokenDigestKeyVersion,
          access_token_expires_at: accessExpiresAt,
          refresh_token_digest: replacement.refreshTokenDigest,
          refresh_token_digest_key_version:
            replacement.refreshTokenDigestKeyVersion,
          device_id_digest: device.digest,
          device_id_digest_key_version: device.keyVersion,
          expires_at: expiresAt,
          last_used_at: rotatedAt,
          revoked_at: null,
          created_at: rotatedAt,
          updated_at: rotatedAt,
        })
        .execute();

      return {
        accountId: current.account_id,
        settingsCompleted,
        accessToken: replacement.accessToken,
        refreshToken: replacement.refreshToken,
        accessTokenExpiresInSeconds: ACCESS_TOKEN_TTL_SECONDS,
      };
    });
  }

  public async revoke(refreshToken: string): Promise<void> {
    const token = this.dependencies.bearerDigester.digest(refreshToken);
    const revokedAt = this.dependencies.clock.now();
    await this.database
      .updateTable("identity.user_sessions")
      .set({
        revoked_at: revokedAt,
        updated_at: revokedAt,
        row_version: sql`row_version + 1`,
      })
      .where("refresh_token_digest", "=", token.digest)
      .where("refresh_token_digest_key_version", "=", token.keyVersion)
      .where("revoked_at", "is", null)
      .execute();
  }
}
