import { record, validateAudio } from "./generate-speaking-transcription.js";

export type PatternIndependentEvaluationCommand = Readonly<{
  accountId: string;
  chapterId: string;
  patternId: string;
  exampleId: string;
  targetResponseEn: string;
  targetStructure: string;
  targetHidden: true;
  answerAudioPlayed: false;
  helpUsed: false;
  audioMimeType: "audio/mp4";
  audioBase64: string;
  commandId: string;
}>;

export interface PatternIndependentEvaluationProvider {
  generate(command: PatternIndependentEvaluationCommand): Promise<unknown>;
}

export class EvaluatePatternIndependent {
  public constructor(
    private readonly provider: PatternIndependentEvaluationProvider,
  ) {}

  public async execute(command: PatternIndependentEvaluationCommand) {
    validateAudio(command.audioMimeType, command.audioBase64);
    if (!command.targetResponseEn.trim() || !command.targetStructure.trim()) {
      throw new Error("Invalid independent pattern input");
    }
    const output = await this.provider.generate(command);
    if (
      !record(output) ||
      !["PASS", "FAIL", "INCONCLUSIVE"].includes(String(output.decision)) ||
      typeof output.structurePreserved !== "boolean" ||
      typeof output.contentSuitable !== "boolean" ||
      typeof output.pronunciationIntelligible !== "boolean" ||
      typeof output.feedbackZh !== "string" ||
      !output.feedbackZh.trim() ||
      typeof output.confidence !== "number" ||
      !Number.isFinite(output.confidence) ||
      output.confidence < 0 ||
      output.confidence > 1
    )
      throw new Error("Invalid independent pattern output");
    const allCriteriaSatisfied =
      output.structurePreserved &&
      output.contentSuitable &&
      output.pronunciationIntelligible;
    const contradictoryDecision =
      (output.decision === "PASS" && !allCriteriaSatisfied) ||
      (output.decision === "FAIL" && allCriteriaSatisfied);
    // A contradictory candidate is not evidence for the opposite formal result.
    // Preserve its observations but require another valid judgment.
    return {
      decision:
        output.confidence < 0.6 || contradictoryDecision
          ? "INCONCLUSIVE"
          : output.decision,
      structurePreserved: output.structurePreserved,
      contentSuitable: output.contentSuitable,
      pronunciationIntelligible: output.pronunciationIntelligible,
      feedbackZh: output.feedbackZh,
      confidence: output.confidence,
    };
  }
}
