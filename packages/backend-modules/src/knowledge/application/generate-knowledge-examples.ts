import type {
  CurrentSettingsReader,
  InitialSettingsSnapshot,
} from "../../settings/application/complete-initial-settings.js";

export type KnowledgeExample = Readonly<{
  textEn: string;
  textZh: string;
  pronunciationText: string;
}>;
export type KnowledgeExampleGenerationCommand = Readonly<{
  accountId: string;
  commandId: string;
  itemId: string;
  targetType: "VOCABULARY" | "PATTERN";
  display: string;
  meaningZh: string;
  sourceScene: readonly Readonly<{ textEn: string; textZh: string }>[];
}>;
export type KnowledgeExampleGenerationResult = Readonly<{
  itemId: string;
  phonetic: string | null;
  pronunciationText: string;
  representativeScene: string;
  naturalRevalidation: boolean | null;
  examples: readonly KnowledgeExample[];
}>;
export interface KnowledgeExampleProvider {
  generate(
    command: KnowledgeExampleGenerationCommand & {
      settings: InitialSettingsSnapshot;
    },
  ): Promise<KnowledgeExampleGenerationResult>;
}

export class GenerateKnowledgeExamples {
  public constructor(
    private readonly settings: CurrentSettingsReader,
    private readonly provider: KnowledgeExampleProvider,
  ) {}

  public async execute(
    command: KnowledgeExampleGenerationCommand,
  ): Promise<KnowledgeExampleGenerationResult> {
    if (
      !command.itemId.trim() ||
      !command.commandId.trim() ||
      !command.display.trim() ||
      !command.meaningZh.trim() ||
      command.sourceScene.length < 1 ||
      command.sourceScene.length > 4 ||
      command.sourceScene.some(
        (sentence) => !sentence.textEn.trim() || !sentence.textZh.trim(),
      )
    )
      throw new Error("Invalid knowledge example input");
    const result = await this.provider.generate({
      ...command,
      settings: await this.settings.read(command.accountId),
    });
    const exampleKeys = result.examples.map(
      (example) =>
        `${example.textEn.trim().toLowerCase()}\u001f${example.textZh.trim()}`,
    );
    if (
      result.itemId !== command.itemId ||
      !result.pronunciationText.trim() ||
      !result.representativeScene.trim() ||
      result.examples.length !== 3 ||
      (command.targetType === "PATTERN" &&
        result.naturalRevalidation === null) ||
      new Set(exampleKeys).size !== 3 ||
      result.examples.some(
        (example) =>
          !example.textEn.trim() ||
          !example.textZh.trim() ||
          !example.pronunciationText.trim(),
      )
    )
      throw new Error("Invalid knowledge example output");
    return result;
  }
}
