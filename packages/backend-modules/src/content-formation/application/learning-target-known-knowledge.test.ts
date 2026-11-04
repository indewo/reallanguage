import { describe, expect, it, vi } from "vitest";
import { ExtractLearningTargets } from "./extract-learning-targets.js";
import { HttpLearningTargetProvider } from "../infrastructure/http-learning-target-provider.js";

const settings = {
  formalityLevel: 3,
  slangEnabled: false,
  basicVocabulary: "CET_4",
  purposeVocabularies: [],
  styleReference: "NATURAL_CONCISE",
};
const known = {
  ref: "PATTERN:p1",
  type: "PATTERN" as const,
  display: "used to + verb",
  meaningZh: "过去的习惯",
};
const sentence = {
  unitId: "u1",
  textZh: "我以前常步行。",
  textEn: "I used to walk.",
  isFocus: true,
  reusedKnowledge: [known],
};
const command = {
  accountId: "isolated-owner",
  commandId: "targets-1",
  chapters: [{ chapterId: "c1", sentences: [sentence] }],
};
const target = {
  targetId: "t1",
  unitId: "u1",
  display: known.display,
  explanationZh: known.meaningZh,
  type: "PATTERN" as const,
  structureParts: ["used to", "+ verb"],
  relatedVocabulary: [],
  selectedByDefault: true as const,
  reusedKnowledgeRef: known.ref,
  sourceEvidence: {
    version: 1 as const,
    startUtf16: 2,
    endUtf16: 14,
    surfaceForm: "used to walk",
    normalizedForm: known.display,
    allowedForms: ["used to walk"],
  },
};

describe("exact per-sentence known knowledge targets", () => {
  const execute = (targets: unknown[], input = command) =>
    new ExtractLearningTargets({ read: async () => settings }, {
      extract: async () => ({ targets, queryableExpressions: [] }),
    } as never).execute(input);

  it("keeps the existing pattern identity and exact meaning selected by default", async () => {
    const result = await execute([target]);
    expect(result.targets).toEqual([target]);
  });

  it.each([
    ["unknown ref", { ...target, reusedKnowledgeRef: "PATTERN:other" }],
    ["different sense", { ...target, explanationZh: "未来的计划" }],
    ["different pattern", { ...target, display: "want to + verb" }],
    [
      "different type",
      { ...target, type: "FIXED_EXPRESSION", display: "used to walk" },
    ],
    ["missing source evidence", { ...target, sourceEvidence: null }],
  ])(
    "rejects %s instead of recording an invented old item",
    async (_reason, invalid) => {
      await expect(execute([invalid])).rejects.toThrow(
        "Invalid learning target output",
      );
    },
  );

  it("requires exactly one default target for each explicitly reused focus item, never a quota", async () => {
    await expect(execute([])).rejects.toThrow("Invalid learning target output");
    await expect(
      execute([target, { ...target, targetId: "t2" }]),
    ).rejects.toThrow("Invalid learning target output");
    await expect(
      execute([{ ...target, reusedKnowledgeRef: null }]),
    ).rejects.toThrow("Invalid learning target output");
    await expect(
      execute([], {
        ...command,
        chapters: [
          {
            chapterId: "c1",
            sentences: [{ ...sentence, reusedKnowledge: [] }],
          },
        ],
      }),
    ).resolves.toMatchObject({ targets: [] });
  });

  it("cannot borrow another sentence's existing item", async () => {
    const input = {
      ...command,
      chapters: [
        {
          chapterId: "c1",
          sentences: [
            { ...sentence, reusedKnowledge: [] },
            { ...sentence, unitId: "u2" },
          ],
        },
      ],
    };
    await expect(execute([target], input)).rejects.toThrow(
      "Invalid learning target output",
    );
  });

  it("invalid or inconsistent input refs fail before any provider work", async () => {
    const provider = vi
      .fn()
      .mockResolvedValue({ targets: [target], queryableExpressions: [] });
    const app = new ExtractLearningTargets(
      { read: async () => settings },
      { extract: provider },
    );
    for (const reusedKnowledge of [
      [known, known],
      [{ ...known, type: "VOCABULARY" }],
      [{ ...known, meaningZh: " " }],
    ]) {
      await expect(
        app.execute({
          ...command,
          chapters: [
            { chapterId: "c1", sentences: [{ ...sentence, reusedKnowledge }] },
          ],
        } as never),
      ).rejects.toThrow("Invalid learning target input");
    }
    expect(provider).not.toHaveBeenCalled();
  });

  it("a vocabulary ref preserves its exact sense while allowing the actual inflected source word", async () => {
    const item = {
      ref: "VOCABULARY:v1",
      type: "VOCABULARY" as const,
      display: "work",
      meaningZh: "工作",
    };
    const input = {
      ...command,
      chapters: [
        {
          chapterId: "c1",
          sentences: [
            {
              ...sentence,
              textEn: "She worked today.",
              reusedKnowledge: [item],
            },
          ],
        },
      ],
    };
    const word = {
      ...target,
      type: "WORD",
      display: "worked",
      explanationZh: item.meaningZh,
      structureParts: [],
      reusedKnowledgeRef: item.ref,
      sourceEvidence: {
        version: 1,
        startUtf16: 4,
        endUtf16: 10,
        surfaceForm: "worked",
        normalizedForm: "work",
        allowedForms: ["worked"],
      },
    };
    await expect(execute([word], input as never)).resolves.toMatchObject({
      targets: [{ reusedKnowledgeRef: item.ref, display: "worked" }],
    });
    await expect(
      execute([{ ...word, explanationZh: "作品" }], input as never),
    ).rejects.toThrow("Invalid learning target output");
  });

  it("adding one clicked word does not regenerate every previous default target", async () => {
    const input = {
      ...command,
      requestedExpression: {
        unitId: "u1",
        expressionId: "word:10",
        display: "walk",
      },
    };
    const word = {
      ...target,
      type: "WORD",
      display: "walk",
      explanationZh: "步行",
      structureParts: [],
      reusedKnowledgeRef: null,
      sourceEvidence: {
        version: 1,
        startUtf16: 10,
        endUtf16: 14,
        surfaceForm: "walk",
        normalizedForm: "walk",
        allowedForms: ["walk"],
      },
    };
    await expect(execute([word], input)).resolves.toMatchObject({
      targets: [{ display: "walk" }],
    });
  });

  it("rejects an old word identity whose form contradicts the current word evidence", async () => {
    const item = {
      ref: "VOCABULARY:work",
      type: "VOCABULARY" as const,
      display: "work",
      meaningZh: "工作",
    };
    const input = {
      ...command,
      chapters: [
        {
          chapterId: "c1",
          sentences: [
            {
              ...sentence,
              textEn: "She walked today.",
              reusedKnowledge: [item],
            },
          ],
        },
      ],
    };
    const unrelated = {
      ...target,
      type: "WORD",
      display: "walked",
      explanationZh: item.meaningZh,
      reusedKnowledgeRef: item.ref,
      structureParts: [],
      sourceEvidence: {
        version: 1,
        startUtf16: 4,
        endUtf16: 10,
        surfaceForm: "walked",
        normalizedForm: "walk",
        allowedForms: ["walked"],
      },
    };
    await expect(execute([unrelated], input as never)).rejects.toThrow(
      "Invalid learning target output",
    );
  });

  it("the real provider decoder preserves a typed ref and rejects malformed refs", async () => {
    let responseTarget: unknown = target;
    const provider = new HttpLearningTargetProvider(
      { endpoint: "https://provider.invalid", apiKey: "isolated-test-only" },
      async () =>
        new Response(
          JSON.stringify({
            targets: [responseTarget],
            queryableExpressions: [],
          }),
        ),
    );
    expect(
      (await provider.extract({ ...command, settings })).targets[0],
    ).toMatchObject({ reusedKnowledgeRef: known.ref });
    responseTarget = { ...target, reusedKnowledgeRef: 7 };
    await expect(provider.extract({ ...command, settings })).rejects.toThrow(
      "Invalid learning target candidate",
    );
  });
});
