import { sql, type Kysely } from "kysely";

/** No decryption: preserve command tombstones and every explicit manual-review hold. */
export const purgeExpiredSafetyPayloads = async (
  database: Kysely<unknown>,
  now = new Date(),
): Promise<{ operations: number; candidates: number; cases: number }> => {
  if (!Number.isFinite(now.getTime()))
    throw new Error("Invalid retention clock");
  return database.transaction().execute(async (transaction) => {
    // Manual submission and candidate acceptance lock this same row. A held or
    // in-flight case is deliberately left untouched, not treated as expired.
    const locked = await sql<{ safety_case_id: string }>`select c.safety_case_id
      from safety.safety_cases c join safety.check_operations o
        on o.operation_id=c.operation_id and o.user_id=c.user_id
      where o.status in ('PASSED','BLOCKED','FAILED')
        and c.resolution_status <> 'REVIEW_PENDING'
        and not exists (select 1 from safety.manual_review_requests r where r.safety_case_id=c.safety_case_id)
        and ((o.expires_at <= ${now} and (o.payload_ciphertext is not null or c.risk_spans <> '[]'::jsonb))
          or exists (select 1 from safety.deidentified_versions d where d.safety_case_id=c.safety_case_id and d.user_id=c.user_id and d.expires_at <= ${now}))
      order by c.safety_case_id for update of c skip locked`.execute(
      transaction,
    );
    let operations = 0;
    let candidates = 0;
    let cases = 0;
    if (locked.rows.length) {
      const ids = sql.join(locked.rows.map((row) => row.safety_case_id));
      const removed =
        await sql`delete from safety.deidentified_versions d using safety.safety_cases c
        where d.safety_case_id=c.safety_case_id and d.user_id=c.user_id
          and c.safety_case_id in (${ids}) and d.expires_at <= ${now}`.execute(
          transaction,
        );
      candidates = Number(removed.numAffectedRows ?? 0);
      const clearedSpans =
        await sql`update safety.safety_cases c set risk_spans='[]'::jsonb
        from safety.check_operations o where o.operation_id=c.operation_id and o.user_id=c.user_id
          and c.safety_case_id in (${ids}) and o.expires_at <= ${now} and c.risk_spans <> '[]'::jsonb`.execute(
          transaction,
        );
      cases = Number(clearedSpans.numAffectedRows ?? 0);
      const cleared = await sql`update safety.check_operations o
        set payload_ciphertext=null,payload_algorithm=null,payload_key_version=null
        from safety.safety_cases c where c.operation_id=o.operation_id and c.user_id=o.user_id
          and c.safety_case_id in (${ids}) and o.expires_at <= ${now} and o.payload_ciphertext is not null`.execute(
        transaction,
      );
      operations = Number(cleared.numAffectedRows ?? 0);
    }
    // A failed check may never have created a safety case. Ambiguous dangling
    // references and PROCESSING checks are not eligible for this cleanup.
    const unlinked = await sql`update safety.check_operations o
      set payload_ciphertext=null,payload_algorithm=null,payload_key_version=null
      where o.expires_at <= ${now} and o.status in ('PASSED','BLOCKED','FAILED')
        and o.payload_ciphertext is not null and o.safety_case_id is null
        and not exists (select 1 from safety.safety_cases c where c.operation_id=o.operation_id)`.execute(
      transaction,
    );
    return {
      operations: operations + Number(unlinked.numAffectedRows ?? 0),
      candidates,
      cases,
    };
  });
};
