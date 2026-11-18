import { expect, it, vi } from "vitest";
import { OpenAiLocalClient } from "./openai-metered-provider.js";

it("keeps measured Fun-ASR word times and rejects missing evidence", async () => {
  vi.stubEnv("OPENAI_TIMED_TRANSCRIBE_MODEL", "fun-asr-flash-2026-06-15");
  vi.stubEnv("DASHSCOPE_ASR_BASE_URL", "https://asr.test/api/v1");
  const wav = Buffer.alloc(32044);
  for (let i = 0; i < 16000; i++)
    wav.writeInt16LE(Math.round(Math.sin(i * 0.1) * 2000), 44 + i * 2);
  wav.write("RIFF");
  wav.writeUInt32LE(wav.length - 8, 4);
  wav.write("WAVEfmt ", 8);
  wav.writeUInt32LE(16, 16);
  wav.writeUInt16LE(1, 20);
  wav.writeUInt16LE(1, 22);
  wav.writeUInt32LE(16000, 24);
  wav.writeUInt32LE(32000, 28);
  wav.writeUInt16LE(2, 32);
  wav.writeUInt16LE(16, 34);
  wav.write("data", 36);
  wav.writeUInt32LE(32000, 40);
  const event = (words: unknown[]) =>
    `data:${JSON.stringify({ output: { text: "你好。", sentence: { sentence_id: 0, sentence_end: true, words } } })}\n\n`;
  const fetcher = vi
    .fn()
    .mockResolvedValueOnce(
      new Response(event([{ text: "你好", begin_time: 200, end_time: 800 }])),
    )
    .mockResolvedValueOnce(new Response(event([])));
  const client = new OpenAiLocalClient("test", "test", fetcher, async () =>
    wav.toString("base64"),
  );
  try {
    const result = (await client.transcribeTimed(
      "audio",
      "audio/wav",
      "zh-CN",
    )) as { words: unknown[] };
    expect(result.words).toMatchObject([
      { word: "你好", start: 0.2, end: 0.8 },
    ]);
    await expect(
      client.transcribeTimed("audio", "audio/wav", "zh-CN"),
    ).rejects.toThrow();
    expect(fetcher.mock.calls[0]?.[0]).toBe(
      "https://asr.test/api/v1/services/aigc/multimodal-generation/generation",
    );
  } finally {
    vi.unstubAllEnvs();
  }
});
