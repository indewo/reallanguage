export interface AppMessageReadStore {
  markAllRead(accountId: string, readAt: Date): Promise<number>;
}

export interface MessagingWriteClock {
  now(): Date;
}

export class MarkAllAppMessagesRead {
  public constructor(
    private readonly messages: AppMessageReadStore,
    private readonly clock: MessagingWriteClock,
  ) {}

  public async execute(command: {
    accountId: string;
  }): Promise<{ updatedCount: number }> {
    return {
      updatedCount: await this.messages.markAllRead(
        command.accountId,
        this.clock.now(),
      ),
    };
  }
}
