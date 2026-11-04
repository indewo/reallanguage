import { describe, expect, it } from "vitest";
import {
  recordingAudioWindows,
  type RecordingAudioWindow,
} from "./recording-audio-windows.js";

// Constructed PCM proves byte/time ownership, not human speech or ASR quality.
const rate = 8000;
function pcm(
  seconds: number,
  sample: (frame: number, channel: number) => number,
  channels = 1,
) {
  const wave = Buffer.alloc(44 + Math.round(seconds * rate) * channels * 2);
  wave.write("RIFF");
  wave.writeUInt32LE(wave.length - 8, 4);
  wave.write("WAVEfmt ", 8);
  wave.writeUInt32LE(16, 16);
  wave.writeUInt16LE(1, 20);
  wave.writeUInt16LE(channels, 22);
  wave.writeUInt32LE(rate, 24);
  wave.writeUInt32LE(rate * channels * 2, 28);
  wave.writeUInt16LE(channels * 2, 32);
  wave.writeUInt16LE(16, 34);
  wave.write("data", 36);
  wave.writeUInt32LE(wave.length - 44, 40);
  for (let frame = 0; frame < (wave.length - 44) / (channels * 2); frame++) {
    for (let channel = 0; channel < channels; channel++) {
      wave.writeInt16LE(
        sample(frame, channel),
        44 + (frame * channels + channel) * 2,
      );
    }
  }
  return wave;
}

function assertOriginalBytesCovered(
  original: Buffer,
  windows: readonly RecordingAudioWindow[],
  channels = 1,
) {
  const alignment = channels * 2;
  const frames = (original.length - 44) / alignment;
  const covered = new Uint8Array(frames);
  let previousStart = -1;
  for (const window of windows) {
    expect(window.startSeconds).toBeGreaterThanOrEqual(previousStart);
    expect(window.durationSeconds).toBeGreaterThan(0);
    expect(window.durationSeconds).toBeLessThanOrEqual(12);
    previousStart = window.startSeconds;
    const start = Math.round(window.startSeconds * rate);
    const end = start + Math.round(window.durationSeconds * rate);
    expect(Buffer.from(window.audioBase64, "base64").subarray(44)).toEqual(
      original.subarray(44 + start * alignment, 44 + end * alignment),
    );
    covered.fill(1, start, end);
  }
  let uncoveredNonzeroSamples = 0;
  for (let frame = 0; frame < frames; frame++) {
    if (covered[frame] === 1) continue;
    for (let channel = 0; channel < channels; channel++) {
      if (original.readInt16LE(44 + frame * alignment + channel * 2) !== 0) {
        uncoveredNonzeroSamples++;
      }
    }
  }
  expect(uncoveredNonzeroSamples).toBe(0);
}

function assertPaddedCoresCoverOriginal(
  windows: readonly RecordingAudioWindow[],
  seconds: number,
) {
  let end = 0;
  for (const window of windows) {
    expect(window.coreStartSeconds).toBe(end);
    expect(window.coreEndSeconds).toBeGreaterThan(end);
    // Exact sample counts avoid epsilon from subtracting rational second values.
    expect(
      Math.round(window.coreEndSeconds! * rate) -
        Math.round(window.coreStartSeconds! * rate),
    ).toBeLessThanOrEqual(10 * rate);
    expect(window.startSeconds).toBe(Math.max(0, window.coreStartSeconds! - 1));
    expect(window.startSeconds + window.durationSeconds).toBeCloseTo(
      Math.min(seconds, window.coreEndSeconds! + 1),
      8,
    );
    end = window.coreEndSeconds!;
  }
  expect(end).toBe(seconds);
}

describe("bounded recording cores without digital-zero pauses", () => {
  it("does not leave 120 seconds of speech-shaped PCM with a nonzero noise floor in one request", () => {
    const original = pcm(120, (frame) => {
      const withinTen = (frame / rate) % 10;
      const magnitude = withinTen >= 8 && withinTen < 8.4 ? 2 : 300;
      return frame % 2 === 0 ? magnitude : -magnitude;
    });
    const windows = recordingAudioWindows(original.toString("base64"));
    expect(windows.length).toBeGreaterThan(1);
    expect(windows.length).toBeLessThanOrEqual(16);
    assertOriginalBytesCovered(original, windows);
    assertPaddedCoresCoverOriginal(windows, 120);
  });

  it("keeps every one-bit sample and the 121-second tail, preferring the 10-second cut on equal energy", () => {
    const original = pcm(121, (frame) => (frame % 2 === 0 ? 1 : -1));
    const windows = recordingAudioWindows(original.toString("base64"));
    expect(windows).toHaveLength(13);
    expect(windows[0]).toMatchObject({
      startSeconds: 0,
      durationSeconds: 11,
      coreStartSeconds: 0,
      coreEndSeconds: 10,
    });
    assertOriginalBytesCovered(original, windows);
    assertPaddedCoresCoverOriginal(windows, 121);
    expect(
      Buffer.from(windows.at(-1)!.audioBase64, "base64").readInt16LE(
        Buffer.from(windows.at(-1)!.audioBase64, "base64").length - 2,
      ),
    ).toBe(-1);
  });

  it("selects a 20ms low-energy boundary across both channels, not the quiet channel alone", () => {
    const original = pcm(
      24,
      (frame, channel) => {
        if (frame >= 8.8 * rate && frame < 8.82 * rate) return 1;
        if (frame >= 8.2 * rate && frame < 8.4 * rate && channel === 0)
          return 0;
        return channel === 0 ? 40 : 400;
      },
      2,
    );
    const windows = recordingAudioWindows(original.toString("base64"));
    expect(windows[0]!.coreEndSeconds).toBe(8.82);
    assertOriginalBytesCovered(original, windows, 2);
    assertPaddedCoresCoverOriginal(windows, 24);
  });

  it("never discards quiet one-sided stereo evidence as silence", () => {
    const original = pcm(
      40,
      (frame, channel) => (channel === 0 ? 0 : frame % 2 === 0 ? 1 : -1),
      2,
    );
    const windows = recordingAudioWindows(original.toString("base64"));
    expect(windows).toHaveLength(4);
    assertOriginalBytesCovered(original, windows, 2);
    assertPaddedCoresCoverOriginal(windows, 40);
  });

  it("retains context and core ownership at a 20ms zero closure that is not a measured pause", () => {
    const original = pcm(120, (frame) =>
      frame >= 9.48 * rate && frame < 9.5 * rate ? 0 : 1,
    );
    const windows = recordingAudioWindows(original.toString("base64"));
    expect(windows[0]).toMatchObject({
      startSeconds: 0,
      durationSeconds: 10.5,
      coreStartSeconds: 0,
      coreEndSeconds: 9.5,
    });
    expect(windows[1]).toMatchObject({
      startSeconds: 8.5,
      coreStartSeconds: 9.5,
    });
    expect(windows.length).toBeLessThanOrEqual(16);
    assertOriginalBytesCovered(original, windows);
    assertPaddedCoresCoverOriginal(windows, 120);
  });

  it("preserves the existing measured-zero split offsets and bytes without adding core metadata", () => {
    const original = pcm(120, (frame) => {
      const seconds = frame / rate;
      return seconds < 115 && seconds % 5 >= 4.5 ? 0 : 1;
    });
    const windows = recordingAudioWindows(original.toString("base64"));
    expect(windows.map((window) => window.startSeconds)).toEqual([
      0, 9.75, 19.75, 29.75, 39.75, 49.75, 59.75, 69.75, 79.75, 89.75, 99.75,
      109.75,
    ]);
    expect(
      windows.every(
        (window) =>
          window.coreStartSeconds === undefined &&
          window.coreEndSeconds === undefined,
      ),
    ).toBe(true);
    expect(
      Buffer.concat(
        windows.map((window) =>
          Buffer.from(window.audioBase64, "base64").subarray(44),
        ),
      ),
    ).toEqual(original.subarray(44));
  });

  it("does not expand a neighbouring legacy zero window or lose the late incomplete-tail bytes", () => {
    const original = pcm(120, (frame) => {
      const seconds = frame / rate;
      return (seconds >= 11.5 && seconds < 12) ||
        (seconds >= 30 && seconds < 118.5)
        ? 0
        : 1;
    });
    const windows = recordingAudioWindows(original.toString("base64"));
    expect(windows[0]).toMatchObject({
      startSeconds: 0,
      durationSeconds: 11.75,
    });
    expect(windows[0]!.coreStartSeconds).toBeUndefined();
    expect(windows.at(-1)).toMatchObject({
      startSeconds: 117.5,
      durationSeconds: 2.5,
    });
    expect(windows.at(-1)!.coreStartSeconds).toBeUndefined();
    assertOriginalBytesCovered(original, windows);
  });

  it("bounds fragmented speech to 16 requests without merging into a long request", () => {
    const original = pcm(121, (frame) => ((frame / rate) % 3.2 < 0.05 ? 1 : 0));
    const windows = recordingAudioWindows(original.toString("base64"));
    expect(windows.length).toBeLessThanOrEqual(16);
    assertOriginalBytesCovered(original, windows);
  });

  it("still rejects an entirely digitally silent recording", () => {
    expect(() =>
      recordingAudioWindows(pcm(120, () => 0).toString("base64")),
    ).toThrow("digitally silent");
  });
});
