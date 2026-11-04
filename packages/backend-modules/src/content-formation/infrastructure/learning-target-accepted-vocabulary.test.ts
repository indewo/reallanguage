import { describe, expect, it, vi } from "vitest";
import {
  ExtractLearningTargets,
  InvalidLearningTargetInputError,
  type LearningTargetProvider,
  type LearningTargetProviderCommand,
} from "../application/extract-learning-targets.js";
import { HttpLearningTargetProvider } from "./http-learning-target-provider.js";

const settings = {
  formalityLevel: 3,
  slangEnabled: false,
  basicVocabulary: "CET_4",
  purposeVocabularies: [],
  styleReference: "NATURAL_CONCISE",
};
const evidence = {
  version: 1 as const,
  startUtf16: 8,
  endUtf16: 11,
  surfaceForm: "tea",
  normalizedForm: "tea",
  allowedForms: ["tea"],
};
const acceptedVocabulary = {
  display: "tea",
  senseZh: "茶",
  unitType: "WORD" as const,
  sourceEvidence: evidence,
};
const target = {
  targetId: "t1",
  unitId: "u1",
  display: "tea",
  explanationZh: "茶",
  type: "WORD",
  structureParts: [],
  relatedVocabulary: [],
  relatedVocabularyItems: [],
  relatedVocabularyItemRefs: [],
  selectedByDefault: true,
  sourceEvidence: evidence,
};
const input = {
  accountId: "isolated-accepted",
  commandId: "accepted-word",
  settings,
  chapters: [
    {
      chapterId: "c1",
      sentences: [
        {
          unitId: "u1",
          textEn: "I drink tea and tea.",
          textZh: "我喝茶和茶。",
          isFocus: true,
        },
      ],
    },
  ],
  requestedExpression: {
    unitId: "u1",
    expressionId: "word:8",
    display: "tea",
    acceptedVocabulary,
  },
};
const service = (output: unknown, typed = false) => {
  const fetcher = vi
    .fn<typeof fetch>()
    .mockImplementation(
      async () =>
        new Response(
          JSON.stringify({ targets: [output], queryableExpressions: [] }),
        ),
    );
  const extract = vi.fn(async () => ({
    targets: [output],
    queryableExpressions: [],
  }));
  const provider = typed
    ? ({ extract } as unknown as LearningTargetProvider)
    : new HttpLearningTargetProvider(
        { endpoint: "https://isolated-provider.test", apiKey: "isolated-test" },
        fetcher,
      );
  return {
    app: new ExtractLearningTargets({ read: async () => settings }, provider),
    provider,
    fetcher,
    extract,
  };
};
describe("accepted current-word snapshot", () => {
  it("forwards only the current-span explanation and preserves command identity", async () => {
    const { app, fetcher } = service(target);
    const output = await app.execute(input);
    expect(output.targets[0]).toMatchObject({
      explanationZh: acceptedVocabulary.senseZh,
      sourceEvidence: evidence,
    });
    const body = JSON.parse(String(fetcher.mock.calls[0]?.[1]?.body));
    expect(body.input.requestedExpression.acceptedVocabulary).toEqual(
      acceptedVocabulary,
    );
    expect(
      new Headers(fetcher.mock.calls[0]?.[1]?.headers).get("Idempotency-Key"),
    ).toBe(input.commandId);
    expect(fetcher).toHaveBeenCalledTimes(1);
  });
  it.each([
    null,
    {},
    { ...acceptedVocabulary, senseZh: " " },
    { ...acceptedVocabulary, unitType: "PATTERN" },
    { ...acceptedVocabulary, display: "coffee" },
    {
      ...acceptedVocabulary,
      sourceEvidence: { ...evidence, startUtf16: 16, endUtf16: 19 },
    },
    {
      ...acceptedVocabulary,
      sourceEvidence: {
        ...evidence,
        startUtf16: 9,
        endUtf16: 11,
        surfaceForm: "ea",
        allowedForms: ["ea"],
      },
    },
    {
      ...acceptedVocabulary,
      sourceEvidence: { ...evidence, allowedForms: ["tea", "coffee"] },
    },
    { ...acceptedVocabulary, sourceEvidence: { ...evidence, extra: true } },
    { ...acceptedVocabulary, extra: true },
  ])(
    "rejects invalid accepted source before any provider request %j",
    async (accepted) => {
      const { app, fetcher } = service(target);
      await expect(
        app.execute({
          ...input,
          requestedExpression: {
            ...input.requestedExpression,
            acceptedVocabulary: accepted,
          },
        } as unknown as Parameters<ExtractLearningTargets["execute"]>[0]),
      ).rejects.toBeInstanceOf(InvalidLearningTargetInputError);
      expect(fetcher).not.toHaveBeenCalled();
    },
  );
  it("rejects snapshots on non-positional requests before calling the provider", async () => {
    const { app, fetcher } = service(target);
    await expect(
      app.execute({
        ...input,
        requestedExpression: {
          ...input.requestedExpression,
          expressionId: "query-tea",
        },
      }),
    ).rejects.toBeInstanceOf(InvalidLearningTargetInputError);
    expect(fetcher).not.toHaveBeenCalled();
  });
  it.each([
    { explanationZh: "茶叶" },
    { type: "FIXED_EXPRESSION" },
    { display: "Tea" },
    { sourceEvidence: { ...evidence, normalizedForm: "coffee" } },
    { sourceEvidence: null },
  ])(
    "rejects a different delivered explanation rather than overwriting prior meaning %j",
    async (extra) => {
      for (const typed of [false, true])
        await expect(
          service({ ...target, ...extra }, typed).app.execute(input),
        ).rejects.toThrow();
    },
  );
  it("rejects a PATTERN placeholder for word:N even on legacy requests", async () => {
    const requestedExpression = {
      unitId: "u1",
      expressionId: "word:8",
      display: "tea",
    };
    for (const typed of [false, true])
      await expect(
        service(
          { ...target, type: "PATTERN", structureParts: ["tea"] },
          typed,
        ).app.execute({ ...input, requestedExpression }),
      ).rejects.toThrow();
  });
  it("also enforces accepted output at the direct HTTP-provider boundary", async () => {
    const { provider } = service({ ...target, explanationZh: "晚餐" });
    await expect(
      provider.extract(input as LearningTargetProviderCommand),
    ).rejects.toThrow();
  });
  it("retains legacy word requests without an accepted explanation", async () => {
    const requestedExpression = {
      unitId: "u1",
      expressionId: "word:8",
      display: "tea",
    };
    expect(
      (await service(target).app.execute({ ...input, requestedExpression }))
        .targets[0]?.display,
    ).toBe("tea");
  });
  it("does not borrow a first-occurrence sense for the second occurrence", async () => {
    const second = { ...evidence, startUtf16: 16, endUtf16: 19 };
    const command = {
      ...input,
      requestedExpression: {
        ...input.requestedExpression,
        expressionId: "word:16",
        acceptedVocabulary: {
          ...acceptedVocabulary,
          senseZh: "第二处茶",
          sourceEvidence: second,
        },
      },
    };
    const output = await service({
      ...target,
      explanationZh: "第二处茶",
      sourceEvidence: second,
    }).app.execute(command);
    expect(output.targets[0]?.sourceEvidence).toEqual(second);
    expect(output.targets[0]?.explanationZh).toBe("第二处茶");
  });
});
