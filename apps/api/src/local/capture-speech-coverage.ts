import type { RecordingAudioWindow } from "./recording-audio-windows.js";
import type { TimedRecording } from "./recording-time-evidence.js";

/** Recover speech that a whole-window recognition skipped, including deliberately repeated phrases. */
export function splitUncoveredCapture(
  window: RecordingAudioWindow,
  evidence: TimedRecording,
): RecordingAudioWindow[] {
  if (window.durationSeconds < 6) return [];
  // recordingAudioWindows has already validated and normalized this exact 16-bit PCM header.
  const source = Buffer.from(window.audioBase64, "base64");
  const rate = source.readUInt32LE(24);
  const alignment = source.readUInt16LE(32);
  const frames = (source.length - 44) / alignment;
  const block = Math.max(1, Math.round(rate * 0.02));
  const pauses: number[] = [];
  let quietFrom = -1;
  let missedSound = 0;
  let word = 0;
  for (let start = 0; start < frames; start += block) {
    const end = Math.min(frames, start + block);
    let energy = 0;
    for (let i = 44 + start * alignment; i < 44 + end * alignment; i += 2) {
      const sample = source.readInt16LE(i);
      energy += sample * sample;
    }
    const meanSquare = energy / (((end - start) * alignment) / 2);
    if (meanSquare <= 12 ** 2) {
      if (quietFrom < 0) quietFrom = start;
    } else {
      if (quietFrom >= 0 && start - quietFrom >= rate * 0.12)
        pauses.push(Math.round((quietFrom + start) / 2));
      quietFrom = -1;
      const seconds = (start + end) / (2 * rate);
      while (
        word < evidence.words.length &&
        evidence.words[word]!.end + 0.3 < seconds
      )
        word++;
      const nearest = evidence.words[word];
      if (
        meanSquare >= 80 ** 2 &&
        (!nearest ||
          nearest.start - 0.3 > seconds ||
          nearest.end - nearest.start > 3)
      ) {
        missedSound += end - start;
      }
    }
  }
  if (missedSound < rate * 1.2) return [];
  const cuts = [0];
  for (const pause of pauses) {
    if (pause - cuts.at(-1)! >= rate * 3.2 && frames - pause >= rate * 1.2)
      cuts.push(pause);
  }
  if (cuts.length === 1) return [];
  cuts.push(frames);
  return cuts.slice(1).map((end, index) => {
    const start = cuts[index]!;
    const bytes = source.subarray(44 + start * alignment, 44 + end * alignment);
    const header = Buffer.from(source.subarray(0, 44));
    header.writeUInt32LE(36 + bytes.length, 4);
    header.writeUInt32LE(bytes.length, 40);
    return {
      startSeconds: window.startSeconds + start / rate,
      durationSeconds: (end - start) / rate,
      audioBase64: Buffer.concat([header, bytes]).toString("base64"),
    };
  });
}
