import type { Kysely } from "kysely";

import type { IdentitySessionDatabase } from "./kysely-credential-verifier.js";

export interface AccessTokenDigester {
  digest(bearer: string): Readonly<{ digest: Uint8Array; keyVersion: number }>;
}

export interface AccessTokenClock {
  now(): Date;
}

export class KyselyAccessTokenAuthenticator {
  public constructor(
    private readonly database: Kysely<IdentitySessionDatabase>,
    private readonly digester: AccessTokenDigester,
    private readonly clock: AccessTokenClock,
  ) {}

  public async authenticate(bearer: string): Promise<string | null> {
    const token = this.digester.digest(bearer);
    const now = this.clock.now();
    const session = await this.database
      .selectFrom("identity.user_sessions")
      .select("account_id")
      .where("access_token_digest", "=", token.digest)
      .where("access_token_digest_key_version", "=", token.keyVersion)
      .where("access_token_expires_at", ">", now)
      .where("expires_at", ">", now)
      .where("revoked_at", "is", null)
      .executeTakeFirst();
    return session?.account_id ?? null;
  }
}
