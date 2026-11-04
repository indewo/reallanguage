import { describe, expect, it, vi } from "vitest";
import { recordingAudioWindows } from "./recording-audio-windows.js";
import { expandRecordingWindowContext } from "./recording-window-context.js";
import { OpenAiLocalClient } from "./openai-metered-provider.js";

const coreBytes = (
  window: ReturnType<typeof recordingAudioWindows>[number],
  channels = 1,
) =>
  Buffer.from(window.audioBase64, "base64").subarray(
    44 +
      Math.round(
        ((window.coreStartSeconds ?? window.startSeconds) -
          window.startSeconds) *
          8000,
      ) *
        channels *
        2,
    44 +
      Math.round(
        ((window.coreEndSeconds ??
          window.startSeconds + window.durationSeconds) -
          window.startSeconds) *
          8000,
      ) *
        channels *
        2,
  );

const waveform = (
  seconds: number,
  silent: readonly [number, number][] = [],
  channels = 1,
) => {
  const sampleRate = 8000;
  const frames = seconds * sampleRate;
  const bytes = Buffer.alloc(44 + frames * channels * 2);
  bytes.write("RIFF");
  bytes.writeUInt32LE(bytes.length - 8, 4);
  bytes.write("WAVEfmt ", 8);
  bytes.writeUInt32LE(16, 16);
  bytes.writeUInt16LE(1, 20);
  bytes.writeUInt16LE(channels, 22);
  bytes.writeUInt32LE(sampleRate, 24);
  bytes.writeUInt32LE(sampleRate * channels * 2, 28);
  bytes.writeUInt16LE(channels * 2, 32);
  bytes.writeUInt16LE(16, 34);
  bytes.write("data", 36);
  bytes.writeUInt32LE(bytes.length - 44, 40);
  for (let frame = 0; frame < frames; frame++) {
    if (
      silent.some(
        ([start, end]) =>
          frame >= start * sampleRate && frame < end * sampleRate,
      )
    )
      continue;
    for (let channel = 0; channel < channels; channel++) {
      // Even a one-bit quiet signal is evidence and cannot be discarded as silence.
      bytes.writeInt16LE(
        channel === channels - 1 ? (frame % 2 === 0 ? 1 : -1) : 0,
        44 + (frame * channels + channel) * 2,
      );
    }
  }
  return bytes;
};

describe("lossless recording recognition windows", () => {
  it("caps acoustic re-observation at two distinct windows for the whole recording", async () => {
    const original = waveform(40),
      windows = recordingAudioWindows(original.toString("base64"));
    expect(windows).toHaveLength(4);
    let calls = 0;
    const fetcher = vi.fn(async () => {
      const call = calls++,
        index = call < 4 ? call : call - 3;
      const original = windows[index]!,
        window =
          call < 4 ? original : expandRecordingWindowContext(windows, index);
      const coreStart = original.coreStartSeconds ?? original.startSeconds;
      const words = [
        ...(call < 4 && index > 0
          ? [
              {
                word: `edge${index}`,
                start: coreStart - 0.8,
                end: coreStart - 0.4,
              },
            ]
          : []),
        { word: `core${index}`, start: coreStart + 1, end: coreStart + 1.5 },
      ];
      return new Response(
        JSON.stringify({
          text: words.map((w) => w.word).join(" "),
          language: "en",
          duration: window.durationSeconds,
          words: words.map((w) => ({
            ...w,
            start: w.start - window.startSeconds,
            end: w.end - window.startSeconds,
          })),
        }),
      );
    });
    const client = new OpenAiLocalClient(
      "isolated-key",
      "unused",
      fetcher,
      vi.fn().mockResolvedValue(original.toString("base64")),
    );
    await expect(
      client.transcribeTimed("b3JpZ2luYWw=", "audio/mp4"),
    ).rejects.toThrow(/context|boundary|seam/);
    expect(calls).toBe(6);
  });
  it.each([false, true])(
    "recovers only the conflicting window with bounded original context; repeated disagreement=%s",
    async (stillWrong) => {
      const original = waveform(20),
        windows = recordingAudioWindows(original.toString("base64"));
      const expanded = expandRecordingWindowContext(windows, 1);
      const requests: FormData[] = [];
      const fetcher = vi.fn(async (_url, options) => {
        requests.push(options.body as FormData);
        const i = requests.length - 1;
        const window = i >= 2 ? expanded : windows[i]!;
        const words =
          i === 0
            ? [{ word: "end", start: 8.6, end: 9.3 }]
            : i === 1 || stillWrong
              ? [
                  { word: "wrong", start: 9, end: 9.7 },
                  { word: "after", start: 10.5, end: 11.2 },
                ]
              : [
                  { word: "end", start: 8.6, end: 9.3 },
                  { word: "after", start: 10.5, end: 11.2 },
                ];
        return new Response(
          JSON.stringify({
            text: words.map((w) => w.word).join(" "),
            language: "en",
            duration: window.durationSeconds,
            words: words.map((w) => ({
              ...w,
              start: w.start - window.startSeconds,
              end: w.end - window.startSeconds,
            })),
          }),
        );
      });
      const client = new OpenAiLocalClient(
        "isolated-key",
        "unused",
        fetcher as typeof fetch,
        vi.fn().mockResolvedValue(original.toString("base64")),
      );
      const result = client.transcribeTimed("b3JpZ2luYWw=", "audio/mp4");
      if (stillWrong)
        await expect(result).rejects.toThrow(/context|boundary|seam/);
      else
        await expect(result).resolves.toMatchObject({
          text: "end after",
          words: [
            { word: "end", start: 8.6, end: 9.3 },
            { word: "after", start: 10.5, end: 11.2 },
          ],
        });
      expect(requests).toHaveLength(3);
      const file = requests[2]!.get("file") as File;
      expect(Buffer.from(await file.arrayBuffer())).toEqual(
        Buffer.from(expanded.audioBase64, "base64"),
      );
    },
  );
  it("separates long digital silence without dropping the late incomplete tail or changing original time", () => {
    const original = waveform(120, [
      [24, 24.5],
      [49, 49.5],
      [61, 118.5],
    ]);
    const windows = recordingAudioWindows(original.toString("base64"));
    expect(windows.length).toBeGreaterThan(1);
    expect(windows.length).toBeLessThanOrEqual(8);
    expect(windows.at(-1)!.startSeconds).toBeGreaterThan(115);
    expect(windows.at(-1)!.startSeconds + windows.at(-1)!.durationSeconds).toBe(
      120,
    );
    expect(
      windows.reduce(
        (sum, value) =>
          sum +
          ((value.coreEndSeconds ??
            value.startSeconds + value.durationSeconds) -
            (value.coreStartSeconds ?? value.startSeconds)),
        0,
      ),
    ).toBeLessThan(70);
    let previousEnd = 0;
    for (const window of windows) {
      const coreStart = window.coreStartSeconds ?? window.startSeconds;
      const coreEnd =
        window.coreEndSeconds ?? window.startSeconds + window.durationSeconds;
      expect(coreStart).toBeGreaterThanOrEqual(previousEnd);
      const bytes = Buffer.from(window.audioBase64, "base64");
      const start = Math.round(window.startSeconds * 8000);
      const end = Math.round(
        (window.startSeconds + window.durationSeconds) * 8000,
      );
      expect(bytes.subarray(44)).toEqual(
        original.subarray(44 + start * 2, 44 + end * 2),
      );
      const omitted = original.subarray(
        44 + Math.round(previousEnd * 8000) * 2,
        44 + Math.round(coreStart * 8000) * 2,
      );
      expect(omitted.every((byte) => byte === 0)).toBe(true);
      previousEnd = coreEnd;
    }
  });

  it("keeps every continuous quiet sample while bounding padded acoustic context, not semantic units", () => {
    const original = waveform(120);
    const windows = recordingAudioWindows(original.toString("base64"));
    expect(windows.length).toBeGreaterThan(1);
    expect(windows.length).toBeLessThanOrEqual(16);
    expect(windows.every((window) => window.durationSeconds <= 12)).toBe(true);
    expect(Buffer.concat(windows.map((window) => coreBytes(window)))).toEqual(
      original.subarray(44),
    );
  });

  it("limits recognition context at measured pauses without turning pauses into semantic sentences", () => {
    const gaps: [number, number][] = Array.from({ length: 23 }, (_, index) => [
      (index + 1) * 5 - 0.5,
      (index + 1) * 5,
    ]);
    const original = waveform(120, gaps);
    const windows = recordingAudioWindows(original.toString("base64"));
    expect(windows.length).toBeGreaterThan(8);
    expect(windows.length).toBeLessThanOrEqual(16);
    expect(windows.every((window) => window.durationSeconds <= 12)).toBe(true);
    expect(
      Buffer.concat(
        windows.map((window) =>
          Buffer.from(window.audioBase64, "base64").subarray(44),
        ),
      ),
    ).toEqual(original.subarray(44));
  });

  it("shares one bounded transcription deadline across windows and never starts another call after timeout", async () => {
    vi.useFakeTimers();
    try {
      const original = waveform(80, [
        [20, 35],
        [55, 70],
      ]);
      const fetcher = vi.fn(async (_url, options) => {
        await vi.advanceTimersByTimeAsync(91_000);
        (options.signal as AbortSignal).throwIfAborted();
        return new Response(
          JSON.stringify({
            text: "你好",
            language: "chinese",
            duration: 21,
            words: [{ word: "你好", start: 1, end: 2 }],
          }),
        );
      });
      const client = new OpenAiLocalClient(
        "isolated-key",
        "unused",
        fetcher as typeof fetch,
        vi.fn().mockResolvedValue(original.toString("base64")),
      );
      await expect(
        client.transcribeTimed("b3JpZ2luYWw=", "audio/mp4"),
      ).rejects.toThrow();
      expect(fetcher).toHaveBeenCalledTimes(1);
    } finally {
      vi.useRealTimers();
    }
  });

  it("does not treat a silent stereo channel as silence in the other channel", () => {
    const original = waveform(40, [], 2);
    const windows = recordingAudioWindows(original.toString("base64"));
    expect(windows.length).toBeGreaterThan(1);
    expect(windows.every((window) => window.durationSeconds <= 12)).toBe(true);
    expect(
      Buffer.concat(windows.map((window) => coreBytes(window, 2))),
    ).toEqual(original.subarray(44));
  });

  it("rejects malformed PCM before a supplier call", () => {
    expect(() =>
      recordingAudioWindows(Buffer.from("not-a-wave").toString("base64")),
    ).toThrow();
    const float = waveform(2);
    float.writeUInt16LE(3, 20);
    expect(() => recordingAudioWindows(float.toString("base64"))).toThrow();
    const truncated = waveform(2).subarray(0, 100);
    expect(() => recordingAudioWindows(truncated.toString("base64"))).toThrow();
  });

  it("rejects fully silent original PCM instead of inviting hallucinated speech", () => {
    expect(() =>
      recordingAudioWindows(waveform(120, [[0, 120]]).toString("base64")),
    ).toThrow("silent");
  });

  it("does not submit silence-only windows before the first or after the last audible sample", () => {
    const original = waveform(40, [
      [0, 8],
      [20, 40],
    ]);
    const windows = recordingAudioWindows(original.toString("base64"));
    expect(windows.length).toBeGreaterThan(1);
    expect(windows.every((window) => window.durationSeconds <= 12)).toBe(true);
    expect(Buffer.concat(windows.map((window) => coreBytes(window)))).toEqual(
      original.subarray(44 + 7 * 16000, 44 + 21 * 16000),
    );
  });

  it("rejects an inconsistent RIFF size or dangling bytes instead of silently losing evidence", () => {
    const wrongSize = waveform(2);
    wrongSize.writeUInt32LE(36, 4);
    expect(() => recordingAudioWindows(wrongSize.toString("base64"))).toThrow();
    const dangling = Buffer.concat([waveform(2), Buffer.from([1, 2, 3])]);
    dangling.writeUInt32LE(dangling.length - 8, 4);
    expect(() => recordingAudioWindows(dangling.toString("base64"))).toThrow();
  });

  it("does not accept a word outside its original audio window or retry the paid subcall", async () => {
    const original = waveform(40, [[20, 35]]);
    const fetcher = vi.fn().mockResolvedValue(
      new Response(
        JSON.stringify({
          text: "你好。",
          language: "chinese",
          duration: 21,
          words: [{ word: "你好", start: 20, end: 30 }],
        }),
      ),
    );
    const client = new OpenAiLocalClient(
      "isolated-key",
      "unused",
      fetcher,
      vi.fn().mockResolvedValue(original.toString("base64")),
    );
    await expect(
      client.transcribeTimed("b3JpZ2luYWw=", "audio/mp4"),
    ).rejects.toThrow();
    expect(fetcher).toHaveBeenCalledTimes(1);
  });

  it("transcribes measured subclips and offsets every word back to the original recording", async () => {
    const original = waveform(40, [[20, 35]]);
    const windows = recordingAudioWindows(original.toString("base64"));
    const requests: FormData[] = [];
    const fetcher = vi.fn(async (_url, options) => {
      requests.push(options.body as FormData);
      const window = windows[requests.length - 1]!;
      return new Response(
        JSON.stringify({
          text: requests.length === 1 ? "你好。" : "再见。",
          language: "chinese",
          duration: window.durationSeconds,
          words: [
            { word: requests.length === 1 ? "你好" : "再见", start: 1, end: 2 },
          ],
        }),
      );
    });
    const client = new OpenAiLocalClient(
      "isolated-key",
      "unused",
      fetcher as typeof fetch,
      vi.fn().mockResolvedValue(original.toString("base64")),
    );
    const result = (await client.transcribeTimed(
      "b3JpZ2luYWw=",
      "audio/mp4",
    )) as { text: string; words: { start: number; end: number }[] };
    expect(requests).toHaveLength(windows.length);
    for (const [index, form] of requests.entries()) {
      const file = form.get("file") as File;
      expect(file.type).toBe("audio/wav");
      expect(form.get("language")).toBe("zh");
      expect(Buffer.from(await file.arrayBuffer())).toEqual(
        Buffer.from(windows[index]!.audioBase64, "base64"),
      );
      expect(result.words[index]).toMatchObject({
        start: windows[index]!.startSeconds + 1,
        end: windows[index]!.startSeconds + 2,
      });
    }
    expect(result.text).toBe(
      windows.map((_, i) => (i === 0 ? "你好。" : "再见。")).join(" "),
    );
  });

  it("passes original-time core ownership through the production timed client without deleting spoken repetitions", async () => {
    const original = waveform(20);
    const windows = recordingAudioWindows(original.toString("base64"));
    expect(windows).toHaveLength(2);
    const words = [
      { word: "today", start: 9.7, end: 10.15 },
      { word: "today", start: 10.6, end: 10.9 },
      { word: "done", start: 18, end: 18.3 },
    ];
    let call = 0;
    const fetcher = vi.fn(async () => {
      const window = windows[call++]!;
      const contained = words.filter(
        (word) =>
          word.start >= window.startSeconds &&
          word.end <= window.startSeconds + window.durationSeconds,
      );
      return new Response(
        JSON.stringify({
          text: contained.map((word) => word.word).join(" "),
          language: "en",
          duration: window.durationSeconds,
          words: contained.map((word) => ({
            ...word,
            start: word.start - window.startSeconds,
            end: word.end - window.startSeconds,
          })),
        }),
      );
    });
    const client = new OpenAiLocalClient(
      "isolated-key",
      "unused",
      fetcher,
      vi.fn().mockResolvedValue(original.toString("base64")),
    );
    const result = await client.transcribeTimed("b3JpZ2luYWw=", "audio/mp4");
    expect(result).toMatchObject({
      text: "today today done",
      words,
      uncertainSpans: [{ start: 9, end: 11 }],
    });
    expect(fetcher).toHaveBeenCalledTimes(2);
  });
});
