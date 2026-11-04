import { describe, expect, it } from "vitest";
import { EvaluatePatternIndependent } from "./evaluate-pattern-independent.js";

describe("EvaluatePatternIndependent", () => {
  it.each(["PASS", "FAIL"])(
    "does not accept a low-confidence %s",
    async (decision) => {
      const application = new EvaluatePatternIndependent({
        generate: async () => ({ ...candidate, decision, confidence: 0.4 }),
      });
      await expect(application.execute(command)).resolves.toMatchObject({
        decision: "INCONCLUSIVE",
      });
    },
  );
  it("accepts bounded hidden-target evidence", async () => {
    const application = new EvaluatePatternIndependent({
      generate: async () => candidate,
    });
    await expect(application.execute(command)).resolves.toEqual(candidate);
  });

  it("rejects invalid provider authority", async () => {
    const application = new EvaluatePatternIndependent({
      generate: async () => ({ ...candidate, confidence: 2 }),
    });
    await expect(application.execute(command)).rejects.toThrow(
      "Invalid independent pattern output",
    );
  });

  it.each([
    "structurePreserved",
    "contentSuitable",
    "pronunciationIntelligible",
  ] as const)(
    "does not turn contradictory PASS with %s=false into a formal result",
    async (field) => {
      const output = { ...candidate, [field]: false };
      const application = new EvaluatePatternIndependent({
        generate: async () => output,
      });

      await expect(application.execute(command)).resolves.toEqual({
        ...output,
        decision: "INCONCLUSIVE",
      });
    },
  );

  it("does not turn PASS with all failed criteria into a formal failure either", async () => {
    const output = {
      ...candidate,
      structurePreserved: false,
      contentSuitable: false,
      pronunciationIntelligible: false,
    };
    const application = new EvaluatePatternIndependent({
      generate: async () => output,
    });

    await expect(application.execute(command)).resolves.toEqual({
      ...output,
      decision: "INCONCLUSIVE",
    });
  });

  it("does not turn contradictory FAIL with all criteria satisfied into a formal result", async () => {
    const output = { ...candidate, decision: "FAIL" };
    const application = new EvaluatePatternIndependent({
      generate: async () => output,
    });

    await expect(application.execute(command)).resolves.toEqual({
      ...output,
      decision: "INCONCLUSIVE",
    });
  });

  it.each([
    "structurePreserved",
    "contentSuitable",
    "pronunciationIntelligible",
  ] as const)(
    "retains a sufficiently confident FAIL supported by %s=false",
    async (field) => {
      const output = { ...candidate, decision: "FAIL", [field]: false };
      const application = new EvaluatePatternIndependent({
        generate: async () => output,
      });

      await expect(application.execute(command)).resolves.toEqual(output);
    },
  );

  it.each([true, false])(
    "keeps an INCONCLUSIVE result when criterion booleans are %s",
    async (satisfied) => {
      const output = {
        ...candidate,
        decision: "INCONCLUSIVE",
        structurePreserved: satisfied,
        contentSuitable: satisfied,
        pronunciationIntelligible: satisfied,
      };
      const application = new EvaluatePatternIndependent({
        generate: async () => output,
      });

      await expect(application.execute(command)).resolves.toEqual(output);
    },
  );

  it.each([
    { confidence: 0.599, decision: "INCONCLUSIVE" },
    { confidence: 0.6, decision: "PASS" },
  ])(
    "keeps the existing confidence boundary at $confidence",
    async ({ confidence, decision }) => {
      const output = { ...candidate, confidence };
      const application = new EvaluatePatternIndependent({
        generate: async () => output,
      });

      await expect(application.execute(command)).resolves.toEqual({
        ...output,
        decision,
      });
    },
  );

  it("does not accept text-model PASS after acoustic evidence overwrites pronunciation as unintelligible", async () => {
    // Exact boundary produced by the local audio adapter: sufficiently confident
    // English observation may set this criterion false without changing decision.
    // This is an isolated provider result, not a real recording or AI call.
    const acousticEvidence = {
      language: "en",
      confidence: 0.92,
      intelligible: false,
    };
    const output = {
      ...candidate,
      pronunciationIntelligible:
        acousticEvidence.language === "en" &&
        acousticEvidence.confidence >= 0.6 &&
        acousticEvidence.intelligible,
    };
    const application = new EvaluatePatternIndependent({
      generate: async () => output,
    });

    await expect(application.execute(command)).resolves.toEqual({
      ...output,
      decision: "INCONCLUSIVE",
    });
  });
});

const candidate = {
  decision: "PASS",
  structurePreserved: true,
  contentSuitable: true,
  pronunciationIntelligible: true,
  feedbackZh: "句式使用自然，发音清楚。",
  confidence: 0.91,
};
const command = {
  accountId: "a1",
  chapterId: "c1",
  patternId: "p1",
  exampleId: "e1",
  targetResponseEn: "I used to walk here.",
  targetStructure: "used to + verb",
  targetHidden: true as const,
  answerAudioPlayed: false as const,
  helpUsed: false as const,
  audioMimeType: "audio/mp4" as const,
  audioBase64: "YWJj",
  commandId: "cmd",
};
