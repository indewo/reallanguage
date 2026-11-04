import type {
  PatternPracticeGenerationCommand,
  PatternPracticeGenerationProvider,
} from "../application/generate-pattern-practice.js";
import type {
  PatternPracticeFeedbackCommand,
  PatternPracticeFeedbackProvider,
} from "../application/generate-pattern-practice-feedback.js";
import type {
  PatternIndependentEvaluationCommand,
  PatternIndependentEvaluationProvider,
} from "../application/evaluate-pattern-independent.js";

export class HttpPatternPracticeProvider
  implements
    PatternPracticeGenerationProvider,
    PatternPracticeFeedbackProvider,
    PatternIndependentEvaluationProvider
{
  public constructor(
    private readonly config: Readonly<{ endpoint: string; apiKey: string }>,
    private readonly fetchImplementation: typeof fetch = fetch,
  ) {}
  public async generate(
    command:
      | PatternPracticeGenerationCommand
      | PatternPracticeFeedbackCommand
      | PatternIndependentEvaluationCommand,
  ): Promise<unknown> {
    const response = await this.fetchImplementation(this.config.endpoint, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${this.config.apiKey}`,
        "Content-Type": "application/json",
        "Idempotency-Key": command.commandId,
        "X-RealLanguage-Account-Id": command.accountId,
      },
      body: JSON.stringify({
        task:
          "targetHidden" in command
            ? "PATTERN_INDEPENDENT_EVALUATION_V1"
            : "mode" in command
              ? "PATTERN_PRACTICE_FEEDBACK_V1"
              : "PATTERN_PRACTICE_GENERATION_V1",
        input: command,
      }),
    });
    if (!response.ok)
      throw new Error("Pattern practice provider request failed");
    return response.json();
  }
}
