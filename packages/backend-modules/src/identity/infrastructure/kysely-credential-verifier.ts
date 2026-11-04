import type { ColumnType, Generated, Kysely } from "kysely";

import type {
  CredentialVerifier,
  VerifiedCredentials,
} from "../application/create-session.js";

interface AuthIdentitiesTable {
  account_id: string;
  normalized_email_index: Uint8Array;
  normalized_email_index_key_version: number;
  password_hash: string;
}

type Timestamp = ColumnType<Date, Date, Date>;
type GeneratedTimestamp = ColumnType<Date, Date | undefined, Date>;

interface UserSessionsTable {
  session_id: string;
  account_id: string;
  refresh_token_digest: Uint8Array;
  refresh_token_digest_key_version: number;
  access_token_digest: Uint8Array | null;
  access_token_digest_key_version: number | null;
  access_token_expires_at: Timestamp | null;
  device_id_digest: Uint8Array;
  device_id_digest_key_version: number;
  expires_at: Timestamp;
  last_used_at: Timestamp;
  revoked_at: Timestamp | null;
  row_version: Generated<number>;
  created_at: GeneratedTimestamp;
  updated_at: GeneratedTimestamp;
}

export interface IdentitySessionDatabase {
  "identity.auth_identities": AuthIdentitiesTable;
  "identity.user_sessions": UserSessionsTable;
}

export interface LoginEmailIndex {
  create(email: string): Readonly<{ digest: Uint8Array; keyVersion: number }>;
}

export interface LoginPasswordHasher {
  verify(encodedHash: string, password: string): Promise<boolean>;
}

export interface SettingsCompletionReader {
  hasCompleted(accountId: string): Promise<boolean>;
}

export type KyselyCredentialVerifierDependencies = Readonly<{
  emailIndex: LoginEmailIndex;
  passwordHasher: LoginPasswordHasher;
  settings: SettingsCompletionReader;
  dummyPasswordHash: string;
}>;

export class KyselyCredentialVerifier implements CredentialVerifier {
  public constructor(
    private readonly database: Kysely<IdentitySessionDatabase>,
    private readonly dependencies: KyselyCredentialVerifierDependencies,
  ) {
    if (!dependencies.dummyPasswordHash) {
      throw new Error("A dummy password hash is required");
    }
  }

  public async verify(input: {
    email: string;
    password: string;
  }): Promise<VerifiedCredentials | null> {
    const emailIndex = this.dependencies.emailIndex.create(input.email);
    const identity = await this.database
      .selectFrom("identity.auth_identities")
      .select(["account_id", "password_hash"])
      .where("normalized_email_index", "=", emailIndex.digest)
      .where("normalized_email_index_key_version", "=", emailIndex.keyVersion)
      .executeTakeFirst();
    const passwordValid = await this.dependencies.passwordHasher.verify(
      identity?.password_hash ?? this.dependencies.dummyPasswordHash,
      input.password,
    );
    if (!identity || !passwordValid) return null;

    return {
      accountId: identity.account_id,
      settingsCompleted: await this.dependencies.settings.hasCompleted(
        identity.account_id,
      ),
    };
  }
}
