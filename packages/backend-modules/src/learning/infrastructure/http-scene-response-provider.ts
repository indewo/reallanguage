import type {
  SceneResponseEvaluationCommand,
  SceneResponseEvaluationProvider,
} from "../application/evaluate-scene-response.js";
import type {
  SceneResponseTrainingFeedbackCommand,
  SceneResponseTrainingFeedbackProvider,
} from "../application/generate-scene-response-feedback.js";
import type {
  SceneResponseGenerationCommand,
  SceneResponseGenerationProvider,
} from "../application/generate-scene-response.js";

type SceneCommand =
  | SceneResponseGenerationCommand
  | SceneResponseTrainingFeedbackCommand
  | SceneResponseEvaluationCommand;

export class HttpSceneResponseProvider
  implements
    SceneResponseGenerationProvider,
    SceneResponseTrainingFeedbackProvider,
    SceneResponseEvaluationProvider
{
  public constructor(
    private readonly config: Readonly<{ endpoint: string; apiKey: string }>,
    private readonly fetchImplementation: typeof fetch = fetch,
  ) {}

  public async generate(command: SceneCommand): Promise<unknown> {
    const task =
      "roundIndex" in command
        ? command.roundIndex === 2
          ? "SCENE_RESPONSE_EVALUATION_V1"
          : "SCENE_RESPONSE_TRAINING_FEEDBACK_V1"
        : "SCENE_RESPONSE_GENERATION_V1";
    const response = await this.fetchImplementation(this.config.endpoint, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${this.config.apiKey}`,
        "Content-Type": "application/json",
        "Idempotency-Key": command.commandId,
        "X-RealLanguage-Account-Id": command.accountId,
      },
      body: JSON.stringify({ task, input: command }),
    });
    if (!response.ok) throw new Error("Scene response provider request failed");
    return response.json();
  }
}
