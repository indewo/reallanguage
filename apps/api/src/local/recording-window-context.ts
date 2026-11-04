import type { RecordingAudioWindow } from "./recording-audio-windows.js";

export function expandRecordingWindowContext(
  windows: readonly RecordingAudioWindow[],
  index: number,
): RecordingAudioWindow {
  const current = windows[index];
  if (!Number.isInteger(index) || !current)
    throw new Error("Invalid recording context target");
  const previous = windows[index - 1],
    next = windows[index + 1];
  const start =
    previous &&
    previous.startSeconds + previous.durationSeconds >= current.startSeconds
      ? Math.max(previous.startSeconds, current.startSeconds - 2)
      : current.startSeconds;
  const end =
    next && next.startSeconds <= current.startSeconds + current.durationSeconds
      ? Math.min(
          next.startSeconds + next.durationSeconds,
          current.startSeconds + current.durationSeconds + 2,
        )
      : current.startSeconds + current.durationSeconds;
  if (end - start > 16 + 1e-8)
    throw new Error("Recording context budget exceeded");
  const template = Buffer.from(current.audioBase64, "base64"),
    format = template.subarray(20, 36);
  const rate = template.readUInt32LE(24),
    alignment = template.readUInt16LE(32);
  let cursor = Math.round(start * rate);
  const final = Math.round(end * rate),
    chunks: Buffer[] = [];
  for (const source of windows) {
    const a = Math.round(source.startSeconds * rate),
      b = Math.round((source.startSeconds + source.durationSeconds) * rate);
    if (a > cursor || b <= cursor || cursor >= final) continue;
    const bytes = Buffer.from(source.audioBase64, "base64");
    if (
      !bytes.subarray(20, 36).equals(format) ||
      bytes.toString("ascii", 36, 40) !== "data" ||
      bytes.length !== 44 + (b - a) * alignment
    )
      throw new Error("Incompatible original recording context");
    const until = Math.min(b, final);
    chunks.push(
      bytes.subarray(
        44 + (cursor - a) * alignment,
        44 + (until - a) * alignment,
      ),
    );
    cursor = until;
  }
  if (cursor !== final)
    throw new Error("Missing original recording context samples");
  const body = Buffer.concat(chunks),
    bytes = Buffer.alloc(44 + body.length);
  template.copy(bytes, 0, 0, 44);
  bytes.writeUInt32LE(bytes.length - 8, 4);
  bytes.writeUInt32LE(body.length, 40);
  body.copy(bytes, 44);
  return {
    ...current,
    startSeconds: start,
    durationSeconds: end - start,
    audioBase64: bytes.toString("base64"),
  };
}
