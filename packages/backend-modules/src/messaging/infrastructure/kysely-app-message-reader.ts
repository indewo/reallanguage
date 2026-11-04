import type { Kysely, Selectable } from "kysely";
import type {
  AppMessageReader,
  AppMessageSnapshot,
} from "../application/list-app-messages.js";
import type { AppMessageReadStore } from "../application/mark-all-app-messages-read.js";

interface AppMessagesTable {
  message_id: string;
  user_id: string;
  kind: "MANUAL_REVIEW_RESULT" | "SYSTEM";
  summary_zh: string;
  related_content_label_zh: string | null;
  target_route: "MANUAL_REVIEW_RESULT" | null;
  target_resource_id: string | null;
  read_at: Date | null;
  expires_at: Date | null;
  row_version: string;
  created_at: Date;
  updated_at: Date;
}
export interface MessagingDatabase {
  "messaging.app_messages": AppMessagesTable;
}
export interface MessagingClock {
  now(): Date;
}

export class KyselyAppMessageReader
  implements AppMessageReader, AppMessageReadStore
{
  public constructor(
    private readonly database: Kysely<MessagingDatabase>,
    private readonly clock: MessagingClock,
  ) {}
  public async list(accountId: string): Promise<readonly AppMessageSnapshot[]> {
    const rows = await this.database
      .selectFrom("messaging.app_messages")
      .selectAll()
      .where("user_id", "=", accountId)
      .where((expression) =>
        expression.or([
          expression("expires_at", "is", null),
          expression("expires_at", ">", this.clock.now()),
        ]),
      )
      .orderBy("created_at", "desc")
      .execute();
    return rows.map(toSnapshot);
  }
  public async markAllRead(accountId: string, readAt: Date): Promise<number> {
    const result = await this.database
      .updateTable("messaging.app_messages")
      .set({ read_at: readAt, updated_at: readAt })
      .where("user_id", "=", accountId)
      .where("read_at", "is", null)
      .executeTakeFirst();
    return Number(result.numUpdatedRows);
  }
}
const toSnapshot = (row: Selectable<AppMessagesTable>): AppMessageSnapshot => ({
  messageId: row.message_id,
  kind: row.kind,
  summaryZh: row.summary_zh,
  relatedContentLabelZh: row.related_content_label_zh,
  createdAt: new Date(row.created_at),
  read: row.read_at !== null,
  target:
    row.target_route && row.target_resource_id
      ? { route: row.target_route, resourceId: row.target_resource_id }
      : null,
});
