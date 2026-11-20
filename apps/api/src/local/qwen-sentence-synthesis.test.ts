import { describe, expect, it, vi } from "vitest";
import { QwenSentenceSynthesis } from "./qwen-sentence-synthesis.js";

const artifact = () => ({ request_id: "provider-request", output: { finish_reason: "stop", audio: {
  url: "https://dashscope-result-bj.oss-cn-beijing.aliyuncs.com/test.wav", expires_at: 200,
} }, usage: { characters: 24 } });

describe("whole sentence synthesis", () => {
  it("sends exact punctuation and contractions once and keeps provider expiry", async () => {
    const fetcher = vi.fn().mockResolvedValue(new Response(JSON.stringify(artifact())));
    const result = await new QwenSentenceSynthesis("isolated-key", fetcher, () => 100).synthesize("I'd like to learn English—mainly for school.");
    expect(fetcher).toHaveBeenCalledTimes(1);
    const body = JSON.parse(fetcher.mock.calls[0]![1].body);
    expect(body.input.text).toBe("I'd like to learn English—mainly for school.");
    expect(body.input.language_type).toBe("English");
    expect(result.expiresAtSeconds).toBe(200);
    expect(result).not.toHaveProperty("wordTimings");
  });
  it("does not replay a failed provider request", async () => {
    const fetcher = vi.fn().mockResolvedValue(new Response("", { status: 500 }));
    await expect(new QwenSentenceSynthesis("isolated-key", fetcher).synthesize("Hello.")).rejects.toThrow("500");
    expect(fetcher).toHaveBeenCalledTimes(1);
  });
  it.each(["expired", "unfinished", "untrusted"])("rejects %s artifacts", async (kind) => {
    const value = artifact();
    if (kind === "expired") value.output.audio.expires_at = 99;
    if (kind === "unfinished") value.output.finish_reason = "length";
    if (kind === "untrusted") value.output.audio.url = "https://example.invalid/audio.wav";
    const fetcher = vi.fn().mockResolvedValue(new Response(JSON.stringify(value)));
    await expect(new QwenSentenceSynthesis("isolated-key", fetcher, () => 100).synthesize("Hello.")).rejects.toThrow();
  });
});
