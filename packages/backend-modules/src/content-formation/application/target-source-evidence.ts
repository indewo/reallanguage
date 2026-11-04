/** Positions are UTF-16 half-open offsets in the immutable confirmed English. */
export type TargetSourceEvidence = Readonly<{
  version: 1;
  startUtf16: number;
  endUtf16: number;
  surfaceForm: string;
  normalizedForm: string;
  allowedForms: readonly string[];
}>;

const sourceWordMatches = (source: string) =>
  source.matchAll(/[\p{L}\p{M}\p{N}]+(?:['‘’ʼ＇-][\p{L}\p{M}\p{N}]+)*/gu);

const lexicalWords = (value: string) => [...sourceWordMatches(value)];

const normalizedLexicalText = (value: string) =>
  lexicalWords(value)
    .map((word) => word[0].toLowerCase())
    .join(" ");

const looksLikeCompleteSentence = (value: string) => {
  const words = lexicalWords(value).map((word) => word[0].toLowerCase());
  if (words.length < 4) return false;
  const first = words[0];
  const second = words[1];
  const subjectStart = new Set([
    "i",
    "you",
    "he",
    "she",
    "we",
    "they",
    "it",
    "this",
    "that",
    "there",
  ]);
  const questionStart = new Set([
    "am",
    "is",
    "are",
    "was",
    "were",
    "do",
    "does",
    "did",
    "can",
    "could",
    "will",
    "would",
    "shall",
    "should",
    "have",
    "has",
    "had",
    "may",
    "might",
    "must",
  ]);
  return (
    subjectStart.has(first ?? "") ||
    (questionStart.has(first ?? "") && subjectStart.has(second ?? "")) ||
    (words.length >= 7 && /[.!?]\s*$/u.test(value))
  );
};

/**
 * A vocabulary target must be a lexical unit, never the complete learning
 * sentence relabelled as a WORD or FIXED_EXPRESSION.
 */
export function learningTargetFitsVocabularyBoundary(
  target: Readonly<{
    type: "WORD" | "FIXED_EXPRESSION" | "PATTERN";
    display: string;
    sourceEvidence?: TargetSourceEvidence | null;
  }>,
  source: string,
): boolean {
  if (target.type === "PATTERN") return true;
  const displayWords = lexicalWords(target.display);
  if (target.type === "WORD" && displayWords.length !== 1) return false;
  if (
    target.type === "FIXED_EXPRESSION" &&
    (displayWords.length < 1 ||
      displayWords.length > 7 ||
      looksLikeCompleteSentence(target.display))
  )
    return false;
  const sourceText = normalizedLexicalText(source);
  const displayText = normalizedLexicalText(target.display);
  const evidenceText = target.sourceEvidence
    ? normalizedLexicalText(target.sourceEvidence.surfaceForm)
    : "";
  return (
    displayText.length > 0 &&
    displayText !== sourceText &&
    (evidenceText.length === 0 || evidenceText !== sourceText)
  );
}

/** Shared by original evidence and request/response word:N references. */
export function sourceWordAtOffset(
  source: string,
  offset: number,
): string | undefined {
  return [...sourceWordMatches(source)].find(
    (word) => word.index === offset,
  )?.[0];
}

/** A lemma is descriptive metadata, never proof that another source word is the target. */
export function wordTargetMatchesSurface(
  display: string,
  surfaceForm: string,
): boolean {
  return (
    sourceWordAtOffset(surfaceForm, 0) === surfaceForm &&
    display.trim().toLowerCase() === surfaceForm.toLowerCase()
  );
}

export function parseTargetSourceEvidence(
  value: unknown,
): TargetSourceEvidence {
  if (typeof value !== "object" || value === null)
    throw new Error("Invalid target source evidence");
  const item = value as Record<string, unknown>;
  if (
    item.version !== 1 ||
    !Number.isSafeInteger(item.startUtf16) ||
    !Number.isSafeInteger(item.endUtf16) ||
    (item.startUtf16 as number) < 0 ||
    (item.endUtf16 as number) <= (item.startUtf16 as number) ||
    typeof item.surfaceForm !== "string" ||
    !item.surfaceForm.trim() ||
    item.surfaceForm.length !==
      (item.endUtf16 as number) - (item.startUtf16 as number) ||
    typeof item.normalizedForm !== "string" ||
    !item.normalizedForm.trim() ||
    !Array.isArray(item.allowedForms) ||
    item.allowedForms.length !== 1 ||
    item.allowedForms[0] !== item.surfaceForm
  )
    throw new Error("Invalid target source evidence");
  return {
    version: 1,
    startUtf16: item.startUtf16 as number,
    endUtf16: item.endUtf16 as number,
    surfaceForm: item.surfaceForm,
    normalizedForm: item.normalizedForm,
    allowedForms: [item.surfaceForm],
  };
}

export function targetEvidenceMatchesSentence(
  evidence: TargetSourceEvidence,
  source: string,
): boolean {
  try {
    parseTargetSourceEvidence(evidence);
  } catch {
    return false;
  }
  if (
    source.slice(evidence.startUtf16, evidence.endUtf16) !==
    evidence.surfaceForm
  )
    return false;
  const words = [...sourceWordMatches(source)];
  return (
    words.some((word) => word.index === evidence.startUtf16) &&
    words.some((word) => word.index + word[0].length === evidence.endUtf16)
  );
}
