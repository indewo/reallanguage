import { describe, expect, it } from "vitest";
import {
  ExtractLearningTargets,
  type LearningTargetCandidate,
} from "./extract-learning-targets.js";
import { HttpLearningTargetProvider } from "../infrastructure/http-learning-target-provider.js";

const textEn = "😀 I used to live here, and I used to work there.";
const surfaceForm = "used to live";
const sourceEvidence = {
  version: 1 as const,
  startUtf16: textEn.indexOf(surfaceForm),
  endUtf16: textEn.indexOf(surfaceForm) + surfaceForm.length,
  surfaceForm,
  normalizedForm: "used to + verb",
  allowedForms: [surfaceForm],
};
const target = {
  targetId: "t1",
  unitId: "u1",
  display: "used to + verb",
  explanationZh: "过去经常做某事",
  type: "PATTERN" as const,
  structureParts: ["used to", "verb"],
  relatedVocabulary: [],
  selectedByDefault: true as const,
  sourceEvidence,
};
const settings = {
  formalityLevel: 3,
  slangEnabled: false,
  basicVocabulary: "CET_4",
  purposeVocabularies: [],
  styleReference: "NATURAL_CONCISE",
};
const command = {
  accountId: "isolated",
  commandId: "evidence-1",
  chapters: [
    {
      chapterId: "c1",
      sentences: [
        { unitId: "u1", textEn, textZh: "我过去住在这里。", isFocus: true },
      ],
    },
  ],
};
const application = (candidate: LearningTargetCandidate) =>
  new ExtractLearningTargets(
    { read: async () => settings },
    {
      extract: async () => ({ targets: [candidate], queryableExpressions: [] }),
    },
  );

describe("locked target source evidence", () => {
  const wordCandidate = (
    surfaceForm: string,
    startUtf16: number,
    display = surfaceForm,
  ): LearningTargetCandidate => ({
    ...target,
    type: "WORD",
    display,
    structureParts: [],
    sourceEvidence: {
      version: 1,
      startUtf16,
      endUtf16: startUtf16 + surfaceForm.length,
      surfaceForm,
      normalizedForm: display,
      allowedForms: [surfaceForm],
    },
  });
  const sourceCommand = (textEn: string) => ({
    ...command,
    chapters: [
      {
        chapterId: "c1",
        sentences: [
          { unitId: "u1", textEn, textZh: "隔离原句", isFocus: true },
        ],
      },
    ],
  });
  it("rejects an exact coffee span as evidence for the separate tea target", async () => {
    await expect(
      application(wordCandidate("coffee", 16, "tea")).execute(
        sourceCommand("I drink tea and coffee."),
      ),
    ).rejects.toThrow("Invalid learning target output");
  });
  it("keeps the actual inflected word as display while retaining a separate lemma", async () => {
    const candidate = wordCandidate("worked", 2, "Worked");
    const sourceEvidence = {
      ...candidate.sourceEvidence!,
      normalizedForm: "work",
    };
    expect(
      (
        await application({ ...candidate, sourceEvidence }).execute(
          sourceCommand("I worked yesterday."),
        )
      ).targets[0],
    ).toEqual({ ...candidate, sourceEvidence });
    await expect(
      application({ ...candidate, display: "work", sourceEvidence }).execute(
        sourceCommand("I worked yesterday."),
      ),
    ).rejects.toThrow("Invalid learning target output");
  });
  it("rejects a multiword source mislabeled as a single WORD", async () => {
    await expect(
      application(wordCandidate("tea and coffee", 8)).execute(
        sourceCommand("I drink tea and coffee."),
      ),
    ).rejects.toThrow("Invalid learning target output");
  });
  it.each(["WORD", "FIXED_EXPRESSION"] as const)(
    "rejects a complete question mislabeled as %s vocabulary",
    async (type) => {
      const sentence = "Can you hear what I'm saying right now?";
      const candidate: LearningTargetCandidate = {
        ...target,
        type,
        display: sentence,
        structureParts: [],
        sourceEvidence: {
          version: 1,
          startUtf16: 0,
          endUtf16: sentence.length,
          surfaceForm: sentence,
          normalizedForm: sentence,
          allowedForms: [sentence],
        },
      };
      await expect(
        application(candidate).execute(sourceCommand(sentence)),
      ).rejects.toThrow("Invalid learning target output");
    },
  );
  it.each(["'", "‘", "’", "ʼ", "＇"])(
    "keeps %s contractions whole for evidence and clicked word references",
    async (apostrophe) => {
      const surface = `I${apostrophe}m`;
      const input = sourceCommand(`${surface} ready.`);
      await expect(
        application(wordCandidate("m", 2)).execute(input),
      ).rejects.toThrow("Invalid learning target output");
      const candidate = wordCandidate(surface, 0);
      const result = await application(candidate).execute({
        ...input,
        requestedExpression: {
          unitId: "u1",
          expressionId: "word:0",
          display: surface,
        },
      });
      expect(result.targets[0]?.sourceEvidence).toEqual(
        candidate.sourceEvidence,
      );
      await expect(
        application(wordCandidate("m", 2)).execute({
          ...input,
          requestedExpression: {
            unitId: "u1",
            expressionId: "word:2",
            display: "m",
          },
        }),
      ).rejects.toThrow("Invalid requested expression");
    },
  );
  it("preserves an exact UTF-16 realization instead of treating a grammar display as the answer", async () => {
    const result = await application(target).execute(command);
    expect(result.targets[0]).toMatchObject({
      display: "used to + verb",
      sourceEvidence,
    });
  });
  it.each([
    { startUtf16: sourceEvidence.startUtf16 - 1 },
    { endUtf16: textEn.length + 1 },
    {
      startUtf16: 1,
      endUtf16: 2,
      surfaceForm: textEn.slice(1, 2),
      allowedForms: [textEn.slice(1, 2)],
    },
    { surfaceForm: "used to work" },
    { allowedForms: [surfaceForm, "use to live"] },
    { allowedForms: ["lived"] },
    { normalizedForm: " " },
    { version: 2 },
    { startUtf16: sourceEvidence.startUtf16 + 0.5 },
    {
      startUtf16: sourceEvidence.startUtf16 + 1,
      surfaceForm: "sed to live",
      allowedForms: ["sed to live"],
    },
  ])(
    "rejects invalid source or invented accepted forms: %j",
    async (change) => {
      await expect(
        application({
          ...target,
          sourceEvidence: { ...sourceEvidence, ...change },
        } as typeof target).execute(command),
      ).rejects.toThrow("Invalid learning target output");
    },
  );
  it("binds a clicked repeated word to the clicked occurrence, not another identical word", async () => {
    const start = textEn.lastIndexOf("used");
    const candidate = {
      ...target,
      type: "WORD" as const,
      display: "used",
      sourceEvidence: {
        ...sourceEvidence,
        endUtf16: sourceEvidence.startUtf16 + 4,
        surfaceForm: "used",
        normalizedForm: "use",
        allowedForms: ["used"],
      },
    };
    const requestedExpression = {
      unitId: "u1",
      expressionId: `word:${start}`,
      display: "used",
    };
    await expect(
      application(candidate).execute({ ...command, requestedExpression }),
    ).rejects.toThrow("Invalid requested expression result");
  });
  it("preserves evidence through the actual HTTP provider decoder", async () => {
    const provider = new HttpLearningTargetProvider(
      { endpoint: "https://provider.test", apiKey: "isolated-test" },
      async () =>
        new Response(
          JSON.stringify({ targets: [target], queryableExpressions: [] }),
        ),
    );
    expect(
      (await provider.extract({ ...command, settings })).targets[0],
    ).toEqual(target);
  });
  it("rejects missing evidence in a new provider response, without fabricating a surface form", async () => {
    const legacy = { ...target, sourceEvidence: undefined };
    const provider = new HttpLearningTargetProvider(
      { endpoint: "https://provider.test", apiKey: "isolated-test" },
      async () =>
        new Response(
          JSON.stringify({ targets: [legacy], queryableExpressions: [] }),
        ),
    );
    await expect(provider.extract({ ...command, settings })).rejects.toThrow(
      "Invalid learning target candidate",
    );
  });
});
