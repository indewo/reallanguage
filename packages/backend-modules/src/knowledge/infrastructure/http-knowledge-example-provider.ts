import type {
  KnowledgeExampleGenerationResult,
  KnowledgeExampleProvider,
} from "../application/generate-knowledge-examples.js";

export class HttpKnowledgeExampleProvider implements KnowledgeExampleProvider {
  public constructor(
    private readonly config: Readonly<{ endpoint: string; apiKey: string }>,
    private readonly fetchImplementation: typeof fetch = fetch,
  ) {}

  public async generate(
    command: Parameters<KnowledgeExampleProvider["generate"]>[0],
  ) {
    const response = await this.fetchImplementation(this.config.endpoint, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${this.config.apiKey}`,
        "Content-Type": "application/json",
        "Idempotency-Key": command.commandId,
        "X-RealLanguage-Account-Id": command.accountId,
      },
      body: JSON.stringify({
        task: "KNOWLEDGE_EXAMPLE_GENERATION_V1",
        input: {
          itemId: command.itemId,
          targetType: command.targetType,
          display: command.display,
          meaningZh: command.meaningZh,
          sourceScene: command.sourceScene,
          settings: command.settings,
        },
      }),
    });
    if (!response.ok)
      throw new Error("Knowledge example provider request failed");
    return parse(await response.json());
  }
}

const parse = (value: unknown): KnowledgeExampleGenerationResult => {
  if (
    !record(value) ||
    typeof value.itemId !== "string" ||
    !(value.phonetic === null || typeof value.phonetic === "string") ||
    typeof value.pronunciationText !== "string" ||
    typeof value.representativeScene !== "string" ||
    !(
      value.naturalRevalidation === null ||
      typeof value.naturalRevalidation === "boolean"
    ) ||
    !Array.isArray(value.examples)
  )
    throw new Error("Invalid knowledge example provider response");
  return {
    itemId: value.itemId,
    phonetic: value.phonetic,
    pronunciationText: value.pronunciationText,
    representativeScene: value.representativeScene,
    naturalRevalidation: value.naturalRevalidation,
    examples: value.examples.map((example) => {
      if (
        !record(example) ||
        typeof example.textEn !== "string" ||
        typeof example.textZh !== "string" ||
        typeof example.pronunciationText !== "string"
      )
        throw new Error("Invalid knowledge example provider response");
      return {
        textEn: example.textEn,
        textZh: example.textZh,
        pronunciationText: example.pronunciationText,
      };
    }),
  };
};
const record = (value: unknown): value is Record<string, unknown> =>
  typeof value === "object" && value !== null;
