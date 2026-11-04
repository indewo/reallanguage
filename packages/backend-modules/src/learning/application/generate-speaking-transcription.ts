export type SpeakingTranscriptionCommand = Readonly<{
  accountId: string;
  chapterId: string;
  sentenceId: string;
  audioMimeType: "audio/mp4";
  audioBase64: string;
  commandId: string;
}>;

export interface SpeakingTranscriptionProvider {
  generate(command: SpeakingTranscriptionCommand): Promise<unknown>;
}

export class GenerateSpeakingTranscription {
  public constructor(
    private readonly provider: SpeakingTranscriptionProvider,
  ) {}

  public async execute(command: SpeakingTranscriptionCommand) {
    validateAudio(command.audioMimeType, command.audioBase64);
    const output = await this.provider.generate(command);
    if (
      !record(output) ||
      typeof output.transcript !== "string" ||
      !Array.isArray(output.lowConfidenceSegments) ||
      !output.lowConfidenceSegments.every(
        (value) => typeof value === "string",
      ) ||
      (output.confidence !== null &&
        (typeof output.confidence !== "number" ||
          !Number.isFinite(output.confidence) ||
          output.confidence < 0 ||
          output.confidence > 1))
    )
      throw new Error("Invalid speaking transcription output");
    return {
      transcript: output.transcript,
      lowConfidenceSegments: output.lowConfidenceSegments,
      confidence: output.confidence,
    };
  }
}

export const validateAudio = (mimeType: string, base64: string): void => {
  if (mimeType !== "audio/mp4" || !base64 || base64.length > 900_000) {
    throw new Error("Invalid speaking audio input");
  }
};

export const record = (value: unknown): value is Record<string, unknown> =>
  typeof value === "object" && value !== null;
