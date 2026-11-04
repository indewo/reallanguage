export type TranscriptRepairEvidence = Readonly<{
  durationSeconds: number;
  endReason: "USER_STOPPED" | "MAX_DURATION";
  acceptedEndMs: number;
  segments: readonly Readonly<{
    segmentId: string;
    startMs: number;
    endMs: number;
    text: string;
  }>[];
}>;

export type TranscriptRepairCommand = Readonly<{
  accountId: string;
  transcriptId: string;
  currentText: string;
  manuallyEdited: boolean;
  audioFormat: "audio/mp4";
  audioBase64: string;
  commandId: string;
  evidence?: TranscriptRepairEvidence | undefined;
}>;

export type TranscriptRepairResult =
  | Readonly<{ decision: "NO_CHANGE" }>
  | Readonly<{
      decision: "PROPOSE_CHANGES";
      repairedText: string;
      changes: readonly Readonly<{
        originalText: string;
        proposedText: string;
        evidenceSegmentIds: readonly string[];
        reason: "CONFIRMED_OMISSION" | "CONFIRMED_DISFLUENCY";
      }>[];
    }>;

export interface TranscriptRepairProvider {
  repair(command: TranscriptRepairCommand): Promise<TranscriptRepairResult>;
}

export class RepairTranscript {
  public constructor(private readonly provider: TranscriptRepairProvider) {}

  public async execute(
    command: TranscriptRepairCommand,
  ): Promise<TranscriptRepairResult> {
    if (
      !command.accountId.trim() ||
      !command.transcriptId.trim() ||
      !command.currentText.trim() ||
      !command.commandId.trim() ||
      !command.audioBase64.trim()
    )
      throw new Error("Invalid transcript repair input");
    const evidence = command.evidence;
    if (
      !evidence ||
      !Number.isInteger(evidence.durationSeconds) ||
      evidence.durationSeconds < 1 ||
      evidence.durationSeconds > 120 ||
      !Number.isInteger(evidence.acceptedEndMs) ||
      evidence.acceptedEndMs <= 0 ||
      evidence.acceptedEndMs > evidence.durationSeconds * 1000 ||
      !["USER_STOPPED", "MAX_DURATION"].includes(evidence.endReason) ||
      !evidence.segments.length ||
      evidence.segments.length > 4000
    )
      throw new Error("Invalid transcript repair evidence");
    let previousEnd = 0;
    const segmentIds = new Set<string>();
    for (const segment of evidence.segments) {
      if (
        !segment.segmentId.trim() ||
        segmentIds.has(segment.segmentId) ||
        !segment.text.trim() ||
        !Number.isInteger(segment.startMs) ||
        !Number.isInteger(segment.endMs) ||
        segment.startMs < previousEnd ||
        segment.endMs <= segment.startMs ||
        segment.endMs > evidence.acceptedEndMs
      )
        throw new Error("Invalid transcript repair evidence");
      segmentIds.add(segment.segmentId);
      previousEnd = segment.endMs;
    }
    if (previousEnd !== evidence.acceptedEndMs)
      throw new Error("Invalid transcript repair evidence");
    const result = await this.provider.repair(command);
    if (result.decision === "NO_CHANGE") return result;
    const validReasons = new Set([
      "CONFIRMED_OMISSION",
      "CONFIRMED_DISFLUENCY",
    ]);
    if (
      !result.repairedText.trim() ||
      result.changes.length < 1 ||
      result.changes.some(
        (change) =>
          !change.originalText.trim() ||
          !change.proposedText.trim() ||
          !change.evidenceSegmentIds.length ||
          change.evidenceSegmentIds.some((id) => !segmentIds.has(id)) ||
          !validReasons.has(change.reason),
      )
    )
      throw new Error("Invalid transcript repair output");
    let cursor = 0;
    let expected = "";
    for (const change of result.changes) {
      const start = command.currentText.indexOf(change.originalText, cursor);
      if (
        start < 0 ||
        command.currentText.indexOf(change.originalText, start + 1) >= 0 ||
        change.originalText === change.proposedText
      )
        throw new Error("Invalid transcript repair output");
      expected +=
        command.currentText.slice(cursor, start) + change.proposedText;
      cursor = start + change.originalText.length;
    }
    expected += command.currentText.slice(cursor);
    if (
      expected !== result.repairedText ||
      result.repairedText === command.currentText
    )
      throw new Error("Invalid transcript repair output");
    return result;
  }
}
