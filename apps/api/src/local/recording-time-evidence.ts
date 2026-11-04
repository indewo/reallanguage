import { randomUUID } from "node:crypto";

type TimedWord = Readonly<{
  word: string;
  start: number;
  end: number;
  textStart: number;
}>;
export type TimedRecording = Readonly<{
  text: string;
  language: string;
  words: readonly TimedWord[];
  uncertainSpans?: readonly Readonly<{ start: number; end: number }>[];
}>;
const record = (value: unknown): Record<string, unknown> =>
  typeof value === "object" && value !== null
    ? (value as Record<string, unknown>)
    : {};
const normalized = (value: string) =>
  value.toLowerCase().replace(/[^\p{L}\p{N}]/gu, "");

type ConfirmationEvidence = {
  needsConfirmation?: boolean;
  alternatives: string[];
  mostLikelyText: string | null;
  mostLikelyIsInference: boolean;
};

/** Legacy boundary-only results have unknown content certainty, not confirmed content. */
const confirmationEvidence = (
  unit: Record<string, unknown>,
): ConfirmationEvidence => {
  const keys = [
    "needsConfirmation",
    "alternatives",
    "mostLikelyText",
    "mostLikelyIsInference",
  ];
  if (keys.every((key) => !(key in unit))) {
    return {
      alternatives: [],
      mostLikelyText: null,
      mostLikelyIsInference: false,
    };
  }
  if (
    !keys.every((key) => key in unit) ||
    typeof unit.needsConfirmation !== "boolean" ||
    !Array.isArray(unit.alternatives) ||
    !unit.alternatives.every(
      (text) => typeof text === "string" && Boolean(text.trim()),
    ) ||
    new Set(unit.alternatives.map((text: string) => text.trim())).size !==
      unit.alternatives.length ||
    typeof unit.mostLikelyIsInference !== "boolean" ||
    !(
      unit.mostLikelyText === null ||
      (typeof unit.mostLikelyText === "string" && unit.mostLikelyText.trim())
    ) ||
    (unit.mostLikelyText !== null) !== unit.mostLikelyIsInference ||
    (!unit.needsConfirmation &&
      (unit.alternatives.length > 0 || unit.mostLikelyText !== null))
  ) {
    throw new Error("Invalid recording confirmation evidence");
  }
  return {
    needsConfirmation: unit.needsConfirmation,
    alternatives: unit.alternatives as string[],
    mostLikelyText: unit.mostLikelyText as string | null,
    mostLikelyIsInference: unit.mostLikelyIsInference,
  };
};

/** A word boundary must come from audio recognition, never from text length or client duration. */
export function validateTimedRecording(
  value: unknown,
  maximumSeconds: number,
): TimedRecording {
  const raw = record(value);
  if (
    !Number.isFinite(maximumSeconds) ||
    maximumSeconds < 1 ||
    maximumSeconds > 120 ||
    typeof raw.text !== "string" ||
    !raw.text.trim() ||
    typeof raw.language !== "string" ||
    !raw.language.trim() ||
    typeof raw.durationSeconds !== "number" ||
    !Number.isFinite(raw.durationSeconds) ||
    raw.durationSeconds <= 0 ||
    raw.durationSeconds > maximumSeconds + 1 ||
    !Array.isArray(raw.words) ||
    !raw.words.length ||
    raw.words.length > 4000
  )
    throw new Error("Missing measured transcription evidence");
  const text = raw.text;
  const durationSeconds = raw.durationSeconds;
  const characterOffsets: number[] = [];
  let offset = 0;
  for (const character of text) {
    for (const unused of normalized(character)) {
      void unused;
      characterOffsets.push(offset);
    }
    offset += character.length;
  }
  const canonical = normalized(text);
  let cursor = 0;
  let previousEnd = 0;
  const words = raw.words.map((value) => {
    const word = record(value);
    if (
      typeof word.word !== "string" ||
      !normalized(word.word) ||
      typeof word.start !== "number" ||
      typeof word.end !== "number" ||
      !Number.isFinite(word.start) ||
      !Number.isFinite(word.end) ||
      word.start < previousEnd ||
      word.end < word.start ||
      word.end > maximumSeconds ||
      word.end > durationSeconds
    ) {
      throw new Error("Invalid measured word boundary");
    }
    const token = normalized(word.word);
    if (canonical.slice(cursor, cursor + token.length) !== token)
      throw new Error("Transcript does not match timestamp evidence");
    const textStart = characterOffsets[cursor];
    if (textStart === undefined)
      throw new Error("Missing transcript word offset");
    cursor += token.length;
    previousEnd = word.end;
    return { word: word.word, start: word.start, end: word.end, textStart };
  });
  if (cursor !== canonical.length)
    throw new Error("Transcript has words without audio timing evidence");
  if (raw.uncertainSpans !== undefined) {
    if (!Array.isArray(raw.uncertainSpans) || raw.uncertainSpans.length > 32)
      throw new Error("Invalid recording context uncertainty");
    const uncertainSpans = raw.uncertainSpans.map((value) => {
      const span = record(value);
      if (
        typeof span.start !== "number" ||
        typeof span.end !== "number" ||
        !Number.isFinite(span.start) ||
        !Number.isFinite(span.end) ||
        span.start < 0 ||
        span.end <= span.start ||
        span.end > maximumSeconds
      )
        throw new Error("Invalid recording context uncertainty");
      return { start: span.start, end: span.end };
    });
    return { text, language: raw.language, words, uncertainSpans };
  }
  return { text, language: raw.language, words };
}

export function assembleRecordingTranscript(
  evidence: TimedRecording,
  value: unknown,
  endReason: string,
) {
  const units = record(value).units;
  if (
    !Array.isArray(units) ||
    !units.length ||
    !["USER_STOPPED", "MAX_DURATION"].includes(endReason)
  ) {
    throw new Error("Missing semantic boundary evidence");
  }
  let cursor = 0;
  let droppedIncompleteTail = false;
  let acceptedWordEnd = 0;
  const segments: {
    segmentId: string;
    startMs: number;
    endMs: number;
    text: string;
    speakerLabel: "UNKNOWN";
    confidence: null;
    alternatives: string[];
    mostLikelyText: string | null;
    mostLikelyIsInference: boolean;
    needsConfirmation?: boolean;
  }[] = [];
  for (const [index, value] of units.entries()) {
    const unit = record(value);
    const start = unit.startWordIndex;
    const end = unit.endWordIndex;
    if (
      typeof start !== "number" ||
      typeof end !== "number" ||
      !Number.isInteger(start) ||
      !Number.isInteger(end) ||
      start !== cursor ||
      end <= start ||
      end > evidence.words.length ||
      !["COMPLETE", "INCOMPLETE", "UNCERTAIN"].includes(String(unit.completion))
    )
      throw new Error("Invalid semantic word range");
    cursor = end;
    if (unit.completion === "UNCERTAIN")
      throw new Error("Uncertain recording audio evidence");
    const confirmation = confirmationEvidence(unit);
    // Zero-duration words are valid provider output but are not independently
    // timed speech evidence. Never let a model's "clear" label hide this
    // uncertainty from the user's existing transcript-confirmation workflow.
    if (
      evidence.words.slice(start, end).some((word) => word.end === word.start)
    )
      confirmation.needsConfirmation = true;
    if (endReason === "MAX_DURATION" && unit.completion !== "COMPLETE") {
      if (unit.completion !== "INCOMPLETE" || index !== units.length - 1)
        throw new Error("Uncertain recording cutoff");
      droppedIncompleteTail = true;
      continue;
    }
    if (
      unit.completion !== "COMPLETE" &&
      (confirmation.alternatives.length > 0 ||
        confirmation.mostLikelyText !== null)
    ) {
      throw new Error("Cannot infer unfinished recording content");
    }
    const first = evidence.words[start]!;
    const last = evidence.words[end - 1]!;
    if (
      evidence.uncertainSpans?.some(
        (span) => span.start <= last.end && span.end >= first.start,
      )
    )
      confirmation.needsConfirmation = true;
    const textEnd = evidence.words[end]?.textStart ?? evidence.text.length;
    const unitText = evidence.text
      .slice(start === 0 ? 0 : first.textStart, textEnd)
      .trim();
    // The current text already has its own explicit confirmation action.
    // Do not present that identical text again as a generated alternative or repair.
    confirmation.alternatives = confirmation.alternatives.filter(
      (alternative) => alternative.trim() !== unitText,
    );
    if (confirmation.mostLikelyText?.trim() === unitText) {
      confirmation.mostLikelyText = null;
      confirmation.mostLikelyIsInference = false;
    }
    acceptedWordEnd = end;
    segments.push({
      segmentId: `segment-${index + 1}`,
      startMs: Math.round(first.start * 1000),
      endMs: Math.round(last.end * 1000),
      text: unitText,
      speakerLabel: "UNKNOWN",
      confidence: null,
      ...confirmation,
    });
  }
  if (cursor !== evidence.words.length || !segments.length)
    throw new Error("No complete supported recording content");
  return {
    transcriptId: randomUUID(),
    language: evidence.language,
    fullText: evidence.text
      .slice(
        0,
        evidence.words[acceptedWordEnd]?.textStart ?? evidence.text.length,
      )
      .trim(),
    segments,
    finalBoundary: {
      acceptedEndMs: segments.at(-1)!.endMs,
      droppedIncompleteTail,
    },
  };
}
