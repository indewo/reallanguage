export type PatternPracticeFeedbackCommand = Readonly<{
  accountId: string;
  chapterId: string;
  patternId: string;
  exampleId: string;
  mode: "READING" | "RESPONSE";
  targetTextEn: string;
  targetStructure: string;
  targetVisible: true;
  audioMimeType: "audio/mp4";
  audioBase64: string;
  commandId: string;
}>;

export interface PatternPracticeFeedbackProvider {
  generate(command: PatternPracticeFeedbackCommand): Promise<unknown>;
}

export class GeneratePatternPracticeFeedback {
  public constructor(
    private readonly provider: PatternPracticeFeedbackProvider,
  ) {}
  public async execute(command: PatternPracticeFeedbackCommand) {
    if (
      !command.targetTextEn.trim() ||
      !command.targetStructure.trim() ||
      !command.audioBase64 ||
      command.audioBase64.length > 900_000
    ) {
      throw new Error("Invalid pattern practice feedback input");
    }
    const output = await this.provider.generate(command);
    if (
      !record(output) ||
      !["READY", "INCONCLUSIVE"].includes(String(output.status))
    ) {
      throw new Error("Invalid pattern practice feedback output");
    }
    if (output.status === "INCONCLUSIVE") return { status: output.status };
    if (
      typeof output.transcript !== "string" ||
      typeof output.feedbackZh !== "string" ||
      !output.feedbackZh.trim() ||
      typeof output.repeatRecommended !== "boolean" ||
      typeof output.confidence !== "number" ||
      output.confidence < 0 ||
      output.confidence > 1
    ) {
      throw new Error("Invalid pattern practice feedback output");
    }
    return {
      status: "READY" as const,
      transcript: output.transcript,
      feedbackZh: output.feedbackZh,
      repeatRecommended: output.repeatRecommended,
      confidence: output.confidence,
    };
  }
}
const record = (value: unknown): value is Record<string, unknown> =>
  typeof value === "object" && value !== null;
