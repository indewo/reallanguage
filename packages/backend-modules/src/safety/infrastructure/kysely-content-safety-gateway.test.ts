import { PGlite } from "@electric-sql/pglite";
import { migrateToLatest } from "@reallanguage/database";
import { Kysely, PGliteDialect, sql } from "kysely";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import {
  type ContentSafetyProvider,
  type ContentSafetyDatabase,
  KyselyContentSafetyGateway,
  SafetyIdempotencyConflictError,
} from "./kysely-content-safety-gateway.js";

const accountId = "30000000-0000-4000-8000-000000000001";
const operationId = "10000000-0000-4000-8000-000000000001";
const safetyCaseId = "20000000-0000-4000-8000-000000000001";
const now = new Date("2026-09-01T12:00:00.000Z");
let database: Kysely<ContentSafetyDatabase>;

beforeEach(async () => {
  database = new Kysely({
    dialect: new PGliteDialect({ pglite: new PGlite() }),
  });
  await migrateToLatest(database);
  await sql`
    insert into identity.accounts (
      account_id, birth_year_month_ciphertext, birth_year_month_algorithm,
      birth_year_month_key_version, age_eligible, age_eligibility_confirmed_at,
      email_verified_at, created_at, updated_at
    ) values (
      ${accountId}, decode('01', 'hex'), 'AES-256-GCM/v1', 1, true, ${now}, ${now}, ${now}, ${now}
    )
  `.execute(database);
});
afterEach(async () => database.destroy());

describe("KyselyContentSafetyGateway", () => {
  it("clears passed plaintext payloads and replays without another provider call", async () => {
    const check = vi.fn().mockResolvedValue({
      decision: "PASS",
      policyVersion: "policy-1",
      providerOperationId: "provider-1",
      contextConsidered: true,
    } as const);
    const provider: ContentSafetyProvider = { check };
    const gateway = createGateway(provider, [operationId]);

    await expect(gateway.check(command)).resolves.toEqual({ decision: "PASS" });
    await expect(gateway.check(command)).resolves.toEqual({ decision: "PASS" });
    expect(check).toHaveBeenCalledTimes(1);
    const stored = await sql<{
      status: string;
      payload_ciphertext: Uint8Array | null;
      policy_version: string;
    }>`select status, payload_ciphertext, policy_version from safety.check_operations`.execute(
      database,
    );
    expect(stored.rows[0]).toEqual({
      status: "PASSED",
      payload_ciphertext: null,
      policy_version: "policy-1",
    });
  });

  it("persists an identified blocked case and rejects command reuse with new text", async () => {
    const check = vi.fn().mockResolvedValue({
      decision: "BLOCK",
      policyVersion: "policy-2",
      providerOperationId: "provider-2",
      reasonCode: "CONFIRMED_RISK",
      userReasonZh: "内容包含明确高风险细节。",
      riskSpans: [
        {
          startCodePoint: 0,
          endCodePoint: 2,
          text: "风险",
          reasonCode: "CONFIRMED_RISK",
        },
      ],
      confidence: 0.98,
      contextConsidered: true,
    } as const);
    const provider: ContentSafetyProvider = { check };
    const gateway = createGateway(provider, [operationId, safetyCaseId]);

    await expect(gateway.check(command)).resolves.toEqual({
      decision: "BLOCK",
      safetyCaseId,
    });
    await expect(
      gateway.check({ ...command, text: "另一份内容" }),
    ).rejects.toBeInstanceOf(SafetyIdempotencyConflictError);
    expect(
      await sql`select * from safety.safety_cases`.execute(database),
    ).toMatchObject({
      rows: [
        expect.objectContaining({
          safety_case_id: safetyCaseId,
          context_considered: true,
        }),
      ],
    });
  });
});

const command = {
  accountId,
  text: "需要按完整上下文检查的日记。",
  sourceType: "DIARY" as const,
  contentShape: "ARTICLE" as const,
  commandId: "safety-command-1",
};

const createGateway = (provider: ContentSafetyProvider, ids: string[]) => {
  const remaining = [...ids];
  return new KyselyContentSafetyGateway(database, {
    clock: { now: () => now },
    identifiers: { next: () => remaining.shift() ?? safetyCaseId },
    payloadProtector: {
      protect: vi.fn().mockResolvedValue({
        ciphertext: Uint8Array.of(9, 8, 7),
        algorithm: "TEST/v1",
        keyVersion: 1,
      }),
    },
    provider,
    payloadTtlMilliseconds: 60_000,
  });
};
