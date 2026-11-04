import type {
  ReadingFeedbackCommand,
  ReadingFeedbackProvider,
} from "../application/generate-reading-feedback.js";

export class HttpReadingFeedbackProvider implements ReadingFeedbackProvider {
  public constructor(
    private readonly config: Readonly<{ endpoint: string; apiKey: string }>,
    private readonly fetchImplementation: typeof fetch = fetch,
  ) {}

  public async generate(command: ReadingFeedbackCommand) {
    const response = await this.fetchImplementation(this.config.endpoint, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${this.config.apiKey}`,
        "Content-Type": "application/json",
        "Idempotency-Key": command.commandId,
        "X-RealLanguage-Account-Id": command.accountId,
      },
      body: JSON.stringify({
        task: "READING_FEEDBACK_V1",
        input: {
          chapterId: command.chapterId,
          sentenceId: command.sentenceId,
          targetTextEn: command.targetTextEn,
          completionSignal: command.completionSignal,
          audio: {
            mimeType: command.audioMimeType,
            base64: command.audioBase64,
          },
        },
      }),
    });
    if (!response.ok)
      throw new Error("Reading feedback provider request failed");
    return response.json();
  }
}
