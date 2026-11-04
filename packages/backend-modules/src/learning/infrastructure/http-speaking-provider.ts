import type {
  FullSentenceSpeakingEvaluationCommand,
  FullSentenceSpeakingEvaluationProvider,
} from "../application/evaluate-full-sentence-speaking.js";
import type {
  SpeakingTranscriptionCommand,
  SpeakingTranscriptionProvider,
} from "../application/generate-speaking-transcription.js";

export class HttpSpeakingProvider
  implements
    SpeakingTranscriptionProvider,
    FullSentenceSpeakingEvaluationProvider
{
  public constructor(
    private readonly config: Readonly<{ endpoint: string; apiKey: string }>,
    private readonly fetchImplementation: typeof fetch = fetch,
  ) {}

  public generate(
    command:
      SpeakingTranscriptionCommand | FullSentenceSpeakingEvaluationCommand,
  ): Promise<unknown> {
    const evaluation = "confirmedTranscript" in command;
    return this.request(
      evaluation
        ? "FULL_SENTENCE_SPEAKING_EVALUATION_V1"
        : "SPEAKING_TRANSCRIPTION_V1",
      command,
    );
  }

  private async request(task: string, command: object): Promise<unknown> {
    const response = await this.fetchImplementation(this.config.endpoint, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${this.config.apiKey}`,
        "Content-Type": "application/json",
        "Idempotency-Key": (command as { commandId: string }).commandId,
        "X-RealLanguage-Account-Id": (command as { accountId: string })
          .accountId,
      },
      body: JSON.stringify({ task, input: command }),
    });
    if (!response.ok) throw new Error("Speaking provider request failed");
    return response.json();
  }
}
