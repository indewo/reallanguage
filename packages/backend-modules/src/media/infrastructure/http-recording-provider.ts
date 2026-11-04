import type {
  RecordingTranscriptionProvider,
  RecordingTranscriptionResult,
} from "../application/transcribe-recording.js";
import type {
  TranscriptRepairProvider,
  TranscriptRepairResult,
} from "../application/repair-transcript.js";

export class HttpRecordingProvider
  implements RecordingTranscriptionProvider, TranscriptRepairProvider
{
  public constructor(
    private readonly config: Readonly<{ endpoint: string; apiKey: string }>,
    private readonly fetchImplementation: typeof fetch = fetch,
  ) {}

  public async transcribe(
    command: Parameters<RecordingTranscriptionProvider["transcribe"]>[0],
  ) {
    return parseTranscription(
      await this.request(
        "SPEECH_TRANSCRIPTION_V1",
        command.accountId,
        command.commandId,
        {
          recordingId: command.recordingId,
          durationSeconds: command.durationSeconds,
          audioFormat: command.audioFormat,
          languageHint: command.languageHint,
          endReason: command.endReason,
          audioBase64: command.audioBase64,
        },
      ),
    );
  }

  public async repair(
    command: Parameters<TranscriptRepairProvider["repair"]>[0],
  ) {
    return parseRepair(
      await this.request(
        "TRANSCRIPT_REPAIR_V1",
        command.accountId,
        command.commandId,
        {
          transcriptId: command.transcriptId,
          currentText: command.currentText,
          manuallyEdited: command.manuallyEdited,
          evidence: command.evidence,
          audioFormat: command.audioFormat,
          audioBase64: command.audioBase64,
        },
      ),
    );
  }

  private async request(
    task: string,
    accountId: string,
    commandId: string,
    input: Record<string, unknown>,
  ): Promise<unknown> {
    const response = await this.fetchImplementation(this.config.endpoint, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${this.config.apiKey}`,
        "Content-Type": "application/json",
        "Idempotency-Key": commandId,
        "X-RealLanguage-Account-Id": accountId,
      },
      body: JSON.stringify({ task, input }),
    });
    if (!response.ok) throw new Error("Recording provider request failed");
    return response.json();
  }
}

const parseTranscription = (value: unknown): RecordingTranscriptionResult => {
  if (
    !record(value) ||
    typeof value.transcriptId !== "string" ||
    typeof value.language !== "string" ||
    typeof value.fullText !== "string" ||
    !Array.isArray(value.segments) ||
    !record(value.finalBoundary) ||
    typeof value.finalBoundary.acceptedEndMs !== "number" ||
    typeof value.finalBoundary.droppedIncompleteTail !== "boolean"
  )
    throw new Error("Invalid recording provider response");
  return {
    transcriptId: value.transcriptId,
    language: value.language,
    fullText: value.fullText,
    segments: value.segments.map((segment) => {
      if (
        !record(segment) ||
        typeof segment.segmentId !== "string" ||
        typeof segment.startMs !== "number" ||
        typeof segment.endMs !== "number" ||
        typeof segment.text !== "string" ||
        !["USER", "OTHER", "UNKNOWN"].includes(String(segment.speakerLabel)) ||
        !(
          segment.confidence === null || typeof segment.confidence === "number"
        ) ||
        ("needsConfirmation" in segment &&
          typeof segment.needsConfirmation !== "boolean") ||
        !Array.isArray(segment.alternatives) ||
        !segment.alternatives.every((item) => typeof item === "string") ||
        !(
          segment.mostLikelyText === null ||
          typeof segment.mostLikelyText === "string"
        ) ||
        typeof segment.mostLikelyIsInference !== "boolean"
      )
        throw new Error("Invalid recording provider response");
      return {
        segmentId: segment.segmentId,
        startMs: segment.startMs,
        endMs: segment.endMs,
        text: segment.text,
        speakerLabel: segment.speakerLabel as "USER" | "OTHER" | "UNKNOWN",
        confidence: segment.confidence,
        ...(typeof segment.needsConfirmation === "boolean"
          ? { needsConfirmation: segment.needsConfirmation }
          : {}),
        alternatives: segment.alternatives as string[],
        mostLikelyText: segment.mostLikelyText,
        mostLikelyIsInference: segment.mostLikelyIsInference,
      };
    }),
    finalBoundary: {
      acceptedEndMs: value.finalBoundary.acceptedEndMs,
      droppedIncompleteTail: value.finalBoundary.droppedIncompleteTail,
    },
  };
};

const parseRepair = (value: unknown): TranscriptRepairResult => {
  if (!record(value)) throw new Error("Invalid recording provider response");
  if (value.decision === "NO_CHANGE") return { decision: "NO_CHANGE" };
  if (
    value.decision !== "PROPOSE_CHANGES" ||
    typeof value.repairedText !== "string" ||
    !Array.isArray(value.changes)
  )
    throw new Error("Invalid recording provider response");
  return {
    decision: "PROPOSE_CHANGES",
    repairedText: value.repairedText,
    changes: value.changes.map((change) => {
      if (
        !record(change) ||
        typeof change.originalText !== "string" ||
        typeof change.proposedText !== "string" ||
        !Array.isArray(change.evidenceSegmentIds) ||
        !change.evidenceSegmentIds.every((item) => typeof item === "string") ||
        !["CONFIRMED_OMISSION", "CONFIRMED_DISFLUENCY"].includes(
          String(change.reason),
        )
      )
        throw new Error("Invalid recording provider response");
      return {
        originalText: change.originalText,
        proposedText: change.proposedText,
        evidenceSegmentIds: change.evidenceSegmentIds as string[],
        reason: change.reason as "CONFIRMED_OMISSION" | "CONFIRMED_DISFLUENCY",
      };
    }),
  };
};

const record = (value: unknown): value is Record<string, unknown> =>
  typeof value === "object" && value !== null;
