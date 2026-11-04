import { describe, expect, it, vi } from "vitest";
import {
  ExtractLearningTargets,
  type LearningTargetProvider,
  type LearningTargetProviderCommand,
} from "../application/extract-learning-targets.js";
import { HttpLearningTargetProvider } from "./http-learning-target-provider.js";
import { RunMeteredProviderAttempt } from "../../billing/application/run-metered-provider-attempt.js";
import { MeteredProviderFetchAdapter } from "../../billing/infrastructure/metered-provider-fetch-adapter.js";

const evidence = (startUtf16 = 8, surfaceForm = "tea") => ({
  version: 1,
  startUtf16,
  endUtf16: startUtf16 + surfaceForm.length,
  surfaceForm,
  normalizedForm: surfaceForm,
  allowedForms: [surfaceForm],
});
const item = {
  itemRef: "local-tea",
  unitId: "u1",
  display: "tea",
  senseZh: "茶",
  unitType: "WORD",
  sourceEvidence: evidence(),
};
const base = {
  targetId: "p1",
  unitId: "u1",
  display: "drink X and Y",
  explanationZh: "喝两种饮品",
  type: "PATTERN",
  structureParts: ["动词与并列宾语"],
  relatedVocabulary: ["tea"],
  selectedByDefault: true,
  sourceEvidence: evidence(2, "drink tea and coffee"),
};
const target = {
  ...base,
  relatedVocabularyItems: [item],
  relatedVocabularyItemRefs: [item.itemRef],
};
const settings = {
  formalityLevel: 3,
  slangEnabled: false,
  basicVocabulary: "CET_4",
  purposeVocabularies: [],
  styleReference: "NATURAL_CONCISE",
};
const input: LearningTargetProviderCommand = {
  accountId: "isolated-related-account",
  commandId: "same-command",
  settings,
  chapters: [
    {
      chapterId: "c1",
      sentences: [
        {
          unitId: "u1",
          textEn: "I drink tea and coffee.",
          textZh: "我喝茶和咖啡。",
          isFocus: true,
        },
        {
          unitId: "u2",
          textEn: "I drink tea and coffee.",
          textZh: "我喝茶和咖啡。",
          isFocus: true,
        },
      ],
    },
  ],
};
const service = (targets: unknown[], typed = false) => {
  const fetcher = vi
    .fn<typeof fetch>()
    .mockImplementation(
      async () =>
        new Response(JSON.stringify({ targets, queryableExpressions: [] })),
    );
  const provider = typed
    ? ({
        extract: async () => ({ targets, queryableExpressions: [] }),
      } as unknown as LearningTargetProvider)
    : new HttpLearningTargetProvider(
        { endpoint: "https://isolated-provider.test", apiKey: "isolated-test" },
        fetcher,
      );
  return {
    fetcher,
    app: new ExtractLearningTargets({ read: async () => settings }, provider),
  };
};
const word = {
  targetId: "w1",
  unitId: "u1",
  type: "WORD",
  display: "tea",
  explanationZh: "茶",
  structureParts: [],
  relatedVocabulary: [],
  selectedByDefault: true,
  sourceEvidence: evidence(),
};

describe("related vocabulary source and identity", () => {
  it("aligns a unique related source phrase without rewriting its meaning or making another request", async () => {
    const raw = {
      ...target,
      relatedVocabularyItems: [{ ...item, sourceEvidence: evidence(0) }],
    };
    const { app, fetcher } = service([raw]);
    expect((await app.execute(input)).targets).toEqual([target]);
    expect(raw.relatedVocabularyItems[0]?.sourceEvidence.startUtf16).toBe(0);
    expect(fetcher).toHaveBeenCalledTimes(1);
  });
  it("rebuilds one related item's provider-only length and allowed-form metadata from the exact current sentence", async () => {
    const raw = {
      ...target,
      relatedVocabularyItems: [
        {
          ...item,
          sourceEvidence: {
            ...evidence(0),
            endUtf16: 2,
            allowedForms: ["teas"],
          },
        },
      ],
    };
    const { app, fetcher } = service([raw]);
    expect((await app.execute(input)).targets).toEqual([target]);
    expect(fetcher).toHaveBeenCalledTimes(1);
  });
  it("does not guess a related occurrence when a wrong offset has two exact matches", async () => {
    const raw = {
      ...target,
      sourceEvidence: evidence(2, "drink tea and tea"),
      relatedVocabularyItems: [{ ...item, sourceEvidence: evidence(0) }],
    };
    const repeated = {
      ...input,
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
    };
    await expect(service([raw]).app.execute(repeated)).rejects.toThrow();
  });
  it("rejects two actual vocabulary targets at one occurrence even when their explanations agree", async () => {
    for (const typed of [false, true])
      await expect(
        service(
          [word, { ...word, targetId: "duplicate-word" }],
          typed,
        ).app.execute(input),
      ).rejects.toThrow();
  });
  it("still allows separate patterns to reuse one identical explanatory snapshot", async () => {
    const other = {
      ...target,
      targetId: "p2",
      display: "another reusable structure",
    };
    expect((await service([target, other]).app.execute(input)).targets).toEqual(
      [target, other],
    );
  });
  it("keeps local refs intact when the requested target ID is replaced", async () => {
    const value = { ...target, display: "drink tea and coffee" };
    const { app, fetcher } = service([value]);
    const output = await app.execute({
      ...input,
      requestedExpression: {
        unitId: "u1",
        expressionId: "phrase-2",
        display: value.display,
      },
    });
    expect(output.targets).toEqual([
      { ...value, targetId: expect.stringMatching(/^added-/) },
    ]);
    expect(fetcher).toHaveBeenCalledTimes(1);
  });
  it("resolves order from refs rather than silently using dictionary insertion order", async () => {
    const coffee = {
      ...item,
      itemRef: "local-coffee",
      display: "coffee",
      senseZh: "咖啡",
      sourceEvidence: evidence(16, "coffee"),
    };
    const value = {
      ...target,
      relatedVocabularyItems: [item, coffee],
      relatedVocabularyItemRefs: [coffee.itemRef, item.itemRef],
      relatedVocabulary: ["coffee", "tea"],
    };
    expect((await service([value]).app.execute(input)).targets).toEqual([
      value,
    ]);
    await expect(
      service([{ ...value, relatedVocabulary: ["tea", "coffee"] }]).app.execute(
        input,
      ),
    ).rejects.toThrow();
  });
  it.each([
    base,
    { ...base, relatedVocabularyItems: null, relatedVocabularyItemRefs: null },
    target,
    word,
  ])(
    "replays delivered explanations through the metered cache with zero new provider attempts: %j",
    async (value) => {
      const execute = vi.fn();
      const claim = vi.fn().mockResolvedValue({
        status: "COMPLETED",
        result: {
          output: { targets: [value], queryableExpressions: [] },
          receipt: {
            providerAttemptId: "stored-related-attempt",
            modelSource: "isolated/model",
            providerCostMicroCny: 1,
          },
        },
      });
      const attempts = new RunMeteredProviderAttempt(
        {
          reserve: vi.fn(async () => ({
            status: "SETTLED" as const,
            reservedFen: 1,
          })),
          settle: vi.fn(async () => ({
            status: "SETTLED" as const,
            chargedFen: 1,
          })),
          release: vi.fn(),
          requireReconciliation: vi.fn(),
        },
        {
          quote: vi.fn(async () => ({
            providerAttemptId: "stored-related-attempt",
            maximumChargeFen: 1,
          })),
          execute,
        },
        { claim, complete: vi.fn(), fail: vi.fn() },
      );
      const adapter = new MeteredProviderFetchAdapter(attempts, {
        LEARNING_TARGET_EXTRACTION_V1: "提取目标",
      });
      const make = () =>
        new ExtractLearningTargets(
          { read: async () => settings },
          new HttpLearningTargetProvider(
            {
              endpoint: "https://isolated-provider.test",
              apiKey: "isolated-test",
            },
            adapter.fetch,
          ),
        );
      const request =
        value === word
          ? {
              ...input,
              requestedExpression: {
                unitId: word.unitId,
                expressionId: "word:8",
                display: word.display,
                acceptedVocabulary: {
                  display: word.display,
                  senseZh: word.explanationZh,
                  unitType: "WORD" as const,
                  sourceEvidence: {
                    ...word.sourceEvidence,
                    version: 1 as const,
                  },
                },
              },
            }
          : input;
      const first = await make().execute(request);
      const second = await make().execute(request);
      expect(first).toEqual(second);
      expect(first.targets[0]).toEqual(
        value === word
          ? { ...value, targetId: expect.stringMatching(/^added-/) }
          : value,
      );
      expect(execute).not.toHaveBeenCalled();
      expect(claim).toHaveBeenCalledTimes(2);
      expect(claim.mock.calls[0]?.[0]).toEqual(claim.mock.calls[1]?.[0]);
      expect(claim.mock.calls[0]?.[0]).toMatchObject({
        accountId: input.accountId,
        commandId: input.commandId,
        task: "LEARNING_TARGET_EXTRACTION_V1",
      });
    },
  );
  it("replays and repairs a previously delivered provider-metadata failure without another AI attempt", async () => {
    const raw = {
      ...target,
      sourceEvidence: {
        ...target.sourceEvidence,
        startUtf16: 0,
        endUtf16: 4,
        allowedForms: ["drink X and Y"],
      },
      relatedVocabularyItems: [
        {
          ...item,
          sourceEvidence: {
            ...item.sourceEvidence,
            startUtf16: 0,
            endUtf16: 2,
            allowedForms: ["teas"],
          },
        },
      ],
    };
    const execute = vi.fn();
    const attempts = new RunMeteredProviderAttempt(
      {
        reserve: vi.fn(async () => ({
          status: "SETTLED" as const,
          reservedFen: 1,
        })),
        settle: vi.fn(async () => ({
          status: "SETTLED" as const,
          chargedFen: 1,
        })),
        release: vi.fn(),
        requireReconciliation: vi.fn(),
      },
      {
        quote: vi.fn(async () => ({
          providerAttemptId: "stored-invalid-metadata",
          maximumChargeFen: 1,
        })),
        execute,
      },
      {
        claim: vi.fn(async () => ({
          status: "COMPLETED" as const,
          result: {
            output: { targets: [raw], queryableExpressions: [] },
            receipt: {
              providerAttemptId: "stored-invalid-metadata",
              modelSource: "isolated/model",
              providerCostMicroCny: 1,
            },
          },
        })),
        complete: vi.fn(),
        fail: vi.fn(),
      },
    );
    const adapter = new MeteredProviderFetchAdapter(attempts, {
      LEARNING_TARGET_EXTRACTION_V1: "提取目标",
    });
    const result = await new ExtractLearningTargets(
      { read: async () => settings },
      new HttpLearningTargetProvider(
        {
          endpoint: "https://isolated-provider.test",
          apiKey: "isolated-test",
        },
        adapter.fetch,
      ),
    ).execute(input);
    expect(result.targets).toEqual([target]);
    expect(execute).not.toHaveBeenCalled();
  });
  it("returns the explanation even when no separate word learning target is generated", async () => {
    const { app, fetcher } = service([target]);
    const output = await app.execute(input);
    expect(output.targets).toEqual([target]);
    expect(fetcher).toHaveBeenCalledTimes(1);
    expect(
      new Headers(fetcher.mock.calls[0]?.[1]?.headers).get("Idempotency-Key"),
    ).toBe(input.commandId);
  });
  it("keeps a related snapshot consistent with a simultaneously offered vocabulary target", async () => {
    expect((await service([word, target]).app.execute(input)).targets).toEqual([
      word,
      target,
    ]);
  });
  it.each([
    { relatedVocabularyItems: [item] },
    { relatedVocabularyItemRefs: [item.itemRef] },
    { relatedVocabularyItems: null },
    { relatedVocabularyItems: [item], relatedVocabularyItemRefs: ["missing"] },
    {
      relatedVocabularyItems: [item],
      relatedVocabularyItemRefs: [item.itemRef, item.itemRef],
    },
    {
      relatedVocabularyItems: [item, { ...item, itemRef: "extra" }],
      relatedVocabularyItemRefs: [item.itemRef],
    },
    {
      relatedVocabularyItems: [{ ...item, unitId: "u2" }],
      relatedVocabularyItemRefs: [item.itemRef],
    },
    {
      relatedVocabularyItems: [
        { ...item, sourceEvidence: evidence(9, "ea"), display: "ea" },
      ],
      relatedVocabularyItemRefs: [item.itemRef],
    },
    {
      relatedVocabularyItems: [{ ...item, senseZh: " " }],
      relatedVocabularyItemRefs: [item.itemRef],
    },
    {
      relatedVocabularyItems: [{ ...item, display: "coffee" }],
      relatedVocabularyItemRefs: [item.itemRef],
    },
    {
      relatedVocabularyItems: [{ ...item, unitType: "PATTERN" }],
      relatedVocabularyItemRefs: [item.itemRef],
    },
    {
      relatedVocabularyItems: [{ ...item, extra: "unexpected" }],
      relatedVocabularyItemRefs: [item.itemRef],
    },
    {
      relatedVocabularyItems: [item],
      relatedVocabularyItemRefs: [item.itemRef],
      relatedVocabulary: ["coffee"],
    },
  ])("rejects invalid supplier and typed-provider groups %j", async (extra) => {
    for (const typed of [false, true])
      await expect(
        service([{ ...base, ...extra }], typed).app.execute(input),
      ).rejects.toThrow();
  });
  it.each([{ explanationZh: "茶几" }, { type: "FIXED_EXPRESSION" }])(
    "rejects contradictory same-span actual vocabulary targets %j",
    async (extra) => {
      for (const typed of [false, true])
        await expect(
          service([{ ...word, ...extra }, target], typed).app.execute(input),
        ).rejects.toThrow();
    },
  );
  it("rejects competing meanings for the same span across pattern snapshots", async () => {
    const other = {
      ...target,
      targetId: "p2",
      relatedVocabularyItems: [{ ...item, senseZh: "茶几" }],
    };
    for (const typed of [false, true])
      await expect(
        service([target, other], typed).app.execute(input),
      ).rejects.toThrow();
  });
  it("keeps the same word at distinct real spans legal", async () => {
    const repeated = {
      ...input,
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
    };
    const otherItem = {
      ...item,
      itemRef: "second-tea",
      sourceEvidence: evidence(16),
    };
    const value = {
      ...target,
      sourceEvidence: evidence(2, "drink tea and tea"),
      relatedVocabularyItems: [item, otherItem],
      relatedVocabularyItemRefs: [otherItem.itemRef, item.itemRef],
      relatedVocabulary: ["tea", "tea"],
    };
    expect((await service([value]).app.execute(repeated)).targets).toEqual([
      value,
    ]);
  });
  it.each([
    {},
    { relatedVocabularyItems: null, relatedVocabularyItemRefs: null },
  ])("keeps old output without inventing explanations %j", async (extra) => {
    const value = { ...base, ...extra };
    expect((await service([value]).app.execute(input)).targets).toEqual([
      value,
    ]);
  });
});
