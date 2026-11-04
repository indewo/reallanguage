import type {
  ConfirmedLearningChapter,
  LearningTargetCandidate,
} from "./extract-learning-targets.js";
import type { KnownKnowledgeCandidate } from "./generate-english-candidates.js";

// These are the exact items referenced by the selected English candidate, not
// a fresh vocabulary search and not a textual guess at an existing identity.
export const validKnownTargetInput = (
  chapters: readonly ConfirmedLearningChapter[],
): boolean => {
  const all = new Map<string, KnownKnowledgeCandidate>();
  for (const sentence of chapters.flatMap((chapter) => chapter.sentences)) {
    const items = sentence.reusedKnowledge ?? [];
    if (
      !Array.isArray(items) ||
      items.length > 16 ||
      new Set(items.map((item) => item.ref)).size !== items.length
    )
      return false;
    for (const item of items) {
      if (
        !["VOCABULARY", "PATTERN"].includes(item.type) ||
        !item.ref.startsWith(`${item.type}:`) ||
        !item.ref.slice(item.type.length + 1).trim() ||
        item.ref.length > 128 ||
        /\s/u.test(item.ref) ||
        !item.display.trim() ||
        !item.meaningZh.trim()
      )
        return false;
      const previous = all.get(item.ref);
      if (
        previous &&
        (previous.type !== item.type ||
          previous.display !== item.display ||
          previous.meaningZh !== item.meaningZh)
      )
        return false;
      all.set(item.ref, item);
    }
  }
  return (
    all.size <= 16 &&
    [...all.values()].reduce(
      (count, item) =>
        count +
        Array.from(item.display).length +
        Array.from(item.meaningZh).length,
      0,
    ) <= 4000
  );
};

export const knownTargetsMatchSentences = (
  chapters: readonly ConfirmedLearningChapter[],
  targets: readonly LearningTargetCandidate[],
  requestedOnly: boolean,
): boolean => {
  const sentences = new Map(
    chapters
      .flatMap((chapter) => chapter.sentences)
      .map((sentence) => [sentence.unitId, sentence]),
  );
  const counts = new Map<string, Set<string>>();
  for (const target of targets) {
    const ref = target.reusedKnowledgeRef;
    if (ref == null) continue;
    const sentence = sentences.get(target.unitId);
    const item = sentence?.reusedKnowledge?.find((known) => known.ref === ref);
    if (
      !sentence?.isFocus ||
      !item ||
      !target.sourceEvidence ||
      target.selectedByDefault !== true ||
      item.type !== (target.type === "PATTERN" ? "PATTERN" : "VOCABULARY") ||
      item.meaningZh.trim() !== target.explanationZh.trim() ||
      (item.type === "PATTERN" &&
        item.display.trim() !== target.display.trim()) ||
      (item.type === "VOCABULARY" &&
        ![target.display, target.sourceEvidence.normalizedForm].some(
          (form) => canonicalForm(form) === canonicalForm(item.display),
        ))
    )
      return false;
    const refs = counts.get(target.unitId) ?? new Set<string>();
    if (refs.has(ref)) return false;
    refs.add(ref);
    counts.set(target.unitId, refs);
  }
  // A clicked-word request returns that one target; it must not pay to regenerate
  // unrelated defaults that are already held by the client.
  return (
    requestedOnly ||
    [...sentences.values()].every(
      (sentence) =>
        !sentence.isFocus ||
        (sentence.reusedKnowledge ?? []).every((item) =>
          counts.get(sentence.unitId)?.has(item.ref),
        ),
    )
  );
};

// Reject contradictory identity claims. This is not a guessed inflection table
// and does not establish that an AI-proposed lemma or meaning is semantically true.
const canonicalForm = (value: string): string =>
  value.normalize("NFC").trim().toLowerCase();
