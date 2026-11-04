import type { TimedRecording } from "./recording-time-evidence.js";

export type RecordingWindowEvidence = Readonly<{
  window: Readonly<{
    startSeconds: number;
    durationSeconds: number;
    coreStartSeconds?: number;
    coreEndSeconds?: number;
  }>;
  evidence: TimedRecording;
}>;

/** A specific acoustic seam may be re-observed once, never retimed by guessing. */
export class RecordingWindowEvidenceError extends Error {
  public constructor(
    public readonly windowIndex: number,
    message: string,
  ) {
    super(message);
    this.name = "RecordingWindowEvidenceError";
  }
}

/** Keeps recognition timestamps in the original recording's coordinate space. */
export function joinRecordingWindowEvidence(
  parts: readonly RecordingWindowEvidence[],
) {
  if (!parts.length) throw new Error("No supported recording audio windows");
  const legacy = {
    text: parts.map((part) => part.evidence.text).join(" "),
    language: parts.every(
      (part) => part.evidence.language === parts[0]!.evidence.language,
    )
      ? parts[0]!.evidence.language
      : "mixed",
    durationSeconds:
      parts.at(-1)!.window.startSeconds + parts.at(-1)!.window.durationSeconds,
    words: parts.flatMap(({ window, evidence }) =>
      evidence.words.map(({ word, start, end }) => ({
        word,
        start: start + window.startSeconds,
        end: end + window.startSeconds,
      })),
    ),
  };
  if (
    parts.every(
      ({ window }) =>
        window.coreStartSeconds === undefined &&
        window.coreEndSeconds === undefined,
    )
  )
    return legacy;
  const normalized = (word: string) =>
    word.toLowerCase().replace(/[^\p{L}\p{N}]/gu, "");
  const groups = parts.map(({ window, evidence }, partIndex) => {
    const coreStart = window.coreStartSeconds ?? window.startSeconds;
    const coreEnd =
      window.coreEndSeconds ?? window.startSeconds + window.durationSeconds;
    if (
      (window.coreStartSeconds === undefined) !==
        (window.coreEndSeconds === undefined) ||
      !Number.isFinite(coreStart) ||
      !Number.isFinite(coreEnd) ||
      coreStart < window.startSeconds ||
      coreEnd > window.startSeconds + window.durationSeconds + 1e-8 ||
      coreEnd <= coreStart
    )
      throw new Error("Invalid recording core window");
    return {
      window,
      coreStart,
      coreEnd,
      words: evidence.words.map((word, index) => {
        const start = word.start + window.startSeconds;
        const end = word.end + window.startSeconds;
        const midpoint = (start + end) / 2;
        return {
          word: word.word,
          start,
          end,
          partIndex,
          index,
          display: evidence.text.slice(
            index === 0 ? 0 : word.textStart,
            evidence.words[index + 1]?.textStart ?? evidence.text.length,
          ),
          owned:
            window.coreStartSeconds === undefined ||
            (midpoint >= coreStart &&
              (midpoint < coreEnd ||
                (partIndex === parts.length - 1 && midpoint <= coreEnd))),
          coreOwned:
            window.coreStartSeconds === undefined ||
            (midpoint >= coreStart &&
              (midpoint < coreEnd ||
                (partIndex === parts.length - 1 && midpoint <= coreEnd))),
        };
      }),
    };
  });
  type Word = (typeof groups)[number]["words"][number];
  const timedOccurrence = (left: Word, right: Word) =>
    normalized(left.word) === normalized(right.word) &&
    Math.min(left.end, right.end) - Math.max(left.start, right.start) >
      Math.min(left.end - left.start, right.end - right.start) * 0.5;
  const anchorsAround = (word: Word) => {
    const words = groups[word.partIndex]!.words;
    const before = words
      .slice(0, word.index)
      .findLast((item) => item.end > item.start);
    const after = words
      .slice(word.index + 1)
      .find((item) => item.end > item.start);
    if (!before || !after) return undefined;
    if (
      words
        .slice(before.index + 1, after.index)
        .filter((item) => normalized(item.word) === normalized(word.word))
        .length !== 1
    )
      return undefined;
    return { before, after };
  };
  const sameOccurrence = (left: Word, right: Word) => {
    if (left.partIndex === right.partIndex && left.index === right.index)
      return true;
    if (timedOccurrence(left, right)) return true;
    // Whisper can return an audible token with zero duration. It is not a
    // measurable interval: reconcile it only between two independently matching
    // timed anchors, never by assigning an invented duration or dropping it.
    if (
      left.end !== left.start ||
      right.end !== right.start ||
      normalized(left.word) !== normalized(right.word) ||
      Math.abs(left.start - right.start) > 0.25
    )
      return false;
    const a = anchorsAround(left),
      b = anchorsAround(right);
    return Boolean(
      a &&
      b &&
      timedOccurrence(a.before, b.before) &&
      timedOccurrence(a.after, b.after),
    );
  };
  const anchoredSeams = new Set<number>();
  for (let i = 1; i < groups.length; i++) {
    const left = groups[i - 1]!,
      right = groups[i]!;
    if (
      Math.abs(left.coreEnd - right.coreStart) > 1e-8 ||
      left.window.startSeconds + left.window.durationSeconds <=
        right.window.startSeconds
    )
      continue;
    const leftOwned = left.words.filter((word) => word.owned),
      rightOwned = right.words.filter((word) => word.owned);
    if (!leftOwned.length || !rightOwned.length) continue;
    // Neighboring recognizers can estimate the same word at slightly different
    // times. Hand off at a shared WHOLE word, retaining one provider's original
    // timestamps. Never average, interpolate or clamp a word's audio evidence.
    const anchors = left.words
      .flatMap((word) => {
        if (
          word.index < leftOwned[0]!.index ||
          Math.abs(word.end - left.coreEnd) > 1
        )
          return [];
        return right.words.flatMap((candidate) => {
          const next = right.words[candidate.index + 1];
          return sameOccurrence(word, candidate) &&
            next &&
            word.end <= next.start &&
            Math.abs(candidate.end - right.coreStart) <= 1
            ? [{ word, candidate }]
            : [];
        });
      })
      .sort(
        (a, b) =>
          Math.abs(a.word.end - left.coreEnd) +
          Math.abs(a.candidate.end - right.coreStart) -
          Math.abs(b.word.end - left.coreEnd) -
          Math.abs(b.candidate.end - right.coreStart),
      );
    const anchor = anchors[0];
    if (!anchor) continue; // The normal evidence guard rejects unresolved disagreement.
    anchoredSeams.add(i);
    for (const word of left.words)
      word.owned =
        word.index >= leftOwned[0]!.index && word.index <= anchor.word.index;
    for (const word of right.words)
      word.owned =
        word.index > anchor.candidate.index &&
        (word.start + word.end) / 2 < right.coreEnd;
  }
  const retained: Word[] = [];
  for (const word of groups
    .flatMap((group) => group.words)
    .filter((word) => word.owned)) {
    const previous = retained.at(-1);
    if (previous && previous.end > word.start + 1e-8) {
      // This is only the SAME audio occurrence from adjacent padded requests.
      // Same text at different original times is never a duplicate.
      if (
        previous.partIndex + 1 === word.partIndex &&
        sameOccurrence(previous, word)
      )
        continue;
      throw new RecordingWindowEvidenceError(
        word.partIndex,
        "Conflicting recording window boundary evidence",
      );
    }
    retained.push(word);
  }
  if (!retained.length) throw new Error("No supported recording window text");
  for (let i = 1; i < groups.length; i++) {
    const left = groups[i - 1]!;
    const right = groups[i]!;
    if (left.coreEnd > right.coreStart + 1e-8)
      throw new Error("Overlapping recording core windows");
    if (
      Math.abs(left.coreEnd - right.coreStart) > 1e-8 ||
      left.window.startSeconds + left.window.durationSeconds <=
        right.window.startSeconds
    )
      continue;
    const boundary = left.coreEnd;
    const overlapStart = Math.max(
      left.window.startSeconds,
      right.window.startSeconds,
    );
    const overlapEnd = Math.min(
      left.window.startSeconds + left.window.durationSeconds,
      right.window.startSeconds + right.window.durationSeconds,
    );
    for (const [group, other] of [
      [left, right],
      [right, left],
    ] as const) {
      for (const word of group.words.filter(
        (word) =>
          !word.owned &&
          word.start < overlapEnd &&
          word.end > overlapStart &&
          (!anchoredSeams.has(i) || word.coreOwned),
      )) {
        if (!retained.some((candidate) => sameOccurrence(word, candidate)))
          throw new RecordingWindowEvidenceError(
            word.partIndex,
            `Unresolved recording window context word at window ${word.partIndex + 1}, word ${word.index}; refusing silent omission`,
          );
      }
      for (const word of group.words.filter(
        (word) => word.start < boundary && word.end > boundary,
      )) {
        if (
          (!anchoredSeams.has(i) &&
            !other.words.some((candidate) =>
              sameOccurrence(word, candidate),
            )) ||
          !retained.some((candidate) => sameOccurrence(word, candidate))
        )
          throw new RecordingWindowEvidenceError(
            word.partIndex,
            `Uncertain recording seam word at window ${word.partIndex + 1}, word ${word.index}; original audio confirmation required`,
          );
      }
    }
  }
  let text = "";
  let previous: Word | undefined;
  for (const word of retained) {
    if (
      previous &&
      (previous.partIndex !== word.partIndex ||
        previous.index + 1 !== word.index)
    )
      text = text.trimEnd() + " ";
    text += word.display;
    previous = word;
  }
  const seams = [
    ...new Set(
      parts.flatMap(({ window }) =>
        window.coreStartSeconds === undefined
          ? []
          : [window.coreStartSeconds, window.coreEndSeconds!],
      ),
    ),
  ].filter((time) => time > 0 && time < legacy.durationSeconds);
  return {
    ...legacy,
    text: text.trim(),
    words: retained.map(({ word, start, end }) => ({ word, start, end })),
    uncertainSpans: seams.map((time) => ({
      start: Math.max(0, time - 1),
      end: Math.min(legacy.durationSeconds, time + 1),
    })),
  };
}
