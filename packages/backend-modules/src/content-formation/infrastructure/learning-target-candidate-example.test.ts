import { describe, expect, it, vi } from "vitest";
import {
  ExtractLearningTargets,
  type LearningTargetProvider,
  type LearningTargetProviderCommand,
} from "../application/extract-learning-targets.js";
import { HttpLearningTargetProvider } from "./http-learning-target-provider.js";
import { RunMeteredProviderAttempt } from "../../billing/application/run-metered-provider-attempt.js";
import { MeteredProviderFetchAdapter } from "../../billing/infrastructure/metered-provider-fetch-adapter.js";

const settings = {
  formalityLevel: 3,
  slangEnabled: false,
  basicVocabulary: "CET_4",
  purposeVocabularies: [],
  styleReference: "NATURAL_CONCISE",
};
const input: LearningTargetProviderCommand = {
  accountId: "isolated-candidate-account",
  commandId: "same-target-command",
  settings,
  chapters: [
    {
      chapterId: "chapter",
      sentences: [
        {
          unitId: "tea-unit",
          textEn: "I drink tea.",
          textZh: "我喝茶。",
          isFocus: true,
        },
        {
          unitId: "coffee-unit",
          textEn: "I drink coffee.",
          textZh: "我喝咖啡。",
          isFocus: true,
        },
      ],
    },
  ],
};
const example = {
  textEn: "She drinks tea after lunch.",
  textZh: "她午饭后喝茶。",
};
const target = (display = "tea", unitId = "tea-unit") => ({
  targetId: `${display}-target`,
  unitId,
  display,
  explanationZh: display === "tea" ? "茶" : "咖啡",
  type: "WORD" as const,
  structureParts: [],
  relatedVocabulary: [],
  selectedByDefault: true as const,
  sourceEvidence: {
    version: 1 as const,
    startUtf16: 8,
    endUtf16: 8 + display.length,
    surfaceForm: display,
    normalizedForm: display,
    allowedForms: [display],
  },
});
const service = (targets: unknown[]) => {
  const fetcher = vi
    .fn<typeof fetch>()
    .mockImplementation(
      async () =>
        new Response(JSON.stringify({ targets, queryableExpressions: [] })),
    );
  const provider = new HttpLearningTargetProvider(
    { endpoint: "https://isolated-provider.test", apiKey: "isolated-test" },
    fetcher,
  );
  return {
    fetcher,
    application: new ExtractLearningTargets(
      { read: async () => settings },
      provider,
    ),
  };
};

describe("candidate examples through target extraction", () => {
  it("keeps two distinct examples bound to their own target and exact source, in one call", async () => {
    const targets = [
      { ...target(), candidateExample: example },
      {
        ...target("coffee", "coffee-unit"),
        candidateExample: { textEn: "He orders coffee.", textZh: "他点咖啡。" },
      },
    ];
    const { application, fetcher } = service(targets);
    const output = await application.execute(input);
    expect(output.targets).toEqual(targets);
    expect(fetcher).toHaveBeenCalledTimes(1);
    expect(
      new Headers(fetcher.mock.calls[0]?.[1]?.headers).get("Idempotency-Key"),
    ).toBe(input.commandId);
  });
  it("preserves the clicked word example without replacing its evidence or requested command", async () => {
    const { application, fetcher } = service([
      { ...target(), candidateExample: example },
    ]);
    const output = await application.execute({
      ...input,
      requestedExpression: {
        unitId: "tea-unit",
        expressionId: "word:8",
        display: "tea",
      },
    });
    expect(output.targets).toEqual([
      expect.objectContaining({
        ...target(),
        targetId: expect.stringMatching(/^added-/),
        candidateExample: example,
      }),
    ]);
    expect(fetcher).toHaveBeenCalledTimes(1);
  });
  it.each(
    [
      {},
      { textEn: example.textEn },
      { textZh: example.textZh },
      { ...example, textEn: " " },
      { ...example, textZh: "\n" },
      { ...example, textEn: false },
      { ...example, textZh: null },
      { ...example, targetId: "another-target" },
      [],
      "wrong",
    ].map((value) => [value]),
  )(
    "rejects malformed provider examples without retrying %j",
    async (candidateExample) => {
      const { application, fetcher } = service([
        { ...target(), candidateExample },
      ]);
      await expect(application.execute(input)).rejects.toThrow(
        "Invalid learning target candidate",
      );
      expect(fetcher).toHaveBeenCalledTimes(1);
    },
  );
  it.each([
    { textEn: " " },
    { ...example, textZh: 1 },
    { ...example, extra: true },
  ])(
    "also rejects a malformed example behind a typed application provider %j",
    async (candidateExample) => {
      const provider = {
        extract: async () => ({
          targets: [{ ...target(), candidateExample }],
          queryableExpressions: [],
        }),
      } as unknown as LearningTargetProvider;
      await expect(
        new ExtractLearningTargets(
          { read: async () => settings },
          provider,
        ).execute(input),
      ).rejects.toThrow("Invalid learning target output");
    },
  );
  it("does not let a fluent example authorize a target from the wrong sentence", async () => {
    const { application } = service([
      { ...target("coffee", "tea-unit"), candidateExample: example },
    ]);
    await expect(application.execute(input)).rejects.toThrow(
      "Invalid learning target candidate",
    );
  });
  it.each([{}, { candidateExample: null }, { candidateExample: example }])(
    "replays already delivered target output with zero supplier calls and unchanged identity: %j",
    async (extra) => {
      const targets = [{ ...target(), ...extra }];
      const quote = vi.fn().mockResolvedValue({
        providerAttemptId: "stored-attempt",
        maximumChargeFen: 1,
      });
      const execute = vi.fn();
      const claim = vi.fn().mockResolvedValue({
        status: "COMPLETED" as const,
        result: {
          output: { targets, queryableExpressions: [] },
          receipt: {
            providerAttemptId: "stored-attempt",
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
        { quote, execute },
        { claim, complete: vi.fn(), fail: vi.fn() },
      );
      const adapter = new MeteredProviderFetchAdapter(attempts, {
        LEARNING_TARGET_EXTRACTION_V1: "提取目标",
      });
      const makeApplication = () =>
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
      expect((await makeApplication().execute(input)).targets).toEqual(targets);
      expect((await makeApplication().execute(input)).targets).toEqual(targets);
      expect(execute).not.toHaveBeenCalled();
      expect(claim).toHaveBeenCalledTimes(2);
      expect(claim.mock.calls[0]?.[0]).toEqual(claim.mock.calls[1]?.[0]);
      expect(quote.mock.calls[0]?.[0]).toMatchObject({
        accountId: input.accountId,
        commandId: input.commandId,
        task: "LEARNING_TARGET_EXTRACTION_V1",
      });
    },
  );
});
