import { describe, expect, it, vi } from "vitest";
import { HttpLearningTargetQualityReviewer } from "./http-learning-target-quality-reviewer.js";
import type {
  LearningTargetCandidate,
  LearningTargetProviderCommand,
} from "../application/extract-learning-targets.js";

const target: LearningTargetCandidate = {
  targetId: "t1",
  unitId: "u1",
  display: "happy",
  explanationZh: "高兴的",
  type: "WORD",
  structureParts: [],
  relatedVocabulary: [],
  selectedByDefault: true,
};
const command: LearningTargetProviderCommand = {
  accountId: "private-account",
  commandId: "command",
  settings: {
    formalityLevel: 3,
    slangEnabled: false,
    basicVocabulary: "CET_4",
    purposeVocabularies: [],
    styleReference: "natural",
  },
  chapters: [],
};
const issue = {
  targetId: "t1",
  reason: "EXAMPLE_GRAMMAR",
  explanationZh: "例句介词错误",
};
describe("HttpLearningTargetQualityReviewer", () => {
  it("uses a stable separately metered identity without sending private identity to the model", async () => {
    const fetcher = vi.fn<typeof fetch>(
      async () => new Response(JSON.stringify({ valid: true, issues: [] })),
    );
    expect(
      await new HttpLearningTargetQualityReviewer(
        { endpoint: "https://test.invalid", apiKey: "test" },
        fetcher,
      ).review(command, [target]),
    ).toEqual([]);
    const request = fetcher.mock.calls[0]![1] as RequestInit;
    expect(request.headers).toMatchObject({
      "Idempotency-Key": "command:quality-v1",
      "X-RealLanguage-Operation-Id": "command",
    });
    expect(JSON.parse(String(request.body))).toEqual({
      task: "LEARNING_TARGET_QUALITY_V1",
      input: { chapters: [], targets: [target] },
    });
  });
  it("returns exact rejected target evidence for the bounded correction", async () => {
    const fetcher = vi.fn<typeof fetch>(
      async () =>
        new Response(JSON.stringify({ valid: false, issues: [issue] })),
    );
    expect(
      await new HttpLearningTargetQualityReviewer(
        { endpoint: "https://test.invalid", apiKey: "test" },
        fetcher,
      ).review(command, [target]),
    ).toEqual([issue]);
  });
  it.each([
    { valid: true, issues: [issue] },
    { valid: false, issues: [] },
    { valid: false, issues: [{ ...issue, targetId: "invented" }] },
    { valid: false, issues: [{ ...issue, reason: "UNKNOWN" }] },
    { valid: false, issues: [{ ...issue, explanationZh: " " }] },
  ])("rejects invalid review output %j", async (output) => {
    const fetcher = vi.fn<typeof fetch>(
      async () => new Response(JSON.stringify(output)),
    );
    await expect(
      new HttpLearningTargetQualityReviewer(
        { endpoint: "https://test.invalid", apiKey: "test" },
        fetcher,
      ).review(command, [target]),
    ).rejects.toThrow();
  });
});
