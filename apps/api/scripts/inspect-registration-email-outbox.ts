import { resolve } from "node:path";

import { PGlite } from "@electric-sql/pglite";

const database = new PGlite(
  resolve(process.cwd(), ".local-data/reallanguage-db"),
);
try {
  const events = await database.query<{
    status: string;
    attempt_count: number;
    available_at: string;
    published_at: string | null;
    created_at: string;
    challenge_status: string;
    expires_at: string;
  }>(`
    select event.status,
           event.attempt_count,
           event.available_at,
           event.published_at,
           event.created_at,
           challenge.status as challenge_status,
           challenge.expires_at
      from platform.outbox_events as event
      join identity.verification_challenges as challenge
        on challenge.challenge_id = event.aggregate_id
     where event.event_type = 'identity.email-verification-requested'
     order by event.created_at desc
     limit 10
  `);
  process.stdout.write(JSON.stringify({ events: events.rows }) + "\n");
} finally {
  await database.close();
}
