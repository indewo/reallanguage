import type { SynthesizedSentence } from "./qwen-sentence-synthesis.js";

const MAX_AUDIO_BYTES = 16 * 1024 * 1024;

/** Measure the downloaded waveform, never estimate duration from text length. */
export function measureSentenceWav(bytes: Buffer): number {
  if (bytes.length < 44 || bytes.toString("ascii", 0, 4) !== "RIFF" || bytes.toString("ascii", 8, 12) !== "WAVE" ||
      bytes.readUInt32LE(4) + 8 !== bytes.length) throw new Error(`Invalid synthesized waveform (bytes=${bytes.length}, magic=${bytes.subarray(0, 12).toString("hex")}, declared=${bytes.length >= 8 ? bytes.readUInt32LE(4) : -1})`);
  let byteRate = 0;
  let blockAlign = 0;
  let dataBytes: number | undefined;
  for (let offset = 12; offset < bytes.length;) {
    if (offset + 8 > bytes.length) throw new Error("Truncated waveform chunk");
    const name = bytes.toString("ascii", offset, offset + 4);
    const size = bytes.readUInt32LE(offset + 4);
    const start = offset + 8;
    if (start + size > bytes.length) throw new Error("Truncated waveform data");
    if (name === "fmt ") {
      if (size < 16 || byteRate) throw new Error("Invalid waveform format");
      const encoding = bytes.readUInt16LE(start);
      const channels = bytes.readUInt16LE(start + 2);
      const sampleRate = bytes.readUInt32LE(start + 4);
      byteRate = bytes.readUInt32LE(start + 8);
      blockAlign = bytes.readUInt16LE(start + 12);
      const bits = bytes.readUInt16LE(start + 14);
      if (![1, 3].includes(encoding) || ![1, 2].includes(channels) || sampleRate < 8000 || sampleRate > 192000 ||
          ![8, 16, 24, 32].includes(bits) || (encoding === 3 && bits !== 32) ||
          blockAlign !== channels * bits / 8 || byteRate !== sampleRate * blockAlign) throw new Error("Unsupported waveform format");
    } else if (name === "data") {
      if (dataBytes !== undefined) throw new Error("Duplicate waveform data");
      dataBytes = size;
    }
    offset = start + size + (size % 2);
    if (offset > bytes.length) throw new Error("Missing waveform padding");
  }
  if (!byteRate || !blockAlign || !dataBytes || dataBytes % blockAlign !== 0) throw new Error("Missing waveform audio");
  return Math.ceil(dataBytes * 1000 / byteRate);
}

/** Internal ingestion only; signed provider addresses must not be returned to the app. */
export async function receiveSentenceAudio(artifact: SynthesizedSentence, fetcher: typeof fetch = fetch): Promise<{ bytes: Buffer; durationMs: number }> {
  const url = new URL(artifact.downloadUrl);
  if (!["http:", "https:"].includes(url.protocol) || !url.hostname.endsWith(".aliyuncs.com") || url.username || url.password || url.port ||
      artifact.expiresAtSeconds <= Date.now() / 1000) throw new Error("Invalid synthesis download lease");
  const response = await fetcher(url, { redirect: "error", signal: AbortSignal.timeout(30_000) });
  if (!response.ok || !response.body) throw new Error("Synthesis audio download failed");
  const reader = response.body.getReader();
  const chunks: Buffer[] = [];
  let length = 0;
  try {
    for (;;) {
      const next = await reader.read();
      if (next.done) break;
      length += next.value.length;
      if (length > MAX_AUDIO_BYTES) throw new Error("Synthesized audio exceeds size limit");
      chunks.push(Buffer.from(next.value));
    }
  } finally { await reader.cancel().catch(() => undefined); reader.releaseLock(); }
  const bytes = Buffer.concat(chunks);
  return { bytes, durationMs: measureSentenceWav(bytes) };
}
