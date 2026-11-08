export type RecordingAudioWindow = Readonly<{
  startSeconds: number;
  durationSeconds: number;
  audioBase64: string;
  /** Absolute original-audio ownership, excluding any recognition context padding. */
  coreStartSeconds?: number;
  coreEndSeconds?: number;
}>;

type FrameWindow = {
  start: number;
  end: number;
  coreStart?: number;
  coreEnd?: number;
};

/** Recognition windows are transport units, never semantic sentence boundaries. */
export function recordingAudioWindows(
  audioBase64: string,
  options: { alreadyWindowed?: boolean } = {},
): readonly RecordingAudioWindow[] {
  const bytes = Buffer.from(audioBase64, "base64");
  if (
    bytes.length < 44 ||
    bytes.length > 8_000_000 ||
    bytes.toString("ascii", 0, 4) !== "RIFF" ||
    bytes.readUInt32LE(4) !== bytes.length - 8 ||
    bytes.toString("ascii", 8, 12) !== "WAVE"
  )
    throw new Error("Invalid recording PCM");
  let format: Buffer | undefined;
  const dataChunks: Buffer[] = [];
  for (let offset = 12; offset < bytes.length;) {
    if (offset + 8 > bytes.length) throw new Error("Dangling recording PCM");
    const name = bytes.toString("ascii", offset, offset + 4);
    const length = bytes.readUInt32LE(offset + 4);
    const start = offset + 8;
    if (start + length + (length % 2) > bytes.length)
      throw new Error("Truncated recording PCM");
    if (name === "fmt ") {
      if (format || length < 16) throw new Error("Ambiguous recording PCM");
      format = bytes.subarray(start, start + length);
    }
    if (name === "data") dataChunks.push(bytes.subarray(start, start + length));
    offset = start + length + (length % 2);
  }
  if (!format || !dataChunks.length) throw new Error("Missing recording PCM");
  const tag = format.readUInt16LE(0);
  const extensible =
    tag === 0xfffe &&
    format.length >= 40 &&
    format.readUInt16LE(16) >= 22 &&
    format.readUInt16LE(18) === 16 &&
    format
      .subarray(24, 40)
      .equals(Buffer.from("0100000000001000800000aa00389b71", "hex"));
  const channels = format.readUInt16LE(2);
  const rate = format.readUInt32LE(4);
  const alignment = format.readUInt16LE(12);
  const data = Buffer.concat(dataChunks);
  if (
    (tag !== 1 && !extensible) ||
    channels < 1 ||
    channels > 2 ||
    rate < 8000 ||
    rate > 192000 ||
    format.readUInt16LE(14) !== 16 ||
    alignment !== channels * 2 ||
    format.readUInt32LE(8) !== rate * alignment ||
    !data.length ||
    data.length % alignment !== 0 ||
    data.length / alignment / rate > 121
  )
    throw new Error("Unsupported recording PCM");
  const frames = data.length / alignment;
  const quiet: { start: number; end: number }[] = [];
  let quietStart = -1;
  let nonSilent = false;
  let lastAudibleEnd = 0;
  for (let frame = 0; frame <= frames; frame++) {
    let zero = frame < frames;
    for (let channel = 0; zero && channel < channels; channel++) {
      zero = data.readInt16LE(frame * alignment + channel * 2) === 0;
    }
    if (frame < frames && !zero) {
      nonSilent = true;
      lastAudibleEnd = frame + 1;
    }
    if (zero && quietStart < 0) quietStart = frame;
    if (!zero && quietStart >= 0) {
      if (frame - quietStart >= rate * 0.15)
        quiet.push({ start: quietStart, end: frame });
      quietStart = -1;
    }
  }
  if (!nonSilent) throw new Error("Original recording is digitally silent");
  // Never equate quiet speech or background noise with silence. Only measured
  // all-zero PCM can be omitted, with one original second retained at each edge.
  const spans: { start: number; end: number }[] = [];
  let start = 0;
  for (const gap of quiet) {
    if (gap.end - gap.start < rate * 3) continue;
    const end = Math.min(frames, gap.start + rate);
    if (gap.start > start) spans.push({ start, end });
    start = Math.max(start, gap.end - rate);
  }
  if (start < lastAudibleEnd) spans.push({ start, end: frames });
  // Capture clients already bound and pad every request. Splitting continuous speech again
  // creates a second seam, which can make genuine repeated words impossible to align.
  if (options.alreadyWindowed) return spans.map(encodeWindow);
  const measured: FrameWindow[] = [];
  for (const span of spans) {
    let cursor = span.start;
    while (span.end - cursor > rate * 12) {
      const candidates = quiet.filter(
        (gap) =>
          gap.start >= cursor + rate * 5 &&
          gap.end <= Math.min(span.end, cursor + rate * 12),
      );
      candidates.sort(
        (a, b) =>
          Math.abs((a.start + a.end) / 2 - cursor - rate * 10) -
          Math.abs((b.start + b.end) / 2 - cursor - rate * 10),
      );
      const gap = candidates[0];
      if (!gap) break; // Long remainders get transport-only cores below, never semantic labels.
      const boundary = Math.floor((gap.start + gap.end) / 2);
      measured.push({ start: cursor, end: boundary });
      cursor = boundary;
    }
    if (cursor < span.end) measured.push({ start: cursor, end: span.end });
  }

  const isAudibleFrame = (frame: number) => {
    for (let channel = 0; channel < channels; channel++) {
      if (data.readInt16LE(frame * alignment + channel * 2) !== 0) return true;
    }
    return false;
  };
  const hasAudibleFrame = (from: number, to: number) => {
    for (let frame = from; frame < to; frame++) {
      if (isAudibleFrame(frame)) return true;
    }
    return false;
  };
  const boundedCores = (span: FrameWindow): FrameWindow[] => {
    const boundaries = [{ frame: span.start, padded: false }];
    let finalAudibleEnd = span.end;
    while (
      finalAudibleEnd > span.start &&
      !isAudibleFrame(finalAudibleEnd - 1)
    ) {
      finalAudibleEnd--;
    }
    let cursor = span.start;
    while (span.end - cursor > rate * 10) {
      let selected = cursor + rate * 10;
      let bestEnergy = Number.POSITIVE_INFINITY;
      // A 20ms, all-channel energy envelope chooses only a transport cut.
      // It never thresholds, modifies or discards low-amplitude original samples.
      for (let tick = 400; tick <= 500; tick++) {
        const end = cursor + Math.round((tick * rate) / 50);
        if (end >= finalAudibleEnd && finalAudibleEnd > cursor + rate * 8)
          continue;
        const start = cursor + Math.round(((tick - 1) * rate) / 50);
        let energy = 0;
        for (let frame = start; frame < end; frame++) {
          for (let channel = 0; channel < channels; channel++) {
            const sample = data.readInt16LE(frame * alignment + channel * 2);
            energy += sample * sample;
          }
        }
        // Normalize the rare rates whose 20ms blocks differ by one sample.
        energy /= end - start;
        if (energy <= bestEnergy) {
          bestEnergy = energy;
          selected = end; // Equal energy prefers the point nearest ten seconds.
        }
      }
      // Even a zero-energy 20ms block can be an in-word closure, not a measured
      // pause. Every fallback cut keeps original context and explicit ownership.
      boundaries.push({ frame: selected, padded: true });
      cursor = selected;
    }
    boundaries.push({ frame: span.end, padded: false });
    return boundaries.slice(0, -1).flatMap((left, index) => {
      const right = boundaries[index + 1]!;
      // Repartitioning a fragmented recording may reinstate a long digital-zero
      // gap. Do not turn that gap into a silence-only recognition request.
      if (!hasAudibleFrame(left.frame, right.frame)) return [];
      return [
        {
          start: Math.max(span.start, left.frame - (left.padded ? rate : 0)),
          end: Math.min(span.end, right.frame + (right.padded ? rate : 0)),
          ...(left.padded || right.padded
            ? { coreStart: left.frame, coreEnd: right.frame }
            : {}),
        },
      ];
    });
  };
  let windows = measured.flatMap((window) =>
    window.end - window.start > rate * 12 ? boundedCores(window) : [window],
  );
  if (windows.length > 16) {
    // Partition the original axis instead of merging into unbounded requests.
    // With cuts at 8..10s, <=121s requires at most sixteen cores. Only actual
    // digital-zero gaps can be omitted; all nonzero PCM remains original bytes.
    windows = boundedCores({ start: spans[0]!.start, end: spans.at(-1)!.end });
  }
  return windows.map(encodeWindow);

  function encodeWindow(window: FrameWindow): RecordingAudioWindow {
    const body = data.subarray(
      window.start * alignment,
      window.end * alignment,
    );
    const wave = Buffer.alloc(44 + body.length);
    wave.write("RIFF");
    wave.writeUInt32LE(wave.length - 8, 4);
    wave.write("WAVEfmt ", 8);
    wave.writeUInt32LE(16, 16);
    format!.copy(wave, 20, 0, 16);
    wave.writeUInt16LE(1, 20);
    wave.write("data", 36);
    wave.writeUInt32LE(body.length, 40);
    body.copy(wave, 44);
    return {
      startSeconds: window.start / rate,
      durationSeconds: (window.end - window.start) / rate,
      audioBase64: wave.toString("base64"),
      ...(window.coreStart !== undefined && window.coreEnd !== undefined
        ? {
            coreStartSeconds: window.coreStart / rate,
            coreEndSeconds: window.coreEnd / rate,
          }
        : {}),
    };
  }
}
