export type ReadingFeedbackCommand = Readonly<{
  accountId: string;
  chapterId: string;
  sentenceId: string;
  targetTextEn: string;
  completionSignal:
    "TOUCH_REACHED_END" | "RECOGNITION_COMPLETE" | "MANUAL_CONFIRMATION";
  audioMimeType: "audio/mp4";
  audioBase64: string;
  commandId: string;
}>;

export interface ReadingFeedbackProvider {
  generate(command: ReadingFeedbackCommand): Promise<unknown>;
}

export class GenerateReadingFeedback {
  public constructor(private readonly provider: ReadingFeedbackProvider) {}

  public async execute(command: ReadingFeedbackCommand) {
    if (
      !command.targetTextEn.trim() ||
      command.audioMimeType !== "audio/mp4" ||
      !command.audioBase64 ||
      command.audioBase64.length > 900_000
    ) {
      throw new Error("Invalid reading feedback input");
    }
    const output = await this.provider.generate(command);
    if (
      !record(output) ||
      typeof output.feedbackZh !== "string" ||
      !output.feedbackZh.trim() ||
      typeof output.transcript !== "string" ||
      typeof output.feedbackConfidence !== "number" ||
      output.feedbackConfidence < 0 ||
      output.feedbackConfidence > 1 ||
      !validFeedbackItems(output.feedbackItems) ||
      (output.action !== "CONTINUE" && output.action !== "REPEAT_FOR_CLARITY")
    ) {
      throw new Error("Invalid reading feedback output");
    }
    const contentComplete =
      normalize(output.transcript) === normalize(command.targetTextEn);
    return {
      feedbackZh: output.feedbackZh,
      feedbackItems: output.feedbackItems,
      transcript: output.transcript,
      contentComplete,
      feedbackConfidence: output.feedbackConfidence,
      action: output.action,
    };
  }
}

const normalize = (value: string): string =>
  value
    .normalize("NFKC")
    .toLocaleLowerCase("en-US")
    .replace(/[^a-z0-9']+/g, " ")
    .trim();

const record = (value: unknown): value is Record<string, unknown> =>
  typeof value === "object" && value !== null;

const validFeedbackItems = (
  value: unknown,
): value is ReadonlyArray<{
  aspect: "WORD_OR_PHRASE" | "RHYTHM" | "COMPLETENESS";
  targetText: string | null;
  observationZh: string;
  suggestionZh: string;
  evidence: "AUDIO" | "TRANSCRIPT" | "GENERAL";
}> => {
  if (!Array.isArray(value) || value.length !== 3) return false;
  const expected = ["WORD_OR_PHRASE", "RHYTHM", "COMPLETENESS"];
  return value.every((item, index) => {
    if (!record(item) || item.aspect !== expected[index]) return false;
    if (
      item.targetText !== null &&
      (typeof item.targetText !== "string" || !item.targetText.trim())
    )
      return false;
    return (
      typeof item.observationZh === "string" &&
      !!item.observationZh.trim() &&
      typeof item.suggestionZh === "string" &&
      !!item.suggestionZh.trim() &&
      (item.evidence === "AUDIO" ||
        item.evidence === "TRANSCRIPT" ||
        item.evidence === "GENERAL")
    );
  });
};
