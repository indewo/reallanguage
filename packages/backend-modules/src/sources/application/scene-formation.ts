import { createHash } from "node:crypto";
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
}>;
export interface SceneFormationProvider {
  execute(
    task: string,
    command: Readonly<Record<string, unknown>>,
  ): Promise<unknown>;
}

const MINIMUM_SCENE_QUESTIONS = 3;
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
    const value = record(
      await this.provider.execute("SCENE_NEXT_QUESTION_V1", {
        ...command,
        start: providerStart,
        minimumOutputUnits: 3,
        minimumQuestions: MINIMUM_SCENE_QUESTIONS,
        maximumQuestions: MAXIMUM_SCENE_QUESTIONS,
        reassessAfterEveryAnswer: command.stepIndex >= MINIMUM_SCENE_QUESTIONS,
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
        command.confirmedAnswers.length < MINIMUM_SCENE_QUESTIONS ||
        !arrayOfStrings(value.collectedFacts) ||
        !arrayOfStrings(value.missingNonBlockingFacts)
      )
        throw new Error("Invalid next-question output");
      return {
        action: "GENERATE" as const,
        collectedFacts: value.collectedFacts,
        missingNonBlockingFacts: value.missingNonBlockingFacts,
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
    }>,
  ) {
    if (
      !command.accountId ||
      !command.commandId ||
      command.session.answers.length < 1 ||
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
    const value = record(
      await this.provider.execute(command.task, {
        ...command,
        session: providerSession,
        ...(generationSettings === undefined
          ? {}
          : { settings: generationSettings }),
        minimumOutputUnits: 3,
      }),
    );
    if (
      !strings(value, ["draftId", "contentType"]) ||
      value.contentType !== command.session.contentType ||
      !Array.isArray(value.units) ||
      value.units.length < 3 ||
      value.units.length > command.remainingContentBudget
    )
      throw new Error("Invalid scene generation output");
    const units = value.units.map(parseUnit);
    const maximumUnitCodePoints =
      command.session.contentType === "DIALOGUE" ? 48 : 80;
    if (
      units.some(
        (unit) => Array.from(unit.textZh.trim()).length > maximumUnitCodePoints,
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
      command.previousUnits?.length &&
      normalizedContent(units) === normalizedContent(command.previousUnits)
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
    return {
      draftId: `scene-${createHash("sha256")
        .update(JSON.stringify([command.accountId, command.commandId]))
        .digest("hex")
        .slice(0, 32)}`,
      contentType: value.contentType as "DIALOGUE" | "ARTICLE",
      units,
    };
  }
}

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
