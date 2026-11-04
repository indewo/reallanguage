export type AppMessageKind = "MANUAL_REVIEW_RESULT" | "SYSTEM";
export type AppMessageTarget = Readonly<{
  route: "MANUAL_REVIEW_RESULT";
  resourceId: string;
}>;
export type AppMessageSnapshot = Readonly<{
  messageId: string;
  kind: AppMessageKind;
  summaryZh: string;
  relatedContentLabelZh: string | null;
  createdAt: Date;
  read: boolean;
  target: AppMessageTarget | null;
}>;
export interface AppMessageReader {
  list(accountId: string): Promise<readonly AppMessageSnapshot[]>;
}

export class ListAppMessages {
  public constructor(private readonly messages: AppMessageReader) {}
  public async execute(query: {
    accountId: string;
  }): Promise<readonly AppMessageSnapshot[]> {
    return [...(await this.messages.list(query.accountId))].sort(
      (left, right) => right.createdAt.getTime() - left.createdAt.getTime(),
    );
  }
}
