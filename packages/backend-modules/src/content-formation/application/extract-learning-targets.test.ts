import { describe, expect, it } from "vitest";
import { ExtractLearningTargets } from "./extract-learning-targets.js";
const settings = {
  formalityLevel: 3,
  slangEnabled: false,
  basicVocabulary: "CET_4",
  purposeVocabularies: ["BUSINESS_ENGLISH"],
  styleReference: "NATURAL_CONCISE",
};
const command = {
  accountId: "a",
  commandId: "cmd",
  chapters: [
    {
      chapterId: "c1",
      sentences: [
        {
          unitId: "u1",
          textZh: "我很高兴。",
          textEn: "I was happy.",
          isFocus: true,
        },
        {
          unitId: "u2",
          textZh: "天气很好。",
          textEn: "The weather was good.",
          isFocus: false,
        },
      ],
    },
  ],
};
const target = {
  targetId: "t1",
  unitId: "u1",
  display: "happy",
  explanationZh: "高兴的",
  type: "WORD" as const,
  structureParts: [],
  relatedVocabulary: [],
  selectedByDefault: true as const,
};
describe("ExtractLearningTargets", () => {
  it.each([[" "], ["be", "\n"], ["\u00a0\u3000"], [""]])(
    "rejects blank structure even behind a typed provider %j",
    async (...structureParts) => {
      let calls = 0;
      const application = new ExtractLearningTargets(
        { read: async () => settings },
        {
          extract: async () => {
            calls++;
            return {
              targets: [{ ...target, type: "PATTERN", structureParts }],
              queryableExpressions: [],
            };
          },
        },
      );
      await expect(application.execute(command)).rejects.toThrow(
        "Invalid learning target output",
      );
      expect(calls).toBe(1);
    },
  );
  it("keeps real structure parts unchanged rather than trimming or inventing more", async () => {
    const candidate = {
      ...target,
      type: "PATTERN" as const,
      structureParts: ["  主语 + was + 表语  "],
    };
    const application = new ExtractLearningTargets(
      { read: async () => settings },
      {
        extract: async () => ({
          targets: [candidate],
          queryableExpressions: [],
        }),
      },
    );
    expect((await application.execute(command)).targets).toEqual([candidate]);
  });
  it.each([
    ["I was happy.", "was"],
    ["I can't leave.", "can't"],
    ["😀 I met a part-time colleague.", "part-time"],
    ["😀 I visited a café at 2pm.", "café"],
    ["😀 I visited a café at 2pm.", "2pm"],
    ["It is 3D-printed.", "3D-printed"],
  ])(
    "keeps exact UTF-16 word references valid in %s",
    async (textEn, display) => {
      const requestedExpression = {
        unitId: "u1",
        expressionId: `word:${textEn.indexOf(display)}`,
        display,
      };
      const app = new ExtractLearningTargets(
        { read: async () => settings },
        {
          extract: async () => ({
            targets: [{ ...target, display }],
            queryableExpressions: [requestedExpression],
          }),
        },
      );
      const result = await app.execute({
        ...command,
        chapters: [
          {
            chapterId: "c1",
            sentences: [{ ...command.chapters[0]!.sentences[0]!, textEn }],
          },
        ],
        requestedExpression,
      });
      expect(result.targets[0]?.display).toBe(display);
      expect(result.queryableExpressions).toEqual([requestedExpression]);
    },
  );

  it("rejects mismatched or partial word offsets before calling AI", async () => {
    let calls = 0;
    const app = new ExtractLearningTargets(
      { read: async () => settings },
      {
        extract: async () => {
          calls++;
          return { targets: [target], queryableExpressions: [] };
        },
      },
    );
    for (const [expressionId, display] of [
      ["word:2", "happy"],
      ["word:3", "as"],
      ["word:999", "happy"],
    ]) {
      await expect(
        app.execute({
          ...command,
          requestedExpression: {
            unitId: "u1",
            expressionId: expressionId!,
            display: display!,
          },
        }),
      ).rejects.toThrow("Invalid requested expression");
    }
    expect(calls).toBe(0);
  });

  it("rejects provider expressions that hijack a local word offset", async () => {
    const app = new ExtractLearningTargets(
      { read: async () => settings },
      {
        extract: async () => ({
          targets: [target],
          queryableExpressions: [
            { expressionId: "word:2", unitId: "u1", display: "happy" },
          ],
        }),
      },
    );
    await expect(app.execute(command)).rejects.toThrow(
      "Invalid learning target output",
    );
  });
  it.each([
    ["I visited a café.", "cafe"],
    ["I visited a café.", "́"],
    ["I arrived at 2pm.", "pm"],
    ["I arrived at 2pm.", "2"],
    ["It is 3D-printed.", "D-printed"],
  ])(
    "rejects a partial Unicode word in request and response references: %s / %s",
    async (textEn, display) => {
      const input = {
        ...command,
        chapters: [
          {
            chapterId: "c1",
            sentences: [
              { unitId: "u1", textZh: "隔离原句", textEn, isFocus: true },
            ],
          },
        ],
      };
      const expression = {
        unitId: "u1",
        expressionId: `word:${textEn.indexOf(display)}`,
        display,
      };
      let calls = 0;
      const app = new ExtractLearningTargets(
        { read: async () => settings },
        {
          extract: async () => {
            calls++;
            return { targets: [], queryableExpressions: [expression] };
          },
        },
      );
      await expect(
        app.execute({ ...input, requestedExpression: expression }),
      ).rejects.toThrow("Invalid requested expression");
      expect(calls).toBe(0);
      await expect(app.execute(input)).rejects.toThrow(
        "Invalid learning target output",
      );
      expect(calls).toBe(1);
    },
  );

  it("gives independently added words distinct stable IDs even if AI repeats t1", async () => {
    const application = new ExtractLearningTargets(
      { read: async () => settings },
      {
        extract: async (input) => ({
          targets: [
            {
              ...target,
              display: input.requestedExpression?.display ?? "happy",
            },
          ],
          queryableExpressions: [],
        }),
      },
    );
    const first = await application.execute(command);
    const request = {
      ...command,
      commandId: "added-word",
      requestedExpression: {
        unitId: "u1",
        expressionId: "word:2",
        display: "was",
      },
    };
    const added = await application.execute(request);
    const retry = await application.execute(request);
    expect(added.targets[0]?.targetId).not.toBe(first.targets[0]?.targetId);
    expect(added.targets[0]?.targetId).toBe(retry.targets[0]?.targetId);
  });
  it("rejects a requested expression belonging to another sentence before calling AI", async () => {
    let calls = 0;
    const application = new ExtractLearningTargets(
      { read: async () => settings },
      {
        extract: async () => {
          calls++;
          return { targets: [target], queryableExpressions: [] };
        },
      },
    );
    await expect(
      application.execute({
        ...command,
        requestedExpression: {
          unitId: "u1",
          expressionId: "x1",
          display: "weather",
        },
      }),
    ).rejects.toThrow("Invalid requested expression");
    expect(calls).toBe(0);
  });

  it("rejects a different target returned for the selected word", async () => {
    const application = new ExtractLearningTargets(
      { read: async () => settings },
      {
        extract: async () => ({
          targets: [{ ...target, display: "was" }],
          queryableExpressions: [],
        }),
      },
    );
    await expect(
      application.execute({
        ...command,
        requestedExpression: {
          unitId: "u1",
          expressionId: "x1",
          display: "happy",
        },
      }),
    ).rejects.toThrow("Invalid requested expression result");
  });
  it("uses authoritative settings and accepts targets only for focus sentences", async () => {
    const result = await new ExtractLearningTargets(
      { read: async () => settings },
      {
        extract: async (input) => {
          expect(input.settings).toEqual(settings);
          return {
            targets: [target],
            queryableExpressions: [
              { expressionId: "x1", unitId: "u1", display: "was" },
            ],
          };
        },
      },
    ).execute(command);
    expect(result.targets).toEqual([target]);
    expect(result.learningScopes).toEqual(["CET_4", "BUSINESS_ENGLISH"]);
  });
  it("rejects non-focus targets and ambiguous requested-expression results", async () => {
    await expect(
      new ExtractLearningTargets(
        { read: async () => settings },
        {
          extract: async () => ({
            targets: [{ ...target, unitId: "u2" }],
            queryableExpressions: [],
          }),
        },
      ).execute(command),
    ).rejects.toThrow("Invalid learning target output");
    await expect(
      new ExtractLearningTargets(
        { read: async () => settings },
        {
          extract: async () => ({
            targets: [target, { ...target, targetId: "t2" }],
            queryableExpressions: [],
          }),
        },
      ).execute({
        ...command,
        requestedExpression: { unitId: "u1", expressionId: "x1" },
      }),
    ).rejects.toThrow("Invalid requested expression result");
  });
});
