import { PGlite } from "@electric-sql/pglite";
import { migrateToLatest } from "@reallanguage/database";
import { Kysely, PGliteDialect, sql } from "kysely";
import { afterEach, beforeEach, describe, expect, it } from "vitest";

import { AdminCryptography } from "./admin-cryptography.js";
import {
  KyselyAdminIdentityStore,
  type AdminIdentityDatabase,
} from "./kysely-admin-identity-store.js";

let database: Kysely<AdminIdentityDatabase>;
let store: KyselyAdminIdentityStore;

beforeEach(async () => {
  database = new Kysely({
    dialect: new PGliteDialect({ pglite: new PGlite() }),
  });
  await migrateToLatest(database);
  store = new KyselyAdminIdentityStore(database, {
    cryptography: new AdminCryptography({
      encryptionKey: {
        key: Uint8Array.from({ length: 32 }, () => 4),
        version: 1,
      },
      digestKey: {
        key: Uint8Array.from({ length: 32 }, () => 5),
        version: 1,
      },
    }),
    identifiers: {
      next: (() => {
        let value = 1;
        return () =>
          `10000000-0000-4000-8000-${String(value++).padStart(12, "0")}`;
      })(),
    },
  });
});

afterEach(async () => database.destroy());

describe("KyselyAdminIdentityStore", () => {
  it("reports an unconfigured store before an administrator is provisioned", async () => {
    await expect(store.configurationStatus()).resolves.toBe("UNCONFIGURED");
    await expect(
      store.loadPendingTotpSecret("indewo@outlook.com"),
    ).resolves.toBeNull();
  });

  it("provisions and loads an isolated encrypted administrator identity", async () => {
    await expect(
      store.provision({
        canonicalEmail: "indewo@outlook.com",
        passwordHash: "argon2-hash",
        totpSecret: "BASE32SECRET",
        now: new Date("2026-09-04T12:00:00.000Z"),
      }),
    ).resolves.toBe("CREATED");

    await expect(store.configurationStatus()).resolves.toBe(
      "ENROLLMENT_PENDING",
    );
    await expect(
      store.loadPendingTotpSecret("indewo@outlook.com"),
    ).resolves.toBe("BASE32SECRET");

    const identity = await store.loadByCanonicalEmail("indewo@outlook.com");
    expect(identity).toMatchObject({
      canonicalEmail: "indewo@outlook.com",
      passwordHash: "argon2-hash",
      totpSecret: "BASE32SECRET",
      lastAcceptedTotpCounter: null,
      enabled: true,
    });
    expect(identity?.adminId).toBeTruthy();
    const result = await sql<{
      email_ciphertext: Uint8Array;
      totp_secret_ciphertext: Uint8Array;
    }>`select email_ciphertext, totp_secret_ciphertext from admin.accounts`.execute(
      database,
    );
    const row = result.rows[0];
    expect(row).toBeDefined();
    if (!row) throw new Error("Expected provisioned administrator row");
    expect(Buffer.from(row.email_ciphertext).toString("utf8")).not.toContain(
      "indewo@outlook.com",
    );
    expect(
      Buffer.from(row.totp_secret_ciphertext).toString("utf8"),
    ).not.toContain("BASE32SECRET");
  });

  it("atomically consumes a TOTP counter and authenticates only a live session", async () => {
    await store.provision({
      canonicalEmail: "indewo@outlook.com",
      passwordHash: "argon2-hash",
      totpSecret: "BASE32SECRET",
      now: new Date("2026-09-04T12:00:00.000Z"),
    });
    const adminId = (await store.loadByCanonicalEmail("indewo@outlook.com"))
      ?.adminId;
    if (!adminId) throw new Error("Expected administrator identity");
    const digest = Uint8Array.from({ length: 32 }, () => 8);
    const issued = await store.issueSession({
      adminId,
      acceptedTotpCounter: 42,
      previousTotpCounter: null,
      sessionDigest: digest,
      sessionDigestKeyVersion: 1,
      expiresAt: new Date("2026-09-04T12:15:00.000Z"),
      now: new Date("2026-09-04T12:00:00.000Z"),
    });

    expect(issued).toBe(true);
    await expect(store.configurationStatus()).resolves.toBe("CONFIGURED");
    await expect(
      store.loadPendingTotpSecret("indewo@outlook.com"),
    ).resolves.toBeNull();
    await expect(
      store.authenticateDigest(digest, 1, new Date("2026-09-04T12:10:00.000Z")),
    ).resolves.toBe(adminId);
    await expect(
      store.issueSession({
        adminId,
        acceptedTotpCounter: 42,
        previousTotpCounter: null,
        sessionDigest: Uint8Array.from({ length: 32 }, () => 9),
        sessionDigestKeyVersion: 1,
        expiresAt: new Date("2026-09-04T12:15:00.000Z"),
        now: new Date("2026-09-04T12:00:01.000Z"),
      }),
    ).resolves.toBe(false);
    await expect(
      store.authenticateDigest(digest, 1, new Date("2026-09-04T12:16:00.000Z")),
    ).resolves.toBeNull();
  });

  it("revokes only the authenticated administrator session", async () => {
    await store.provision({
      canonicalEmail: "indewo@outlook.com",
      passwordHash: "argon2-hash",
      totpSecret: "BASE32SECRET",
      now: new Date("2026-09-04T12:00:00.000Z"),
    });
    const adminId = (await store.loadByCanonicalEmail("indewo@outlook.com"))
      ?.adminId;
    if (!adminId) throw new Error("Expected administrator identity");
    const digest = Uint8Array.from({ length: 32 }, () => 8);
    await store.issueSession({
      adminId,
      acceptedTotpCounter: 42,
      previousTotpCounter: null,
      sessionDigest: digest,
      sessionDigestKeyVersion: 1,
      expiresAt: new Date("2026-09-04T12:15:00.000Z"),
      now: new Date("2026-09-04T12:00:00.000Z"),
    });

    await expect(
      store.revokeDigest(
        adminId,
        digest,
        1,
        new Date("2026-09-04T12:05:00.000Z"),
      ),
    ).resolves.toBe(true);
    await expect(
      store.authenticateDigest(digest, 1, new Date("2026-09-04T12:06:00.000Z")),
    ).resolves.toBeNull();
  });
});
