/** API-local cleanup also runs when the separate payment scheduler is intentionally disabled. */
export class AiResultCleanup {
  private timer: ReturnType<typeof setInterval> | undefined;
  private active: Promise<unknown> | null = null;
  public constructor(
    private readonly purge: () => Promise<unknown>,
    private readonly onFailure: () => void = () =>
      console.warn("AI_RESULT_CLEANUP_FAILED"),
  ) {}
  public async onModuleInit() {
    await this.purge();
    this.timer = setInterval(() => {
      if (this.active) return;
      this.active = this.purge()
        .catch(() => this.onFailure())
        .finally(() => {
          this.active = null;
        });
    }, 60_000);
    this.timer.unref();
  }
  public async onModuleDestroy() {
    if (this.timer) clearInterval(this.timer);
    await this.active;
  }
}
