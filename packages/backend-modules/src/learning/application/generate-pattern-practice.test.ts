import { describe, expect, it } from "vitest";
import { GeneratePatternPractice } from "./generate-pattern-practice.js";

describe("GeneratePatternPractice", () => {
  it("accepts one to five natural bilingual scenes for the exact pattern", async () => {
    const app = new GeneratePatternPractice({ generate: async () => ready });
    await expect(app.execute(command)).resolves.toEqual(ready);
  });

  it("preserves the explicit not naturally verifiable result", async () => {
    const app = new GeneratePatternPractice({
      generate: async () => ({ status: "NOT_NATURALLY_VERIFIABLE" }),
    });
    await expect(app.execute(command)).resolves.toEqual({
      status: "NOT_NATURALLY_VERIFIABLE",
    });
  });

  it("rejects duplicate examples and a changed pattern identity", async () => {
    const app = new GeneratePatternPractice({
      generate: async () => ({ ...ready, patternId: "other" }),
    });
    await expect(app.execute(command)).rejects.toThrow(
      "Invalid pattern practice output",
    );
  });
});

const ready = {
  status: "READY" as const,
  patternId: "p1",
  examples: [
    {
      exampleId: "e1",
      sceneZh: "谈过去的上学方式",
      aiPromptEn: "How did you get to school?",
      targetResponseEn: "I used to walk to school.",
      targetResponseZh: "我过去常常走路上学。",
    },
  ],
};
const command = {
  accountId: "a1",
  chapterId: "c1",
  sourceSentenceId: "s1",
  sourceTextEn: "I used to write by hand.",
  sourceTextZh: "我过去常常手写。",
  patternId: "p1",
  structure: "used to + verb",
  explanationZh: "表示过去的习惯。",
  commandId: "cmd",
};
