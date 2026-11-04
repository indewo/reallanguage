import { createHash } from "node:crypto";

export type SceneResponseGenerationCommand = Readonly<{
  accountId: string;
  chapterId: string;
  focusSentenceId: string;
  chapterSentences: ReadonlyArray<
    Readonly<{
      sentenceId: string;
      textEn: string;
      textZh: string;
      isFocus: boolean;
    }>
  >;
  commandId: string;
}>;

export interface SceneResponseGenerationProvider {
  generate(command: SceneResponseGenerationCommand): Promise<unknown>;
}

export class GenerateSceneResponse {
  public constructor(
    private readonly provider: SceneResponseGenerationProvider,
  ) {}

  public async execute(command: SceneResponseGenerationCommand) {
    if (
      ![
        command.accountId,
        command.chapterId,
        command.focusSentenceId,
        command.commandId,
      ].every((value) => typeof value === "string" && value.trim().length > 0)
    ) {
      throw new Error("Invalid scene response generation input");
    }
    const focusIndex = command.chapterSentences.findIndex(
      (sentence) =>
        sentence.sentenceId === command.focusSentenceId && sentence.isFocus,
    );
    if (
      focusIndex < 0 ||
      command.chapterSentences.length < 1 ||
      command.chapterSentences.length > 4
    ) {
      throw new Error("Invalid scene response generation input");
    }
    const target = command.chapterSentences[focusIndex];
    if (!target) throw new Error("Invalid scene response generation input");
    const output = await this.provider.generate(command);
    if (!record(output) || !Array.isArray(output.contextLines)) {
      throw new Error("Invalid scene response generation output");
    }
    const contextLines = output.contextLines.map((line) => {
      if (
        !record(line) ||
        typeof line.speaker !== "string" ||
        !line.speaker.trim() ||
        typeof line.textEn !== "string" ||
        !line.textEn.trim() ||
        normalized(line.textEn) === normalized(target.textEn)
      ) {
        throw new Error("Invalid scene response generation output");
      }
      return { speaker: line.speaker, textEn: line.textEn };
    });
    const backgroundEn = output.backgroundEn;
    if (
      typeof output.taskId !== "string" ||
      !output.taskId.trim() ||
      output.sentenceId !== command.focusSentenceId ||
      output.targetSentenceEn !== target.textEn ||
      contextLines.length > 3 ||
      (backgroundEn !== null &&
        (typeof backgroundEn !== "string" || !backgroundEn.trim())) ||
      (focusIndex === 0 && backgroundEn === null) ||
      (focusIndex > 0 && contextLines.length === 0)
    ) {
      throw new Error("Invalid scene response generation output");
    }
    return {
      // Providers may repeat labels across sentences. The fixed task belongs to
      // one account/chapter/focus, including when a cached response is replayed.
      taskId: `scene-${createHash("sha256")
        .update(
          JSON.stringify([
            "scene-response-task:v1",
            command.accountId,
            command.chapterId,
            command.focusSentenceId,
          ]),
        )
        .digest("hex")}`,
      sentenceId: output.sentenceId,
      backgroundEn,
      contextLines,
      targetSentenceEn: output.targetSentenceEn,
    };
  }
}

const normalized = (value: string) =>
  value
    .toLowerCase()
    .replaceAll(/[\p{P}\p{S}\s]+/gu, " ")
    .trim();
const record = (value: unknown): value is Record<string, unknown> =>
  typeof value === "object" && value !== null;
