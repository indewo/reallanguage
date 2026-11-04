export type SceneResponseTrainingFeedbackCommand = Readonly<{
  accountId: string;
  chapterId: string;
  taskId: string;
  sentenceId: string;
  roundIndex: 0 | 1;
  targetTextEn: string;
  contextLines: ReadonlyArray<Readonly<{ speaker: string; textEn: string }>>;
  confirmedTranscript: string;
  userTranscriptConfirmed: true;
  audioMimeType: "audio/mp4";
  audioBase64: string;
  commandId: string;
}>;

export interface SceneResponseTrainingFeedbackProvider {
  generate(command: SceneResponseTrainingFeedbackCommand): Promise<unknown>;
}

export class GenerateSceneResponseFeedback {
  public constructor(
    private readonly provider: SceneResponseTrainingFeedbackProvider,
  ) {}

  public async execute(command: SceneResponseTrainingFeedbackCommand) {
    validateEvidence(command);
    const output = await this.provider.generate(command);
    if (
      !record(output) ||
      !["READY", "INCONCLUSIVE"].includes(String(output.status))
    ) {
      throw new Error("Invalid scene response feedback output");
    }
    if (output.status === "INCONCLUSIVE") return { status: output.status };
    if (
      typeof output.feedbackZh !== "string" ||
      !output.feedbackZh.trim() ||
      typeof output.pronunciationConfidence !== "number" ||
      output.pronunciationConfidence < 0 ||
      output.pronunciationConfidence > 1
    ) {
      throw new Error("Invalid scene response feedback output");
    }
    if (output.pronunciationConfidence < 0.6) {
      return { status: "INCONCLUSIVE" as const };
    }
    return {
      status: "READY" as const,
      feedbackZh: output.feedbackZh,
      pronunciationConfidence: output.pronunciationConfidence,
    };
  }
}

const validateEvidence = (command: SceneResponseTrainingFeedbackCommand) => {
  if (
    !command.targetTextEn.trim() ||
    !command.confirmedTranscript.trim() ||
    !command.audioBase64 ||
    command.audioBase64.length > 900_000 ||
    command.contextLines.length > 3
  ) {
    throw new Error("Invalid scene response feedback input");
  }
};
const record = (value: unknown): value is Record<string, unknown> =>
  typeof value === "object" && value !== null;
