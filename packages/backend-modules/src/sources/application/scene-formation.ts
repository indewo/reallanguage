import { createHash, randomInt } from "node:crypto";
import type {
  ContentGenerationSettings,
  ContentGenerationSettingsReader,
} from "../../content-formation/application/generate-english-candidates.js";

export type SceneStartPayload =
  | Readonly<{
      kind: "RECOMMENDATION";
      recommendationId: string;
      shortTopicZh: string;
      whyRelevantZh: string;
      sourceRefs: readonly string[];
    }>
  | Readonly<{
      kind: "TEMPLATE";
      templateId: string;
      titleZh: string;
      scopeZh: string;
    }>
  | Readonly<{ kind: "DIRECT_DESCRIPTION"; description: string }>;
export type SceneAnswerPayload = Readonly<{
  questionId: string;
  questionZh?: string | undefined;
  answerZh: string;
}>;
export type SceneUnitPayload = Readonly<{
  unitId: string;
  speaker: "USER" | "OTHER" | null;
  textZh: string;
  factReferences: readonly string[];
}>;
export type SceneSessionPayload = Readonly<{
  operationId: string;
  start: SceneStartPayload;
  contentType: "DIALOGUE" | "ARTICLE";
  answers: readonly SceneAnswerPayload[];
  collectedFacts: readonly string[];
  missingNonBlockingFacts: readonly string[];
  practiceGoalRef?: string | undefined;
}>;
export interface SceneFormationProvider {
  execute(
    task: string,
    command: Readonly<Record<string, unknown>>,
  ): Promise<unknown>;
}

const MAXIMUM_SCENE_QUESTIONS = 10;

export class GenerateSceneRecommendations {
  public constructor(private readonly provider: SceneFormationProvider) {}
  public async execute(
    command: Readonly<{
      accountId: string;
      commandId: string;
      sources: readonly Readonly<{
        sourceId: string;
        sourceType: "DIARY" | "QUICK_RECORDING" | "AI_SCENE";
        completedLocalDate: string;
        topicSummaryZh: string;
        confirmedTextZh: string;
        sceneContextZh: string;
      }>[];
    }>,
  ) {
    if (!command.accountId || !command.commandId || command.sources.length > 12)
      throw new Error("Invalid scene recommendation input");
    const orderedSources = [...command.sources].sort((left, right) =>
      right.completedLocalDate.localeCompare(left.completedLocalDate),
    );
    const value = record(
      await this.provider.execute("RECENT_SCENE_RECOMMENDATION_V1", {
        ...command,
        sources: orderedSources,
      }),
    );
    if (
      !Array.isArray(value.recommendations) ||
      value.recommendations.length > 4
    )
      throw new Error("Invalid scene recommendation output");
    const sourceIds = new Set(command.sources.map((source) => source.sourceId));
    const recommendations = value.recommendations.map((item) => {
      const recommendation = record(item);
      if (
        !strings(recommendation, [
          "recommendationId",
          "shortTopicZh",
          "whyRelevantZh",
          "differentiationKey",
        ]) ||
        !Array.isArray(recommendation.sourceRefs) ||
        !recommendation.sourceRefs.every(
          (id) => typeof id === "string" && sourceIds.has(id),
        )
      )
        throw new Error("Invalid scene recommendation output");
      return recommendation as {
        recommendationId: string;
        shortTopicZh: string;
        whyRelevantZh: string;
        sourceRefs: string[];
        differentiationKey: string;
      };
    });
    if (
      new Set(recommendations.map((item) => item.differentiationKey)).size !==
      recommendations.length
    )
      throw new Error("Invalid scene recommendation output");
    const recency = new Map(
      orderedSources.map((source, index) => [source.sourceId, index]),
    );
    const rank = (sourceRefs: readonly string[]) =>
      Math.min(
        ...sourceRefs.map((id) => recency.get(id) ?? Number.MAX_SAFE_INTEGER),
      );
    return {
      recommendations: recommendations
        .map((recommendation, index) => ({ recommendation, index }))
        .sort(
          (left, right) =>
            rank(left.recommendation.sourceRefs) -
              rank(right.recommendation.sourceRefs) || left.index - right.index,
        )
        .map(({ recommendation }) => recommendation),
    };
  }
}

export class GetNextSceneQuestion {
  public constructor(private readonly provider: SceneFormationProvider) {}
  public async execute(
    command: Readonly<{
      accountId: string;
      commandId: string;
      operationId: string;
      start: SceneStartPayload;
      contentType: "DIALOGUE" | "ARTICLE";
      confirmedAnswers: readonly SceneAnswerPayload[];
      remainingQuestions: number;
      remainingContentBudget: number;
      stepIndex: number;
      allowEarlyGeneration?: true | undefined;
      practiceGoalRef?: string | undefined;
    }>,
  ) {
    if (
      !command.accountId ||
      !command.commandId ||
      !command.operationId ||
      command.stepIndex !== command.confirmedAnswers.length ||
      command.stepIndex > MAXIMUM_SCENE_QUESTIONS ||
      command.remainingQuestions !==
        Math.max(0, MAXIMUM_SCENE_QUESTIONS - command.stepIndex) ||
      command.remainingContentBudget < 3
    )
      throw new Error("Invalid next-question input");
    const providerStart =
      command.start.kind === "RECOMMENDATION"
        ? {
            kind: "RECOMMENDATION" as const,
            shortTopicZh: command.start.shortTopicZh,
          }
        : command.start;
    const facts = sceneFactSources(command.start, command.confirmedAnswers);
    const lockedGoal = facts.find((fact) => fact.id === command.practiceGoalRef);
    if (command.practiceGoalRef && !lockedGoal) throw new Error("Unknown scene practice goal reference");
    const value = record(
      await this.provider.execute("SCENE_NEXT_QUESTION_V1", {
        ...command,
        start: providerStart,
        minimumOutputUnits: command.allowEarlyGeneration ? (command.contentType === "ARTICLE" ? 1 : 2) : 3,
        ...(command.allowEarlyGeneration ? { factSources: facts } : {}),
        minimumQuestions: command.allowEarlyGeneration ? 0 : 3,
        preferredMaximumQuestions: 3,
        maximumQuestions: MAXIMUM_SCENE_QUESTIONS,
        preferredConcreteSceneMoments: 1,
        maximumConcreteSceneMoments: 2,
        missingOutsideChosenMomentIsNonBlocking: true,
      }),
    );
    if (value.action === "INSUFFICIENT_INFORMATION") {
      if (command.stepIndex !== MAXIMUM_SCENE_QUESTIONS)
        throw new Error("Invalid next-question output");
      const recoveredFacts = recoverableSceneFacts(command);
      if (recoveredFacts !== null)
        return {
          action: "GENERATE" as const,
          collectedFacts: recoveredFacts,
          missingNonBlockingFacts: [],
        };
      return { action: "INSUFFICIENT_INFORMATION" as const };
    }
    if (value.action === "GENERATE") {
      if (
        (!command.allowEarlyGeneration && command.confirmedAnswers.length < 3) ||
        (command.confirmedAnswers.length === 0 && command.start.kind !== "DIRECT_DESCRIPTION") ||
        !arrayOfStrings(value.collectedFacts) ||
        !arrayOfStrings(value.missingNonBlockingFacts)
      )
        throw new Error("Invalid next-question output");
      const practiceGoalRef = lockedGoal?.id ?? value.practiceGoalRef ??
        (command.allowEarlyGeneration && command.start.kind === "DIRECT_DESCRIPTION" ? "description" : undefined);
      if (practiceGoalRef !== undefined && !facts.some((source) => source.id === practiceGoalRef))
        throw new Error("Unknown scene practice goal reference");
      return {
        action: "GENERATE" as const,
        collectedFacts: value.collectedFacts,
        missingNonBlockingFacts: value.missingNonBlockingFacts,
        ...(typeof practiceGoalRef === "string" ? { practiceGoalRef } : {}),
      };
    }
    if (
      value.action !== "ASK" ||
      command.stepIndex >= MAXIMUM_SCENE_QUESTIONS ||
      !strings(value, ["questionId", "questionZh"]) ||
      !arrayOfStrings(value.options) ||
      command.confirmedAnswers.some(
        (answer) =>
          answer.questionId === value.questionId ||
          (answer.questionZh !== undefined &&
            normalizeQuestion(answer.questionZh) ===
              normalizeQuestion(value.questionZh as string)),
      )
    )
      throw new Error("Invalid next-question output");
    return {
      action: "ASK" as const,
      questionId: value.questionId,
      questionZh: value.questionZh,
      options: value.options,
      ...(lockedGoal ? { practiceGoalRef: lockedGoal.id } :
        typeof value.practiceGoalRef === "string" && facts.some((source) => source.id === value.practiceGoalRef)
          ? { practiceGoalRef: value.practiceGoalRef } : {}),
    };
  }
}

export class GenerateSceneDraft {
  public constructor(
    private readonly provider: SceneFormationProvider,
    private readonly settings?: ContentGenerationSettingsReader,
  ) {}
  public async execute(
    command: Readonly<{
      accountId: string;
      commandId: string;
      task: "SCENE_DIALOGUE_TURN_V1" | "SCENE_ARTICLE_DRAFT_V1";
      session: SceneSessionPayload;
      remainingContentBudget: number;
      previousUnits?: readonly SceneUnitPayload[] | undefined;
      draftVersion?: 2 | undefined;
    }>,
  ) {
    if (
      !command.accountId ||
      !command.commandId ||
      (command.session.answers.length === 0 && command.session.start.kind !== "DIRECT_DESCRIPTION") ||
      command.remainingContentBudget < 3 ||
      command.remainingContentBudget > 10 ||
      (command.task === "SCENE_ARTICLE_DRAFT_V1") !==
        (command.session.contentType === "ARTICLE")
    )
      throw new Error("Invalid scene generation input");
    const providerSession =
      command.session.start.kind === "RECOMMENDATION"
        ? {
            ...command.session,
            start: {
              kind: "RECOMMENDATION" as const,
              shortTopicZh: command.session.start.shortTopicZh,
            },
          }
        : command.session;
    const generationSettings: ContentGenerationSettings | undefined =
      await this.settings?.read(command.accountId);
    // Model summaries and rejected drafts are not additional user facts.
    // Retain each answer's question so a bare agreement keeps its exact scope.
    const confirmedAnswerFacts = command.session.answers.map((answer) =>
      answer.questionZh?.trim()
        ? `${answer.questionZh.trim()}：${answer.answerZh}`
        : answer.answerZh,
    );
    if (confirmedAnswerFacts.length === 0 && command.session.start.kind === "DIRECT_DESCRIPTION") {
      confirmedAnswerFacts.push(command.session.start.description);
    }
    const factSources = sceneFactSources(command.session.start, command.session.answers);
    const goal = factSources.find((source) => source.id === command.session.practiceGoalRef);
    if (command.session.practiceGoalRef && !goal) throw new Error("Unknown scene practice goal reference");
    const minimumOutputUnits = command.draftVersion === 2
      ? (command.session.contentType === "ARTICLE" ? 1 : 2)
      : 3;
    const generateCandidate = async (
      attempt: number,
      fidelityIssues: readonly SceneFidelityIssue[] = [],
      previousUnits = command.previousUnits,
    ) => {
      const value = record(
        await this.provider.execute(command.task, {
          ...command,
          ...(attempt === 0
            ? {}
            : {
                commandId: `${command.commandId}:fidelity-repair-v1`,
                billingOperationId: command.commandId,
                callType: "AUTOMATIC_RETRY",
                previousUnits,
                fidelityIssues,
              }),
          session: { ...providerSession, collectedFacts: confirmedAnswerFacts },
          ...(generationSettings === undefined
            ? {}
            : { settings: generationSettings }),
          minimumOutputUnits,
          generationMode: attempt > 0 ? "FACT_REPAIR" : command.previousUnits?.length ? "USER_REGENERATE" : "INITIAL",
          ...(command.draftVersion === 2 ? { factSources, practiceGoal: goal ?? null } : {}),
        }),
      );
      const patchOnly = attempt > 0 && command.draftVersion === 2;
      const outputUnits = Array.isArray(value.units) ? value.units.map(parseUnit) : [];
      if (patchOnly && (!previousUnits || !outputUnits.length ||
        new Set(outputUnits.map((unit) => unit.unitId)).size !== outputUnits.length ||
        outputUnits.some((unit) => !fidelityIssues.some((issue) => issue.unitId === unit.unitId)))) {
        throw new Error("Scene repair changed unaffected units");
      }
      const originalUnits = patchOnly && previousUnits
        ? previousUnits.map((unit) => outputUnits.find((replacement) => replacement.unitId === unit.unitId) ?? unit)
        : outputUnits;
      if (
        !strings(value, ["draftId", "contentType"]) ||
        value.contentType !== command.session.contentType ||
        originalUnits.length < minimumOutputUnits ||
        originalUnits.length > command.remainingContentBudget
      )
        throw new Error("Invalid scene generation output");
      if (command.draftVersion === 2 && originalUnits.some((unit) =>
        unit.factReferences.some((ref) => !factSources.some((source) => source.id === ref))))
        throw new Error("Unknown scene draft fact reference");
      if (new Set(originalUnits.map((unit) => unit.unitId)).size !== originalUnits.length)
        throw new Error("Duplicate scene unit identity");
      const maximumUnitCodePoints =
        command.session.contentType === "DIALOGUE" ? 48 : 80;
      // Preserve every character and its source references. Never split a
      // dialogue turn or cut a sentence in the middle to satisfy the limit.
      const units = originalUnits.flatMap((unit, index) => {
        if (command.session.contentType !== "ARTICLE" ||
            Array.from(unit.textZh.trim()).length <= maximumUnitCodePoints) return [unit];
        const sentences = unit.textZh.split(/(?<=[。！？；!?;])(?![”’」』）】])/u);
        const segments: string[] = [];
        for (const sentence of sentences) {
          const previous = segments.at(-1);
          if (previous !== undefined && Array.from((previous + sentence).trim()).length <= maximumUnitCodePoints)
            segments[segments.length - 1] = previous + sentence;
          else if (sentence.trim()) segments.push(sentence);
        }
        if (segments.length < 2) return [unit];
        return segments.map((textZh, part) => ({
          ...unit,
          textZh,
          unitId: `article-part:${index}:${part}:${createHash("sha256").update(unit.unitId).digest("hex").slice(0, 16)}`,
        }));
      });
      if (
        units.length > command.remainingContentBudget || units.some(
          (unit) =>
            Array.from(unit.textZh.trim()).length > maximumUnitCodePoints,
        )
      )
        throw new Error("Scene unit is too long for focused learning");
      const normalizedContent = (items: readonly SceneUnitPayload[]) =>
        items
          .map((unit) =>
            unit.textZh.normalize("NFKC").replace(/[\s\p{P}\p{S}]/gu, ""),
          )
          .join("");
      if (
        attempt === 0 && previousUnits?.length &&
        normalizedContent(units) === normalizedContent(previousUnits)
      )
        throw new Error("Scene regeneration repeated previous content");
      if (
        value.contentType === "ARTICLE"
          ? units.some((unit) => unit.speaker !== null)
          : units.some((unit) => unit.speaker === null) ||
            !units.some((unit) => unit.speaker === "USER") ||
            !units.some((unit) => unit.speaker === "OTHER")
      )
        throw new Error("Invalid scene generation output");
      if (new Set(units.map((unit) => unit.unitId)).size !== units.length)
        throw new Error("Duplicate scene unit identity");
      if (attempt > 0 && command.draftVersion === 2 && previousUnits) {
        const preserved = previousUnits.filter((unit) => !fidelityIssues.some((issue) => issue.unitId === unit.unitId));
        const retained = units.filter((unit) => preserved.some((old) => old.unitId === unit.unitId));
        if (preserved.length !== retained.length || preserved.some((old, index) => {
          const unit = retained[index]!;
          return old.unitId !== unit.unitId || old.speaker !== unit.speaker ||
            old.textZh !== unit.textZh || JSON.stringify(old.factReferences) !== JSON.stringify(unit.factReferences);
        }))
          throw new Error("Scene repair changed unaffected units");
      }
      return {
        contentType: value.contentType as "DIALOGUE" | "ARTICLE",
        units,
      };
    };
    let candidate = await generateCandidate(0);
    for (let attempt = 0; attempt < 2; attempt++) {
      const review = parseSceneFidelityReview(
        await this.provider.execute("SCENE_DRAFT_FIDELITY_V1", {
          accountId: command.accountId,
          commandId: `${command.commandId}:fidelity-v1:${attempt}`,
          billingOperationId: command.commandId,
          session: { ...providerSession, collectedFacts: confirmedAnswerFacts },
          contentType: candidate.contentType,
          units: candidate.units,
          ...(command.draftVersion === 2 ? { factSources, practiceGoal: goal ?? null } : {}),
        }),
        candidate.units,
      );
      if (review.decision === "PASS") break;
      if (attempt === 1)
        throw new Error("Scene draft failed factual fidelity review");
      // One bounded corrective attempt; never immediately hammer a supplier.
      await new Promise((resolve) => setTimeout(resolve, 100 + randomInt(151)));
      candidate = await generateCandidate(1, review.issues, candidate.units);
    }
    return {
      draftId: `scene-${createHash("sha256")
        .update(JSON.stringify([command.accountId, command.commandId]))
        .digest("hex")
        .slice(0, 32)}`,
      ...candidate,
    };
  }
}

type SceneFidelityIssue = Readonly<{
  unitId: string;
  reason:
    | "UNSUPPORTED_FACT"
    | "ROLE_CHANGED"
    | "TEMPORAL_CHANGED"
    | "INTENT_CHANGED"
    | "PRACTICE_GOAL_MISSING"
    | "CONTRADICTION";
  explanationZh: string;
}>;
const parseSceneFidelityReview = (
  raw: unknown,
  units: readonly SceneUnitPayload[],
) => {
  const value = record(raw);
  if (
    !Array.isArray(value.issues) ||
    !["PASS", "FAIL"].includes(String(value.decision))
  )
    throw new Error("Invalid scene fidelity review");
  const issues = value.issues.map((rawIssue): SceneFidelityIssue => {
    const issue = record(rawIssue);
    if (
      !strings(issue, ["unitId", "reason", "explanationZh"]) ||
      !units.some((unit) => unit.unitId === issue.unitId) ||
      ![
        "UNSUPPORTED_FACT",
        "ROLE_CHANGED",
        "TEMPORAL_CHANGED",
        "INTENT_CHANGED",
        "PRACTICE_GOAL_MISSING",
        "CONTRADICTION",
      ].includes(String(issue.reason))
    )
      throw new Error("Invalid scene fidelity issue");
    return issue as SceneFidelityIssue;
  });
  if ((value.decision === "PASS") !== (issues.length === 0))
    throw new Error("Inconsistent scene fidelity decision");
  return { decision: value.decision as "PASS" | "FAIL", issues };
};

export class ReviewSceneEdit {
  public constructor(private readonly provider: SceneFormationProvider) {}
  public async execute(
    command: Readonly<{
      accountId: string;
      commandId: string;
      contentType: "DIALOGUE" | "ARTICLE";
      changedUnitIds: readonly string[];
      originalUnits: readonly SceneUnitPayload[];
      editedUnits: readonly SceneUnitPayload[];
    }>,
  ) {
    if (
      !command.accountId ||
      !command.commandId ||
      command.changedUnitIds.length < 1 ||
      command.originalUnits.length !== command.editedUnits.length
    )
      throw new Error("Invalid scene edit review input");
    const value = record(
      await this.provider.execute("SCENE_EDIT_REVIEW_V1", command),
    );
    if (value.decision === "PASS") return { decision: "PASS" as const };
    const valid = new Set([
      "REQUIRED_INFORMATION_MISSING",
      "CONTRADICTION",
      "UNINTELLIGIBLE",
      "SEVERELY_UNNATURAL",
    ]);
    if (
      value.decision !== "NEEDS_USER_EDIT" ||
      !Array.isArray(value.issues) ||
      value.issues.length < 1
    )
      throw new Error("Invalid scene edit review output");
    const issues = value.issues.map((item) => {
      const issue = record(item);
      if (
        !strings(issue, ["unitId", "reason", "explanationZh"]) ||
        typeof issue.startOffset !== "number" ||
        typeof issue.endOffset !== "number" ||
        issue.startOffset < 0 ||
        issue.endOffset <= issue.startOffset ||
        !valid.has(issue.reason as string) ||
        !command.changedUnitIds.includes(issue.unitId as string)
      )
        throw new Error("Invalid scene edit review output");
      return issue as {
        unitId: string;
        startOffset: number;
        endOffset: number;
        reason:
          | "REQUIRED_INFORMATION_MISSING"
          | "CONTRADICTION"
          | "UNINTELLIGIBLE"
          | "SEVERELY_UNNATURAL";
        explanationZh: string;
      };
    });
    return { decision: "NEEDS_USER_EDIT" as const, issues };
  }
}

const parseUnit = (value: unknown): SceneUnitPayload => {
  const unit = record(value);
  if (
    !strings(unit, ["unitId", "textZh"]) ||
    !(
      unit.speaker === null ||
      unit.speaker === "USER" ||
      unit.speaker === "OTHER"
    ) ||
    !arrayOfStrings(unit.factReferences)
  )
    throw new Error("Invalid scene generation output");
  return unit as SceneUnitPayload;
};
const normalizeQuestion = (value: string) =>
  value
    .normalize("NFKC")
    .replace(/[\s\p{P}\p{S}]/gu, "")
    .toLocaleLowerCase();
const recoverableSceneFacts = (
  command: Readonly<{
    start: SceneStartPayload;
    confirmedAnswers: readonly SceneAnswerPayload[];
  }>,
): string[] | null => {
  const vagueAnswers = new Set([
    "不知道",
    "不清楚",
    "不确定",
    "随便",
    "没有",
    "没什么",
    "无",
    "哪知道",
  ]);
  const distinctAnswers = new Map<string, string>();
  for (const answer of command.confirmedAnswers) {
    const fact = answer.answerZh.trim();
    const normalized = normalizeQuestion(fact);
    if (
      normalized.length < 2 ||
      vagueAnswers.has(normalized) ||
      distinctAnswers.has(normalized)
    )
      continue;
    distinctAnswers.set(normalized, fact);
  }
  const answerFacts = [...distinctAnswers.values()];
  const totalAnswerCodePoints = answerFacts.reduce(
    (total, fact) => total + Array.from(fact).length,
    0,
  );
  if (
    answerFacts.length < 3 &&
    !(answerFacts.length >= 2 && totalAnswerCodePoints >= 24)
  )
    return null;
  const startFact =
    command.start.kind === "DIRECT_DESCRIPTION"
      ? command.start.description.trim()
      : command.start.kind === "TEMPLATE"
        ? `${command.start.titleZh}：${command.start.scopeZh}`.trim()
        : command.start.shortTopicZh.trim();
  return [...new Set([startFact, ...answerFacts].filter(Boolean))];
};
const record = (value: unknown): Record<string, unknown> => {
  if (typeof value !== "object" || value === null)
    throw new Error("Invalid scene provider output");
  return value as Record<string, unknown>;
};
const strings = (value: Record<string, unknown>, keys: string[]) =>
  keys.every(
    (key) => typeof value[key] === "string" && (value[key] as string).trim(),
  );
const arrayOfStrings = (value: unknown): value is string[] =>
  Array.isArray(value) && value.every((item) => typeof item === "string");

const sceneFactSources = (start: SceneStartPayload, answers: readonly SceneAnswerPayload[]) => [
  ...(start.kind === "DIRECT_DESCRIPTION" ? [{ id: "description", textZh: start.description }] : []),
  ...answers.map((answer) => ({
    id: `answer:${answer.questionId}`,
    textZh: answer.questionZh ? `${answer.questionZh}：${answer.answerZh}` : answer.answerZh,
  })),
];
