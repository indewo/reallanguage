import {
  parseTargetSourceEvidence,
  targetEvidenceMatchesSentence,
  wordTargetMatchesSurface,
  type TargetSourceEvidence,
} from "./target-source-evidence.js";
import type {
  ConfirmedLearningChapter,
  LearningTargetCandidate,
} from "./extract-learning-targets.js";

/** Local explanatory metadata, never an independently selected target or knowledge ID. */
export type RelatedVocabularyItem = Readonly<{
  itemRef: string;
  unitId: string;
  display: string;
  senseZh: string;
  unitType: "WORD" | "FIXED_EXPRESSION";
  sourceEvidence: TargetSourceEvidence;
}>;
const record = (value: unknown): value is Record<string, unknown> =>
  value !== null && typeof value === "object" && !Array.isArray(value);
const nonblank = (value: unknown): value is string =>
  typeof value === "string" && value.trim().length > 0;
const keys = (value: Record<string, unknown>, expected: readonly string[]) =>
  Object.keys(value).length === expected.length &&
  expected.every((key) => key in value);

export function validRelatedVocabularyGroup(
  target: {
    unitId: string;
    type: string;
    relatedVocabulary: readonly string[];
    relatedVocabularyItems?: unknown;
    relatedVocabularyItemRefs?: unknown;
  },
  source: string,
): boolean {
  const items = target.relatedVocabularyItems;
  const refs = target.relatedVocabularyItemRefs;
  if (
    (items === undefined && refs === undefined) ||
    (items === null && refs === null)
  )
    return true;
  if (
    !Array.isArray(items) ||
    !Array.isArray(refs) ||
    items.length !== refs.length ||
    (target.type !== "PATTERN" && (items.length > 0 || refs.length > 0)) ||
    refs.some((ref) => !nonblank(ref) || ref.length > 128) ||
    new Set(refs).size !== refs.length
  )
    return false;
  const definitions = new Map<string, RelatedVocabularyItem>();
  const spans = new Set<string>();
  for (const item of items) {
    if (
      !record(item) ||
      !keys(item, [
        "itemRef",
        "unitId",
        "display",
        "senseZh",
        "unitType",
        "sourceEvidence",
      ]) ||
      !nonblank(item.itemRef) ||
      item.itemRef.length > 128 ||
      item.unitId !== target.unitId ||
      !nonblank(item.display) ||
      !nonblank(item.senseZh) ||
      (item.unitType !== "WORD" && item.unitType !== "FIXED_EXPRESSION") ||
      !record(item.sourceEvidence) ||
      !keys(item.sourceEvidence, [
        "version",
        "startUtf16",
        "endUtf16",
        "surfaceForm",
        "normalizedForm",
        "allowedForms",
      ]) ||
      definitions.has(item.itemRef)
    )
      return false;
    let evidence: TargetSourceEvidence;
    try {
      evidence = parseTargetSourceEvidence(item.sourceEvidence);
    } catch {
      return false;
    }
    if (
      !targetEvidenceMatchesSentence(evidence, source) ||
      (item.unitType === "WORD" &&
        !wordTargetMatchesSurface(item.display, evidence.surfaceForm))
    )
      return false;
    const span = JSON.stringify([evidence.startUtf16, evidence.endUtf16]);
    if (spans.has(span)) return false;
    spans.add(span);
    definitions.set(item.itemRef, item as unknown as RelatedVocabularyItem);
  }
  return (
    refs.every(
      (ref, index) =>
        definitions.has(ref) &&
        target.relatedVocabulary[index] === definitions.get(ref)?.display,
    ) && target.relatedVocabulary.length === refs.length
  );
}

export function relatedVocabularyMatchesSentences(
  chapters: readonly ConfirmedLearningChapter[],
  targets: readonly LearningTargetCandidate[],
): boolean {
  const meanings = new Map<string, string>();
  const actualOccurrences = new Set<string>();
  const accept = (
    unitId: string,
    evidence: TargetSourceEvidence,
    type: string,
    display: string,
    meaning: string,
  ) => {
    const key = JSON.stringify([
      unitId,
      evidence.startUtf16,
      evidence.endUtf16,
    ]);
    const value = JSON.stringify([type, display, meaning]);
    if (meanings.has(key) && meanings.get(key) !== value) return false;
    meanings.set(key, value);
    return true;
  };
  for (const target of targets) {
    const source = chapters
      .flatMap((chapter) => chapter.sentences)
      .find(
        (sentence) => sentence.unitId === target.unitId && sentence.isFocus,
      );
    if (!source || !validRelatedVocabularyGroup(target, source.textEn))
      return false;
    if (target.type !== "PATTERN" && target.sourceEvidence != null) {
      const occurrence = JSON.stringify([
        target.unitId,
        target.sourceEvidence.startUtf16,
        target.sourceEvidence.endUtf16,
      ]);
      if (actualOccurrences.has(occurrence)) return false;
      actualOccurrences.add(occurrence);
    }
    if (
      target.type !== "PATTERN" &&
      target.sourceEvidence != null &&
      !accept(
        target.unitId,
        target.sourceEvidence,
        target.type,
        target.display,
        target.explanationZh,
      )
    )
      return false;
    for (const item of target.relatedVocabularyItems ?? []) {
      if (
        !accept(
          item.unitId,
          item.sourceEvidence,
          item.unitType,
          item.display,
          item.senseZh,
        )
      )
        return false;
    }
  }
  return true;
}
