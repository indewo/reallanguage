import { describe, expect, it } from "vitest";
import { GeneratePatternPracticeFeedback } from "./generate-pattern-practice-feedback.js";

describe("GeneratePatternPracticeFeedback", () => {
  it("returns training-only transcript and objective feedback", async () => {
    const app = new GeneratePatternPracticeFeedback({
      generate: async () => ready,
    });
    await expect(app.execute(command)).resolves.toEqual(ready);
  });
  it("preserves low-confidence as inconclusive without a failure result", async () => {
    const app = new GeneratePatternPracticeFeedback({
      generate: async () => ({ status: "INCONCLUSIVE" }),
    });
    await expect(app.execute(command)).resolves.toEqual({
      status: "INCONCLUSIVE",
    });
  });
  it("rejects provider pass-fail authority", async () => {
    const app = new GeneratePatternPracticeFeedback({
      generate: async () => ({ ...ready, status: "PASS" }),
    });
    await expect(app.execute(command)).rejects.toThrow(
      "Invalid pattern practice feedback output",
    );
  });
});
const ready = {
  status: "READY" as const,
  transcript: "I used to walk.",
  feedbackZh: "结构和发音清楚。",
  repeatRecommended: false,
  confidence: 0.9,
};
const command = {
  accountId: "a1",
  chapterId: "c1",
  patternId: "p1",
  exampleId: "e1",
  mode: "RESPONSE" as const,
  targetTextEn: "I used to walk.",
  targetStructure: "used to + verb",
  targetVisible: true as const,
  audioMimeType: "audio/mp4" as const,
  audioBase64: "YWJj",
  commandId: "cmd",
};
