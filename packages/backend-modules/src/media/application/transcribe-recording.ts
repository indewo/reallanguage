export type RecordingTranscriptionCommand = Readonly<{
  accountId: string;
  recordingId: string;
  durationSeconds: number;
  audioFormat: "audio/mp4";
  languageHint: "zh-CN";
  endReason: "USER_STOPPED" | "MAX_DURATION";
  audioBase64: string;
  commandId: string;
}>;

export type RecordingTranscriptSegment = Readonly<{
  segmentId: string;
  startMs: number;
  endMs: number;
  text: string;
  speakerLabel: "USER" | "OTHER" | "UNKNOWN";
  confidence: number | null;
  /** Missing in legacy results; absence is unknown, not an assertion of clear audio. */
  needsConfirmation?: boolean;
  alternatives: readonly string[];
  mostLikelyText: string | null;
  mostLikelyIsInference: boolean;
}>;

export type RecordingTranscriptionResult = Readonly<{
  transcriptId: string;
  language: string;
  fullText: string;
  segments: readonly RecordingTranscriptSegment[];
  finalBoundary: Readonly<{
    acceptedEndMs: number;
    droppedIncompleteTail: boolean;
  }>;
}>;

export interface RecordingTranscriptionProvider {
  transcribe(
    command: RecordingTranscriptionCommand,
  ): Promise<RecordingTranscriptionResult>;
}

const confirmationValid = (segment: RecordingTranscriptSegment): boolean => {
  if (segment.needsConfirmation === undefined) return true;
  return (
    typeof segment.needsConfirmation === "boolean" &&
    Array.isArray(segment.alternatives) &&
    segment.alternatives.every(
      (text) => typeof text === "string" && Boolean(text.trim()),
    ) &&
    new Set(segment.alternatives.map((text) => text.trim())).size ===
      segment.alternatives.length &&
    (segment.mostLikelyText === null
      ? segment.mostLikelyIsInference === false
      : typeof segment.mostLikelyText === "string" &&
        Boolean(segment.mostLikelyText.trim()) &&
        segment.mostLikelyIsInference === true) &&
    (segment.needsConfirmation ||
      (segment.alternatives.length === 0 && segment.mostLikelyText === null))
  );
};

export class TranscribeRecording {
  public constructor(
    private readonly provider: RecordingTranscriptionProvider,
  ) {}

  public async execute(
    command: RecordingTranscriptionCommand,
  ): Promise<RecordingTranscriptionResult> {
    if (
      !command.accountId.trim() ||
      !command.recordingId.trim() ||
      !command.commandId.trim() ||
      !Number.isFinite(command.durationSeconds) ||
      command.durationSeconds < 1 ||
      command.durationSeconds > 120 ||
      !command.audioBase64.trim()
    )
      throw new Error("Invalid transcription input");
    const result = await this.provider.transcribe(command);
    const maximumEnd = command.durationSeconds * 1_000;
    let previousEnd = 0;
    const segmentsValid = result.segments.every((segment) => {
      const valid =
        segment.segmentId.trim() &&
        segment.text.trim() &&
        Number.isInteger(segment.startMs) &&
        Number.isInteger(segment.endMs) &&
        segment.startMs >= previousEnd &&
        segment.endMs >= segment.startMs &&
        segment.endMs <= maximumEnd &&
        confirmationValid(segment) &&
        (segment.confidence === null ||
          (typeof segment.confidence === "number" &&
            Number.isFinite(segment.confidence) &&
            segment.confidence >= 0 &&
            segment.confidence <= 1)) &&
        (!segment.mostLikelyIsInference ||
          Boolean(segment.mostLikelyText?.trim()));
      previousEnd = segment.endMs;
      return Boolean(valid);
    });
    if (
      !result.transcriptId.trim() ||
      !result.language.trim() ||
      !result.fullText.trim() ||
      !result.segments.length ||
      new Set(result.segments.map((segment) => segment.segmentId)).size !==
        result.segments.length ||
      result.fullText.replace(/\s/gu, "") !==
        result.segments
          .map((segment) => segment.text)
          .join("")
          .replace(/\s/gu, "") ||
      !segmentsValid ||
      !Number.isInteger(result.finalBoundary.acceptedEndMs) ||
      (result.finalBoundary.droppedIncompleteTail &&
        command.endReason !== "MAX_DURATION") ||
      result.finalBoundary.acceptedEndMs < 0 ||
      result.finalBoundary.acceptedEndMs > maximumEnd ||
      result.segments.some(
        (segment) => segment.endMs > result.finalBoundary.acceptedEndMs,
      )
    )
      throw new Error("Invalid transcription output");
    return result;
  }
}
