import type {
  LearningExplanationProvider,
  LearningExplanationProviderCommand,
} from "../application/generate-learning-explanation.js";
export class HttpLearningExplanationProvider implements LearningExplanationProvider {
  public constructor(
    private readonly config: Readonly<{ endpoint: string; apiKey: string }>,
    private readonly fetchImplementation: typeof fetch = fetch,
  ) {}
  public async generate(command: LearningExplanationProviderCommand) {
    const response = await this.fetchImplementation(this.config.endpoint, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${this.config.apiKey}`,
        "Content-Type": "application/json",
        "Idempotency-Key": command.commandId,
        "X-RealLanguage-Account-Id": command.accountId,
      },
      body: JSON.stringify({
        task: command.requestedExpression
          ? "WORD_EXPLANATION_V1"
          : "SENTENCE_EXPLANATION_V1",
        input: {
          chapter: command.chapter,
          focusSentenceId: command.focusSentenceId,
          round: command.round,
          existingBaseExplanationZh: command.existingBaseExplanationZh ?? null,
          requestedExpression: command.requestedExpression ?? null,
          settings: command.settings,
          ...(command.lockedTargets !== undefined
            ? { lockedTargets: command.lockedTargets }
            : {}),
        },
      }),
    });
    if (!response.ok)
      throw new Error("Learning explanation provider request failed");
    return response.json();
  }
}
