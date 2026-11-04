import type { SceneResponseTrainingFeedbackCommand } from "./generate-scene-response-feedback.js";

export type SceneResponseEvaluationCommand = Omit<
  SceneResponseTrainingFeedbackCommand,
  "roundIndex"
> &
  Readonly<{ roundIndex: 2; targetHidden: true }>;

export interface SceneResponseEvaluationProvider {
  generate(command: SceneResponseEvaluationCommand): Promise<unknown>;
}

export class EvaluateSceneResponse {
  public constructor(
    private readonly provider: SceneResponseEvaluationProvider,
  ) {}

  public async execute(command: SceneResponseEvaluationCommand) {
    if (
      !command.targetTextEn.trim() ||
      !command.confirmedTranscript.trim() ||
      !command.audioBase64 ||
      command.audioBase64.length > 900_000 ||
      command.contextLines.length > 3
    ) {
      throw new Error("Invalid scene response evaluation input");
    }
    const output = await this.provider.generate(command);
    if (
      !record(output) ||
      !["PASS", "FAIL", "INCONCLUSIVE"].includes(String(output.decision)) ||
      typeof output.correctTurn !== "boolean" ||
      typeof output.targetSentenceComplete !== "boolean" ||
      typeof output.pronunciationIntelligible !== "boolean" ||
      typeof output.feedbackZh !== "string" ||
      !output.feedbackZh.trim() ||
      typeof output.pronunciationConfidence !== "number" ||
      output.pronunciationConfidence < 0 ||
      output.pronunciationConfidence > 1
    ) {
      throw new Error("Invalid scene response evaluation output");
    }
    const conclusive = output.pronunciationConfidence >= 0.6;
    return {
      decision: conclusive ? output.decision : "INCONCLUSIVE",
      correctTurn: output.correctTurn,
      targetSentenceComplete: output.targetSentenceComplete,
      pronunciationIntelligible: output.pronunciationIntelligible,
      feedbackZh: output.feedbackZh,
      pronunciationConfidence: output.pronunciationConfidence,
    };
  }
}

const record = (value: unknown): value is Record<string, unknown> =>
  typeof value === "object" && value !== null;
