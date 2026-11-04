export type PatternPracticeGenerationCommand = Readonly<{
  accountId: string;
  chapterId: string;
  sourceSentenceId: string;
  sourceTextEn: string;
  sourceTextZh: string;
  patternId: string;
  structure: string;
  explanationZh: string;
  commandId: string;
}>;

export interface PatternPracticeGenerationProvider {
  generate(command: PatternPracticeGenerationCommand): Promise<unknown>;
}

export class GeneratePatternPractice {
  public constructor(
    private readonly provider: PatternPracticeGenerationProvider,
  ) {}

  public async execute(command: PatternPracticeGenerationCommand) {
    if (
      !command.sourceTextEn.trim() ||
      !command.sourceTextZh.trim() ||
      !command.structure.trim() ||
      !command.explanationZh.trim()
    )
      throw new Error("Invalid pattern practice input");
    const output = await this.provider.generate(command);
    if (
      !record(output) ||
      !["READY", "NOT_NATURALLY_VERIFIABLE"].includes(String(output.status))
    ) {
      throw new Error("Invalid pattern practice output");
    }
    if (output.status === "NOT_NATURALLY_VERIFIABLE")
      return { status: output.status };
    if (
      output.patternId !== command.patternId ||
      !Array.isArray(output.examples) ||
      output.examples.length < 1 ||
      output.examples.length > 5
    ) {
      throw new Error("Invalid pattern practice output");
    }
    const ids = new Set<string>();
    const examples = output.examples.map((value) => {
      if (!record(value)) throw new Error("Invalid pattern practice output");
      const fields = [
        "exampleId",
        "sceneZh",
        "aiPromptEn",
        "targetResponseEn",
        "targetResponseZh",
      ] as const;
      if (
        fields.some(
          (field) =>
            typeof value[field] !== "string" || !String(value[field]).trim(),
        ) ||
        ids.has(String(value.exampleId))
      ) {
        throw new Error("Invalid pattern practice output");
      }
      ids.add(String(value.exampleId));
      return Object.fromEntries(fields.map((field) => [field, value[field]]));
    });
    return { status: "READY" as const, patternId: command.patternId, examples };
  }
}

const record = (value: unknown): value is Record<string, unknown> =>
  typeof value === "object" && value !== null;
