import {
  parseTargetSourceEvidence,
  sourceWordAtOffset,
  targetEvidenceMatchesSentence,
  type TargetSourceEvidence,
} from "./target-source-evidence.js";
import type { LearningTargetCandidate } from "./extract-learning-targets.js";

/** A previously delivered explanation at the clicked occurrence, never formal knowledge. */
export type AcceptedVocabulary = Readonly<{
  display: string;
  senseZh: string;
  unitType: "WORD" | "FIXED_EXPRESSION";
  sourceEvidence: TargetSourceEvidence;
}>;
export type RequestedTargetExpression = Readonly<{
  unitId: string;
  expressionId: string;
  display?: string | undefined;
  acceptedVocabulary?: AcceptedVocabulary | undefined;
}>;
const record = (value: unknown): value is Record<string, unknown> =>
  typeof value === "object" && value !== null && !Array.isArray(value);
const exactKeys = (
  value: Record<string, unknown>,
  expected: readonly string[],
) =>
  Object.keys(value).length === expected.length &&
  expected.every((key) => Object.hasOwn(value, key));
const nonblank = (value: unknown): value is string =>
  typeof value === "string" && value.trim().length > 0;

/** Invalid current-word snapshots fail before any metered/provider request. */
export function validAcceptedVocabularyInput(
  requested: RequestedTargetExpression | undefined,
  source: string | undefined,
): boolean {
  const value: unknown = requested?.acceptedVocabulary;
  if (value === undefined) return true;
  if (
    !requested ||
    !/^word:(0|[1-9]\d*)$/u.test(requested.expressionId) ||
    source === undefined ||
    !record(value) ||
    !exactKeys(value, ["display", "senseZh", "unitType", "sourceEvidence"]) ||
    !nonblank(value.display) ||
    !nonblank(value.senseZh) ||
    !nonblank(requested.display) ||
    (value.unitType !== "WORD" && value.unitType !== "FIXED_EXPRESSION") ||
    !record(value.sourceEvidence) ||
    !exactKeys(value.sourceEvidence, [
      "version",
      "startUtf16",
      "endUtf16",
      "surfaceForm",
      "normalizedForm",
      "allowedForms",
    ])
  )
    return false;
  try {
    const evidence = parseTargetSourceEvidence(value.sourceEvidence);
    const start = Number(requested.expressionId.slice(5));
    const word = sourceWordAtOffset(source, start);
    return (
      Number.isSafeInteger(start) &&
      evidence.startUtf16 === start &&
      word === evidence.surfaceForm &&
      value.display.trim().toLowerCase() === word.toLowerCase() &&
      requested.display.trim().toLowerCase() === word.toLowerCase() &&
      targetEvidenceMatchesSentence(evidence, source)
    );
  } catch {
    return false;
  }
}

const sameEvidence = (
  first: TargetSourceEvidence | null | undefined,
  second: TargetSourceEvidence,
): boolean =>
  first != null &&
  first.version === second.version &&
  first.startUtf16 === second.startUtf16 &&
  first.endUtf16 === second.endUtf16 &&
  first.surfaceForm === second.surfaceForm &&
  first.normalizedForm === second.normalizedForm &&
  first.allowedForms.length === second.allowedForms.length &&
  first.allowedForms.every(
    (form, index) => form === second.allowedForms[index],
  );

/** Do not overwrite a delivered sense with a paraphrase or expand its evidence. */
export function targetMatchesAcceptedVocabulary(
  target: LearningTargetCandidate,
  requested: RequestedTargetExpression | undefined,
): boolean {
  if (!requested?.expressionId.startsWith("word:")) return true;
  if (target.type === "PATTERN") return false;
  const accepted = requested.acceptedVocabulary;
  if (accepted === undefined) return true;
  if (
    target.unitId !== requested.unitId ||
    target.type !== accepted.unitType ||
    target.display !== accepted.display ||
    target.explanationZh !== accepted.senseZh ||
    !sameEvidence(target.sourceEvidence, accepted.sourceEvidence)
  )
    return false;
  return (target.relatedVocabularyItems ?? []).every(
    (item) =>
      item.unitId !== requested.unitId ||
      item.sourceEvidence.startUtf16 !== accepted.sourceEvidence.startUtf16 ||
      item.sourceEvidence.endUtf16 !== accepted.sourceEvidence.endUtf16 ||
      (item.unitType === accepted.unitType &&
        item.display === accepted.display &&
        item.senseZh === accepted.senseZh &&
        sameEvidence(item.sourceEvidence, accepted.sourceEvidence)),
  );
}
