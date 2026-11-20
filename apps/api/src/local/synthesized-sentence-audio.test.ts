import { describe, expect, it, vi } from "vitest";
import { measureSentenceWav, receiveSentenceAudio } from "./synthesized-sentence-audio.js";

function wave() {
  const bytes = Buffer.alloc(44 + 48000);
  bytes.write("RIFF", 0); bytes.writeUInt32LE(bytes.length - 8, 4); bytes.write("WAVEfmt ", 8);
  bytes.writeUInt32LE(16, 16); bytes.writeUInt16LE(1, 20); bytes.writeUInt16LE(1, 22);
  bytes.writeUInt32LE(24000, 24); bytes.writeUInt32LE(48000, 28); bytes.writeUInt16LE(2, 32); bytes.writeUInt16LE(16, 34);
  bytes.write("data", 36); bytes.writeUInt32LE(48000, 40);
  return bytes;
}
describe("synthesized waveform ingestion", () => {
  it("measures actual audio bytes", () => { expect(measureSentenceWav(wave())).toBe(1000); });
  it.each(["truncated", "rate", "empty"])("rejects %s audio", (kind) => {
    let bytes = wave();
    if (kind === "truncated") bytes = bytes.subarray(0, 45);
    if (kind === "rate") bytes.writeUInt32LE(1, 28);
    if (kind === "empty") bytes.writeUInt32LE(0, 40);
    expect(() => measureSentenceWav(bytes)).toThrow();
  });
  it("receives bytes without returning signed provider URL", async () => {
    const fetcher = vi.fn().mockResolvedValue(new Response(wave()));
    const result = await receiveSentenceAudio({ downloadUrl: "https://result.oss-cn-beijing.aliyuncs.com/audio.wav", expiresAtSeconds: Date.now() / 1000 + 300,
      providerRequestId: "isolated", characters: 10 }, fetcher);
    expect(result.durationMs).toBe(1000);
    expect(result).not.toHaveProperty("downloadUrl");
    expect(fetcher.mock.calls[0]![1].redirect).toBe("error");
  });
});
