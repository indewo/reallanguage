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
  conciseFeedback?: true | undefined;
}>;

export interface ReadingFeedbackProvider {
  generate(command: ReadingFeedbackCommand): Promise<unknown>;
}

const feedbackAspects = ["WORD_OR_PHRASE", "RHYTHM", "COMPLETENESS"] as const;

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
    const items = record(output) ? output.feedbackItems : null;
    const feedbackItems = Array.isArray(items) && (command.conciseFeedback ? items.length <= 3 : items.length === feedbackAspects.length)
      ? feedbackAspects.map((aspect) => {
          const item: unknown = items.find(
            (candidate: unknown) => record(candidate) && candidate.aspect === aspect,
          );
          return record(item) && item.targetText === undefined
            ? { ...item, targetText: null }
            : item;
        }).filter((item) => item !== undefined)
      : null;
    const validItems = validFeedbackItems(feedbackItems) &&
      Array.isArray(items) && items.length === feedbackItems.length;
    if (
      !record(output) ||
      typeof output.feedbackZh !== "string" ||
      !output.feedbackZh.trim() ||
      typeof output.transcript !== "string" ||
      typeof output.feedbackConfidence !== "number" ||
      !Number.isFinite(output.feedbackConfidence) ||
      output.feedbackConfidence < 0 ||
      output.feedbackConfidence > 1 ||
      !validItems ||
      (output.action !== "CONTINUE" && output.action !== "REPEAT_FOR_CLARITY")
    ) {
      const invalidFields = !record(output) ? ["object"] : [
        ...(typeof output.feedbackZh !== "string" || !output.feedbackZh.trim() ? ["feedbackZh"] : []),
        ...(typeof output.transcript !== "string" ? ["transcript"] : []),
        ...(typeof output.feedbackConfidence !== "number" || !Number.isFinite(output.feedbackConfidence) ||
          output.feedbackConfidence < 0 || output.feedbackConfidence > 1 ? ["feedbackConfidence"] : []),
        ...(!validItems ? ["feedbackItems"] : []),
        ...(output.action !== "CONTINUE" && output.action !== "REPEAT_FOR_CLARITY" ? ["action"] : []),
      ];
      throw new Error(`Invalid reading feedback output (${invalidFields.join(", ")})`);
    }
    if (!validFeedbackItems(feedbackItems)) throw new Error("Invalid reading feedback items");
    const recognizedWords = normalize(output.transcript);
    const contentComplete = Boolean(recognizedWords) && recognizedWords === normalize(command.targetTextEn);
    const issueKind = output.feedbackConfidence === 0 || !recognizedWords
      ? "RECOGNITION_UNCERTAIN"
      : !contentComplete
        ? output.acousticTranscriptAgrees === true && normalize(command.targetTextEn).startsWith(`${recognizedWords} `)
          ? "INCOMPLETE" : "RECOGNITION_UNCERTAIN"
        : feedbackItems.some((item) => record(item) && item.evidence === "AUDIO") ? "PRONUNCIATION" : "NONE";
    return {
      ...(command.conciseFeedback ? { issueKind } : {}),
      feedbackZh: output.feedbackZh,
      feedbackItems,
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
    .replace(/[‘’]/gu, "'")
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
  if (!Array.isArray(value) || value.length > 3) return false;
  return value.every((item) => {
    if (!record(item) || !feedbackAspects.some((aspect) => aspect === item.aspect)) return false;
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
