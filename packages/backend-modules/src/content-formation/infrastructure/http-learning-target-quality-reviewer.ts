import type {
  LearningTargetCandidate,
  LearningTargetProviderCommand,
  LearningTargetQualityReviewer,
  TargetQualityIssue,
} from "../application/extract-learning-targets.js";

const reasons = new Set([
  "NOT_LEXICALIZED",
  "CONTEXT_MEANING",
  "PATTERN_FUNCTION",
  "EXAMPLE_GRAMMAR",
  "EXAMPLE_MEANING",
  "EXAMPLE_REUSE",
]);
/** A separate metered review; never silently edits a delivered target or its meaning. */
export class HttpLearningTargetQualityReviewer implements LearningTargetQualityReviewer {
  constructor(
    private readonly config: Readonly<{ endpoint: string; apiKey: string }>,
    private readonly fetchImplementation: typeof fetch = fetch,
  ) {}
  async review(
    command: LearningTargetProviderCommand,
    targets: readonly LearningTargetCandidate[],
  ): Promise<readonly TargetQualityIssue[]> {
    const response = await this.fetchImplementation(this.config.endpoint, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${this.config.apiKey}`,
        "Content-Type": "application/json",
        "Idempotency-Key": `${command.commandId}:quality-v1`,
        "X-RealLanguage-Account-Id": command.accountId,
        "X-RealLanguage-Operation-Id":
          command.billingOperationId ?? command.commandId,
      },
      body: JSON.stringify({
        task: "LEARNING_TARGET_QUALITY_V1",
        input: { chapters: command.chapters, targets },
      }),
    });
    if (!response.ok) throw new Error("Learning target review request failed");
    const value = (await response.json()) as {
      valid?: unknown;
      issues?: unknown;
    };
    if (
      !value ||
      typeof value.valid !== "boolean" ||
      !Array.isArray(value.issues)
    )
      throw new Error("Invalid learning target quality review");
    const ids = new Set(targets.map((target) => target.targetId));
    const issues: TargetQualityIssue[] = value.issues.map((item: unknown) => {
      if (!item || typeof item !== "object")
        throw new Error("Invalid learning target quality issue");
      const issue = item as Record<string, unknown>;
      if (
        typeof issue.targetId !== "string" ||
        !ids.has(issue.targetId) ||
        typeof issue.reason !== "string" ||
        !reasons.has(issue.reason) ||
        typeof issue.explanationZh !== "string" ||
        !issue.explanationZh.trim()
      )
        throw new Error("Invalid learning target quality issue");
      return {
        targetId: issue.targetId,
        reason: issue.reason,
        explanationZh: issue.explanationZh,
      };
    });
    if (value.valid !== (issues.length === 0))
      throw new Error("Contradictory learning target quality review");
    return issues;
  }
}
