import { describe, expect, it, vi } from "vitest";
import { HttpLearningExplanationProvider } from "./http-learning-explanation-provider.js";

const command = {
  accountId: "test-account",
  commandId: "new-attempt",
  chapter: {
    chapterId: "c",
    sentences: [
      { sentenceId: "s", textEn: "I work.", textZh: "我工作。", isFocus: true },
    ],
  },
  focusSentenceId: "s",
  round: 1,
  requestedExpression: { expressionId: "word-2", display: "work" },
  settings: {
    formalityLevel: 3,
    slangEnabled: false,
    basicVocabulary: "DAILY_CORE",
    purposeVocabularies: [],
    styleReference: "NATURAL_CONCISE",
  },
};

describe("word explanation retry billing metadata", () => {
  it.each([undefined, "original-command"])(
    "preserves attempt identity and groups only explicit retries: %s",
    async (retryOfCommandId) => {
      const fetcher = vi
        .fn<typeof fetch>()
        .mockResolvedValue(
          new Response(JSON.stringify({ kind: "EXPRESSION" }), { status: 200 }),
        );
      const provider = new HttpLearningExplanationProvider(
        { endpoint: "https://provider.test", apiKey: "test" },
        fetcher,
      );
      await provider.generate({
        ...command,
        ...(retryOfCommandId ? { retryOfCommandId } : {}),
      });
      const headers = new Headers(fetcher.mock.calls[0]?.[1]?.headers);
      expect(headers.get("Idempotency-Key")).toBe("new-attempt");
      expect(headers.get("X-RealLanguage-Account-Id")).toBe("test-account");
      expect(headers.get("X-RealLanguage-Call-Type")).toBe(
        retryOfCommandId ? "USER_RETRY" : null,
      );
      expect(headers.get("X-RealLanguage-Operation-Id")).toBe(
        retryOfCommandId ?? null,
      );
      expect(fetcher).toHaveBeenCalledTimes(1);
    },
  );
});
