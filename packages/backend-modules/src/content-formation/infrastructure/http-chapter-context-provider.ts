import type {
  ChapterContextCandidate,
  ChapterContextCommand,
  ChapterContextProvider,
} from "../application/select-chapter-context.js";

export class HttpChapterContextProvider implements ChapterContextProvider {
  public constructor(
    private readonly config: Readonly<{ endpoint: string; apiKey: string }>,
    private readonly fetchImplementation: typeof fetch = fetch,
  ) {}
  public async select(
    command: ChapterContextCommand,
  ): Promise<readonly ChapterContextCandidate[]> {
    const response = await this.fetchImplementation(this.config.endpoint, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${this.config.apiKey}`,
        "Content-Type": "application/json",
        "Idempotency-Key": command.commandId,
        "X-RealLanguage-Account-Id": command.accountId,
      },
      body: JSON.stringify({
        task: "CHAPTER_CONTEXT_SELECTION_V1",
        input: {
          units: command.units,
          selectedUnitIds: command.selectedUnitIds,
        },
      }),
    });
    if (!response.ok)
      throw new Error("Chapter context provider request failed");
    const value: unknown = await response.json();
    if (!isRecord(value) || !Array.isArray(value.chapters))
      throw new Error("Invalid chapter context provider response");
    return value.chapters.map((candidate) => {
      if (
        !isRecord(candidate) ||
        !Array.isArray(candidate.unitIds) ||
        candidate.unitIds.some((id) => typeof id !== "string")
      )
        throw new Error("Invalid chapter context candidate");
      if (typeof candidate.chapterId !== "string" || !candidate.chapterId)
        throw new Error("Invalid chapter identity");
      return {
        chapterId: candidate.chapterId,
        unitIds: candidate.unitIds as string[],
      };
    });
  }
}
const isRecord = (value: unknown): value is Record<string, unknown> =>
  typeof value === "object" && value !== null;
