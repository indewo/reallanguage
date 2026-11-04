import { readFileSync } from "node:fs";
import { describe, expect, it, vi } from "vitest";
import {
  ExtractLearningTargets,
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
const command = (textEn: string): LearningTargetProviderCommand => ({
  accountId: "isolated-offset-test",
  commandId: "offset-command",
  settings,
  chapters: [
    {
      chapterId: "c1",
      sentences: [
        { unitId: "u1", textEn, textZh: "隔离测试原句", isFocus: true },
      ],
    },
  ],
});
const target = (surfaceForm: string, startUtf16 = 1) => ({
  targetId: "t1",
  unitId: "u1",
  display: surfaceForm,
  explanationZh: "当前语境解释",
  type: "WORD",
  structureParts: [] as string[],
  relatedVocabulary: [] as string[],
  selectedByDefault: true,
  sourceEvidence: {
    version: 1,
    startUtf16,
    endUtf16: startUtf16 + surfaceForm.length,
    surfaceForm,
    normalizedForm: surfaceForm,
    allowedForms: [surfaceForm],
  },
});
const delivery = (candidate: unknown) => ({
  targets: [candidate],
  queryableExpressions: [],
});
const provider = (output: unknown) => {
  const fetcher = vi.fn(async () => new Response(JSON.stringify(output)));
  return {
    fetcher,
    service: new HttpLearningTargetProvider(
      { endpoint: "https://isolated-provider.test", apiKey: "isolated-test" },
      fetcher,
    ),
  };
};

describe("HttpLearningTargetProvider source alignment", () => {
  it.each([[], [" "], ["drink", "\n"], ["\u00a0\u3000"], [1], [null], [{}]])(
    "rejects invalid provider pattern structure %j after one request",
    async (...structureParts) => {
      const { service, fetcher } = provider(
        delivery({ ...target("drink", 2), type: "PATTERN", structureParts }),
      );
      await expect(service.extract(command("I drink tea."))).rejects.toThrow(
        "Invalid learning target candidate",
      );
      expect(fetcher).toHaveBeenCalledTimes(1);
    },
  );
  it("preserves one useful part verbatim and permits empty WORD/FIXED structures", async () => {
    for (const [type, structureParts] of [
      ["PATTERN", ["  主语 + drink + 宾语  "]],
      ["WORD", []],
      ["FIXED_EXPRESSION", []],
    ]) {
      const original = { ...target("drink", 2), type, structureParts };
      const { service, fetcher } = provider(delivery(original));
      expect(
        (await service.extract(command("I drink tea."))).targets[0],
      ).toEqual(original);
      expect(fetcher).toHaveBeenCalledTimes(1);
    }
  });
  it("replays the captured provider offset error through the unchanged final validator", async () => {
    // Exact output_text payload from target-source-live-captured-output.json.
    // This is an offline regression fixture, not a new live AI acceptance run.
    const captured: unknown = JSON.parse(
      readFileSync(
        new URL(
          "./fixtures/learning-target-offset-capture.json",
          import.meta.url,
        ),
        "utf8",
      ),
    );
    const input = command(
      "Yesterday I worked up the courage to ask for feedback.",
    );
    const { service, fetcher } = provider(captured);
    const application = new ExtractLearningTargets(
      { read: async () => settings },
      service,
    );
    const result = await application.execute(input);
    expect(result.targets.map((item) => item.sourceEvidence)).toEqual([
      {
        version: 1,
        startUtf16: 12,
        endUtf16: 33,
        surfaceForm: "worked up the courage",
        normalizedForm: "work up the courage",
        allowedForms: ["worked up the courage"],
      },
      {
        version: 1,
        startUtf16: 37,
        endUtf16: 53,
        surfaceForm: "ask for feedback",
        normalizedForm: "ask for feedback",
        allowedForms: ["ask for feedback"],
      },
    ]);
    expect(
      result.targets.map((item) => ({ ...item, sourceEvidence: null })),
    ).toEqual(
      (captured as { targets: object[] }).targets.map((item) => ({
        ...item,
        sourceEvidence: null,
      })),
    );
    expect(result.queryableExpressions).toEqual(
      (captured as { queryableExpressions: unknown[] }).queryableExpressions,
    );
    expect(await application.execute(input)).toEqual(result);
    expect(fetcher).toHaveBeenCalledTimes(2);
  });

  it("rebuilds provider-only span length and allowed-form metadata from one exact source phrase", async () => {
    const original = {
      ...target("breaking up", 3),
      type: "FIXED_EXPRESSION",
      sourceEvidence: {
        ...target("breaking up", 3).sourceEvidence,
        endUtf16: 11,
        normalizedForm: "break up",
        allowedForms: ["break up"],
      },
    };
    const result = await provider(delivery(original)).service.extract(
      command("I stopped worrying about breaking up yesterday."),
    );
    expect(result.targets[0]?.sourceEvidence).toEqual({
      version: 1,
      startUtf16: 25,
      endUtf16: 36,
      surfaceForm: "breaking up",
      normalizedForm: "break up",
      allowedForms: ["breaking up"],
    });
  });

  it.each([
    ["😀 I can't leave.", "can't", 5],
    ["😀 I met a part-time colleague.", "part-time", 11],
    ["I enjoyed a café nearby.", "café", 12],
    ["I grew, then grew up.", "grew up", 13],
  ])(
    "rebuilds a unique literal full-word span in %s",
    async (textEn, surface, start) => {
      const original = {
        ...target(surface),
        type: surface.includes(" ") ? "FIXED_EXPRESSION" : "WORD",
      };
      const result = await provider(delivery(original)).service.extract(
        command(textEn),
      );
      expect(result.targets[0]?.sourceEvidence).toEqual({
        ...original.sourceEvidence,
        startUtf16: start,
        endUtf16: start + surface.length,
      });
      expect(original.sourceEvidence.startUtf16).toBe(1);
    },
  );

  it("preserves a valid explicit provider span even when the same word occurs twice", async () => {
    const original = target("used", 12);
    const result = await provider(delivery(original)).service.extract(
      command("I used what used to work."),
    );
    expect(result.targets[0]).toEqual(original);
  });
  it("rejects a WORD whose exact source span belongs to a different displayed word", async () => {
    const candidate = target("coffee", 16);
    await expect(
      provider(
        delivery({
          ...candidate,
          display: "tea",
          sourceEvidence: {
            ...candidate.sourceEvidence,
            normalizedForm: "tea",
          },
        }),
      ).service.extract(command("I drink tea and coffee.")),
    ).rejects.toThrow();
  });
  it.each(["'", "‘", "’", "ʼ", "＇"])(
    "does not align half a %s contraction, and preserves its full clicked form",
    async (apostrophe) => {
      const surface = `I${apostrophe}m`;
      const input = command(`${surface} ready.`);
      await expect(
        provider(delivery(target("m", 2))).service.extract(input),
      ).rejects.toThrow();
      const result = await provider(delivery(target(surface))).service.extract({
        ...input,
        requestedExpression: {
          unitId: "u1",
          expressionId: "word:0",
          display: surface,
        },
      });
      expect(result.targets[0]?.sourceEvidence).toMatchObject({
        startUtf16: 0,
        endUtf16: 3,
        surfaceForm: surface,
      });
    },
  );

  it("rejects ambiguous repair rather than choosing one repeated occurrence", async () => {
    await expect(
      provider(delivery(target("used"))).service.extract(
        command("I used what used to work."),
      ),
    ).rejects.toThrow();
  });

  it("uses the clicked repeated word even when the provider points to another valid occurrence", async () => {
    const original = target("used", 2);
    const input = command("I used what used to work.");
    const requestedExpression = {
      unitId: "u1",
      expressionId: "word:12",
      display: "used",
    };
    const { service } = provider(delivery(original));
    const application = new ExtractLearningTargets(
      { read: async () => settings },
      service,
    );
    const result = await application.execute({ ...input, requestedExpression });
    expect(result.targets[0]?.sourceEvidence).toEqual({
      ...original.sourceEvidence,
      startUtf16: 12,
      endUtf16: 16,
    });
  });
  it.each([
    ["😀 I visited a café at 2pm.", "café"],
    ["😀 I visited a café at 2pm.", "2pm"],
    ["It is 3D-printed.", "3D-printed"],
  ])(
    "aligns the complete clicked Unicode word %s / %s using the source evidence boundary",
    async (textEn, surface) => {
      const start = textEn.indexOf(surface);
      const requestedExpression = {
        unitId: "u1",
        expressionId: `word:${start}`,
        display: surface,
      };
      const candidate = target(surface);
      const output = delivery(candidate);
      const result = await provider(output).service.extract({
        ...command(textEn),
        requestedExpression,
      });
      expect(result.targets[0]?.sourceEvidence).toEqual({
        ...candidate.sourceEvidence,
        startUtf16: start,
        endUtf16: start + surface.length,
      });
    },
  );

  it.each([
    ["word:2", "used"],
    ["word:5", "used"],
    ["word:99", "used"],
    ["word:4", "used to"],
    ["word:04", "used"],
  ])(
    "does not borrow another literal for the user position %s / %s",
    async (expressionId, surface) => {
      await expect(
        provider(delivery(target(surface, 4))).service.extract({
          ...command("We  used to work."),
          requestedExpression: { unitId: "u1", expressionId, display: surface },
        }),
      ).rejects.toThrow();
    },
  );

  it.each([
    ["I can leave.", "can't"],
    ["I walk each day.", "walked"],
    ["I Worked yesterday.", "worked"],
    ["I used to live here.", "used to + verb"],
    ["I worked yesterday.", "work"],
    ["I can’t leave.", "can"],
    ["I work part-time.", "part"],
    ["I live here.", " live"],
  ])(
    "rejects absent, approximate, or partial source evidence in %s",
    async (textEn, surface) => {
      await expect(
        provider(delivery(target(surface))).service.extract(command(textEn)),
      ).rejects.toThrow();
    },
  );

  it("does not repair using a different sentence or a non-focus sentence", async () => {
    const input = command("I stayed home.");
    const other = {
      unitId: "u2",
      textEn: "I used this.",
      textZh: "别句",
      isFocus: false,
    };
    const chapters = [
      { chapterId: "c1", sentences: [...input.chapters[0]!.sentences, other] },
    ];
    const output = target("used", 2);
    await expect(
      provider(delivery(output)).service.extract({ ...input, chapters }),
    ).rejects.toThrow();
    await expect(
      provider(delivery({ ...output, unitId: "u2" })).service.extract({
        ...input,
        chapters,
      }),
    ).rejects.toThrow();
  });

  it("does not return a target from another unit when a specific word was clicked", async () => {
    const input = command("I used this.");
    const chapters = [
      {
        chapterId: "c1",
        sentences: [
          ...input.chapters[0]!.sentences,
          {
            unitId: "u2",
            textEn: "I used that.",
            textZh: "别句",
            isFocus: true,
          },
        ],
      },
    ];
    await expect(
      provider(
        delivery({ ...target("used", 2), unitId: "u2" }),
      ).service.extract({
        ...input,
        chapters,
        requestedExpression: {
          unitId: "u1",
          expressionId: "word:2",
          display: "used",
        },
      }),
    ).rejects.toThrow();
  });

  it.each([
    undefined,
    null,
    { startUtf16: 1 },
    { ...target("used").sourceEvidence, startUtf16: -1, endUtf16: 3 },
    { ...target("used").sourceEvidence, allowedForms: ["used", "use"] },
    { ...target("used").sourceEvidence, normalizedForm: " " },
    { ...target("used").sourceEvidence, version: 2 },
  ])(
    "rejects malformed evidence instead of filling missing semantic fields: %j",
    async (sourceEvidence) => {
      await expect(
        provider(
          delivery({ ...target("used"), sourceEvidence }),
        ).service.extract(command("I used this.")),
      ).rejects.toThrow();
    },
  );

  it("rejects missing pattern structure before attempting to correct an offset", async () => {
    const { service } = provider(
      delivery({
        ...target("used to live"),
        type: "PATTERN",
        display: "used to + verb",
      }),
    );
    const application = new ExtractLearningTargets(
      { read: async () => settings },
      service,
    );
    await expect(
      application.execute(command("I used to live here.")),
    ).rejects.toThrow("Invalid learning target candidate");
  });
});
