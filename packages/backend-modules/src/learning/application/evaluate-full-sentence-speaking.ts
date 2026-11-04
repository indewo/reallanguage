import { record, validateAudio } from "./generate-speaking-transcription.js";

export type FullSentenceSpeakingEvaluationCommand = Readonly<{
  accountId: string;
  chapterId: string;
  sentenceId: string;
  targetTextEn: string;
  confirmedTranscript: string;
  targetHidden: true;
  answerAudioPlayed: boolean;
  userTranscriptConfirmed: true;
  audioMimeType: "audio/mp4";
  audioBase64: string;
  commandId: string;
}>;

export interface FullSentenceSpeakingEvaluationProvider {
  generate(command: FullSentenceSpeakingEvaluationCommand): Promise<unknown>;
}

export class EvaluateFullSentenceSpeaking {
  public constructor(
    private readonly provider: FullSentenceSpeakingEvaluationProvider,
  ) {}

  public async execute(command: FullSentenceSpeakingEvaluationCommand) {
    validateAudio(command.audioMimeType, command.audioBase64);
    if (!command.targetTextEn.trim() || !command.confirmedTranscript.trim()) {
      throw new Error("Invalid full sentence speaking input");
    }
    const output = await this.provider.generate(command);
    if (
      !record(output) ||
      !["PASS", "FAIL", "INCONCLUSIVE"].includes(String(output.decision)) ||
      !Number.isInteger(output.completenessScore) ||
      Number(output.completenessScore) < 0 ||
      Number(output.completenessScore) > 100 ||
      typeof output.coreMeaningRecognizable !== "boolean" ||
      typeof output.pronunciationIntelligible !== "boolean" ||
      typeof output.feedbackZh !== "string" ||
      !output.feedbackZh.trim() ||
      typeof output.pronunciationConfidence !== "number" ||
      !Number.isFinite(output.pronunciationConfidence) ||
      output.pronunciationConfidence < 0 ||
      output.pronunciationConfidence > 1
    )
      throw new Error("Invalid full sentence speaking output");
    return {
      decision:
        output.pronunciationConfidence < 0.6 ? "INCONCLUSIVE" : output.decision,
      completenessScore: output.completenessScore,
      coreMeaningRecognizable: output.coreMeaningRecognizable,
      pronunciationIntelligible: output.pronunciationIntelligible,
      feedbackZh: output.feedbackZh,
      pronunciationConfidence: output.pronunciationConfidence,
    };
  }
}
