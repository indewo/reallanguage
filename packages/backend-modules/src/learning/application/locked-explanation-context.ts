import {
  parseTargetSourceEvidence,
  targetEvidenceMatchesSentence,
  wordTargetMatchesSurface,
  type TargetSourceEvidence,
} from "../../content-formation/application/target-source-evidence.js";

export type LockedExplanationVocabulary = Readonly<{
  display: string;
  senseZh: string;
  unitType: "WORD" | "FIXED_EXPRESSION";
  sourceEvidence: TargetSourceEvidence;
}>;
export type LockedExplanationTarget = Readonly<{
  targetId: string;
  type: "WORD" | "FIXED_EXPRESSION" | "PATTERN";
  display: string;
  explanationZh: string;
  structureParts: readonly string[];
  sourceEvidence: TargetSourceEvidence | null;
  relatedVocabulary: readonly LockedExplanationVocabulary[] | null;
}>;
export type PositionalExplanationExpression = Readonly<{
  expressionId: string;
  display: string;
  startUtf16?: number | undefined;
  endUtf16?: number | undefined;
}>;
const record = (value: unknown): value is Record<string, unknown> =>
  typeof value === "object" && value !== null && !Array.isArray(value);
const nonblank = (value: unknown): value is string =>
  typeof value === "string" && value.trim().length > 0;
const keys = (value: Record<string, unknown>, expected: readonly string[]) =>
  Object.keys(value).length === expected.length &&
  expected.every((key) => Object.hasOwn(value, key));
const evidenceKeys = [
  "version",
  "startUtf16",
  "endUtf16",
  "surfaceForm",
  "normalizedForm",
  "allowedForms",
];

function validEvidence(
  value: unknown,
  text: string,
  display: string,
  type: string,
): value is TargetSourceEvidence {
  if (!record(value) || !keys(value, evidenceKeys)) return false;
  try {
    const evidence = parseTargetSourceEvidence(value);
    return (
      targetEvidenceMatchesSentence(evidence, text) &&
      (type !== "WORD" ||
        wordTargetMatchesSurface(display, evidence.surfaceForm))
    );
  } catch {
    return false;
  }
}

/** Current-focus snapshots only. Null evidence is historical unknown, never a guessed occurrence. */
export function validLockedExplanationContext(
  lockedTargets: unknown,
  text: string,
): boolean {
  if (lockedTargets === undefined) return true;
  if (!Array.isArray(lockedTargets)) return false;
  const ids = new Set<string>();
  const actualSpans = new Set<string>();
  const meanings = new Map<string, string>();
  const acceptMeaning = (
    evidence: TargetSourceEvidence,
    display: string,
    meaning: string,
    type: string,
  ) => {
    const span = JSON.stringify([evidence.startUtf16, evidence.endUtf16]);
    const signature = JSON.stringify([display, meaning, type]);
    if (meanings.has(span) && meanings.get(span) !== signature) return false;
    meanings.set(span, signature);
    return true;
  };
  for (const target of lockedTargets) {
    if (
      !record(target) ||
      !keys(target, [
        "targetId",
        "type",
        "display",
        "explanationZh",
        "structureParts",
        "sourceEvidence",
        "relatedVocabulary",
      ]) ||
      !nonblank(target.targetId) ||
      target.targetId.length > 128 ||
      ids.has(target.targetId) ||
      !["WORD", "FIXED_EXPRESSION", "PATTERN"].includes(String(target.type)) ||
      !nonblank(target.display) ||
      !nonblank(target.explanationZh) ||
      !Array.isArray(target.structureParts) ||
      target.structureParts.some((part) => !nonblank(part)) ||
      (target.type === "PATTERN" &&
        target.sourceEvidence !== null &&
        target.structureParts.length === 0)
    )
      return false;
    ids.add(target.targetId);
    if (target.sourceEvidence !== null) {
      if (
        !validEvidence(
          target.sourceEvidence,
          text,
          target.display,
          String(target.type),
        )
      )
        return false;
      if (target.type !== "PATTERN") {
        const span = JSON.stringify([
          target.sourceEvidence.startUtf16,
          target.sourceEvidence.endUtf16,
        ]);
        if (
          actualSpans.has(span) ||
          !acceptMeaning(
            target.sourceEvidence,
            target.display,
            target.explanationZh,
            String(target.type),
          )
        )
          return false;
        actualSpans.add(span);
      }
    }
    if (target.relatedVocabulary === null) continue;
    if (
      !Array.isArray(target.relatedVocabulary) ||
      (target.type !== "PATTERN" && target.relatedVocabulary.length > 0)
    )
      return false;
    const relatedSpans = new Set<string>();
    for (const item of target.relatedVocabulary) {
      if (
        !record(item) ||
        !keys(item, ["display", "senseZh", "unitType", "sourceEvidence"]) ||
        !nonblank(item.display) ||
        !nonblank(item.senseZh) ||
        (item.unitType !== "WORD" && item.unitType !== "FIXED_EXPRESSION") ||
        !validEvidence(item.sourceEvidence, text, item.display, item.unitType)
      )
        return false;
      const span = JSON.stringify([
        item.sourceEvidence.startUtf16,
        item.sourceEvidence.endUtf16,
      ]);
      if (
        relatedSpans.has(span) ||
        !acceptMeaning(
          item.sourceEvidence,
          item.display,
          item.senseZh,
          item.unitType,
        )
      )
        return false;
      relatedSpans.add(span);
    }
  }
  return true;
}

/** New requests cite the exact full word/phrase; legacy no-position requests remain readable. */
export function validExplanationExpression(
  expression: PositionalExplanationExpression | undefined,
  text: string,
  hasLockedContext: boolean,
): boolean {
  if (expression === undefined) return true;
  if (!nonblank(expression.display)) return false;
  const { startUtf16, endUtf16, display } = expression;
  if (startUtf16 === undefined && endUtf16 === undefined)
    return !hasLockedContext && text.includes(display);
  if (
    !Number.isSafeInteger(startUtf16) ||
    !Number.isSafeInteger(endUtf16) ||
    startUtf16 === undefined ||
    endUtf16 === undefined
  )
    return false;
  return targetEvidenceMatchesSentence(
    {
      version: 1,
      startUtf16,
      endUtf16,
      surfaceForm: display,
      normalizedForm: display,
      allowedForms: [display],
    },
    text,
  );
}

/** Pattern-associated vocabulary stays explanatory; it never becomes a selected target. */
export function lockedMeaningForExpression(
  targets: readonly LockedExplanationTarget[] | undefined,
  expression:
    | Pick<PositionalExplanationExpression, "startUtf16" | "endUtf16">
    | undefined,
): string | undefined {
  if (
    !expression ||
    expression.startUtf16 === undefined ||
    expression.endUtf16 === undefined
  )
    return undefined;
  const matches = (evidence: TargetSourceEvidence | null) =>
    evidence !== null &&
    evidence.startUtf16 === expression.startUtf16 &&
    evidence.endUtf16 === expression.endUtf16;
  for (const target of targets ?? []) {
    if (target.type !== "PATTERN" && matches(target.sourceEvidence))
      return target.explanationZh;
    const related = target.relatedVocabulary?.find((item) =>
      matches(item.sourceEvidence),
    );
    if (related) return related.senseZh;
  }
  return undefined;
}
