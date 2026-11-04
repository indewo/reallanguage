export type SemanticSourceSpan = Readonly<{
  startCodePoint: number;
  endCodePoint: number;
}>;

export type SemanticUnitCandidate = Readonly<{
  unitId: string;
  order: number;
  sourceSpans: readonly SemanticSourceSpan[];
  textZh: string;
  speakerRole: string | null;
  semanticPurposeZh: string;
  recommendationReasonZh: string | null;
}>;

export type SplitSemanticUnitsCommand = Readonly<{
  accountId: string;
  text: string;
  contentShape: "ARTICLE" | "DIALOGUE";
  commandId: string;
}>;

export interface SemanticUnitSplitProvider {
  split(
    command: SplitSemanticUnitsCommand,
  ): Promise<readonly SemanticUnitCandidate[]>;
}

export class SplitSemanticUnits {
  public constructor(private readonly provider: SemanticUnitSplitProvider) {}

  public async execute(command: SplitSemanticUnitsCommand) {
    let units: readonly SemanticUnitCandidate[];
    try {
      units = await this.provider.split(command);
      validateExactCoverage(command.text, units);
    } catch {
      try {
        // A completed provider attempt can be cached. One stable corrective
        // identity gives the model a chance to repair a coarse result.
        units = await this.provider.split({
          ...command,
          commandId: `${command.commandId}:granularity-retry-1`,
        });
        validateExactCoverage(command.text, units);
      } catch {
        // Splitting is a lossless structural operation, so provider, network,
        // or stale-cache failure must not terminate the user's flow. This
        // deterministic boundary splitter preserves every source code point.
        units = deterministicExactSplit(command.text);
        validateExactCoverage(command.text, units);
      }
    }
    return {
      units,
      coverage: {
        complete: true as const,
        missingSpans: [],
        duplicatedSpans: [],
      },
    };
  }
}

const validateExactCoverage = (
  sourceText: string,
  units: readonly SemanticUnitCandidate[],
): void => {
  if (units.length === 0) throw new Error("Semantic split requires units");
  const source = Array.from(sourceText);
  const seen = new Set<number>();
  units.forEach((unit, index) => {
    if (unit.order !== index || !unit.unitId || !unit.semanticPurposeZh) {
      throw new Error("Semantic units must be ordered and identified");
    }
    const reconstructed = unit.sourceSpans
      .map((span) => {
        if (
          !Number.isInteger(span.startCodePoint) ||
          !Number.isInteger(span.endCodePoint) ||
          span.startCodePoint < 0 ||
          span.endCodePoint <= span.startCodePoint ||
          span.endCodePoint > source.length
        )
          throw new Error("Invalid semantic source span");
        for (
          let cursor = span.startCodePoint;
          cursor < span.endCodePoint;
          cursor += 1
        ) {
          if (seen.has(cursor))
            throw new Error("Duplicated semantic source span");
          seen.add(cursor);
        }
        return source.slice(span.startCodePoint, span.endCodePoint).join("");
      })
      .join("");
    if (reconstructed !== unit.textZh) {
      throw new Error("Semantic unit rewrites source text");
    }
    validateMinimumSemanticGranularity(unit.textZh);
  });
  if (
    seen.size !== source.length ||
    source.some((_, index) => !seen.has(index))
  ) {
    throw new Error("Semantic split does not cover the complete source");
  }
};

const FINISHED_SENTENCE_MARKS = new Set(["。", "！", "？", "!", "?"]);
const PROPOSITION_MARKS = new Set(["，", ",", "；", ";"]);
const PROPOSITION_TRANSITIONS = [
  "因为",
  "由于",
  "但是",
  "不过",
  "可是",
  "所以",
  "因此",
  "于是",
  "然后",
  "接着",
  "后来",
  "最后",
  "结果",
  "而且",
  "并且",
  "同时",
  "其实",
  "另外",
  "但",
] as const;

/** Losslessly split a source into selectable minimum units without AI. */
export const deterministicExactSplit = (
  sourceText: string,
): readonly SemanticUnitCandidate[] => {
  const source = Array.from(sourceText);
  if (source.every((character) => /\s/u.test(character))) return [];
  const boundaries = new Set<number>([0, source.length]);
  for (let index = 0; index < source.length; index += 1) {
    const character = source[index] ?? "";
    if (FINISHED_SENTENCE_MARKS.has(character)) {
      let end = index + 1;
      while (end < source.length && /\s/u.test(source[end] ?? "")) end += 1;
      if (end < source.length) boundaries.add(end);
      continue;
    }
    if (character === "\n" || character === "\r") {
      let end = index + 1;
      while (end < source.length && /[\r\n]/u.test(source[end] ?? "")) end += 1;
      if (
        source.slice(0, index).some((item) => !/\s/u.test(item)) &&
        source.slice(end).some((item) => !/\s/u.test(item))
      )
        boundaries.add(end);
      continue;
    }
    if (!PROPOSITION_MARKS.has(character)) continue;
    let markerStart = index + 1;
    while (markerStart < source.length && /\s/u.test(source[markerStart] ?? ""))
      markerStart += 1;
    const remainder = source.slice(markerStart).join("");
    if (
      PROPOSITION_TRANSITIONS.some((transition) =>
        remainder.startsWith(transition),
      )
    ) {
      boundaries.add(markerStart);
    }
  }
  const ordered = [...boundaries].sort((left, right) => left - right);
  return ordered
    .slice(0, -1)
    .flatMap((startCodePoint, order) => {
      const endCodePoint = ordered[order + 1] ?? source.length;
      const textZh = source.slice(startCodePoint, endCodePoint).join("");
      if (!textZh.trim()) return [];
      return [
        {
          unitId: `deterministic-${order + 1}`,
          order,
          sourceSpans: [{ startCodePoint, endCodePoint }],
          textZh,
          speakerRole: null,
          semanticPurposeZh: "保留原意的最小表达",
          recommendationReasonZh: null,
        },
      ];
    })
    .map((unit, order) => ({
      ...unit,
      order,
      unitId: `deterministic-${order + 1}`,
    }));
};

/** Reject merged propositions while leaving genuine enumerations and indivisible
 * condition/result expressions intact. Punctuation alone is deliberately
 * insufficient; a comma/semicolon must introduce a new discourse proposition. */
const validateMinimumSemanticGranularity = (textZh: string): void => {
  const containsMultipleFinishedSentences = /[。！？!?][\s\r\n]*\S/u.test(
    textZh,
  );
  const propositionTransitions =
    textZh.match(
      /[，,；;][\s\r\n]*(?:因为|由于|但是|但|不过|可是|所以|因此|于是|然后|接着|后来|最后|结果|而且|并且|同时|其实|另外)/gu,
    )?.length ?? 0;
  if (containsMultipleFinishedSentences || propositionTransitions >= 1) {
    throw new Error("Semantic unit is not minimally split");
  }
};
