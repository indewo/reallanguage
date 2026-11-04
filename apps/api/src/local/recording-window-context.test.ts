import { expect, it } from "vitest";
import { expandRecordingWindowContext } from "./recording-window-context.js";
import type { RecordingAudioWindow } from "./recording-audio-windows.js";

function window(
  start: number,
  end: number,
  coreStart: number,
  coreEnd: number,
): RecordingAudioWindow {
  const rate = 8000,
    bytes = Buffer.alloc(44 + (end - start) * rate * 2);
  bytes.write("RIFF");
  bytes.writeUInt32LE(bytes.length - 8, 4);
  bytes.write("WAVEfmt ", 8);
  bytes.writeUInt32LE(16, 16);
  bytes.writeUInt16LE(1, 20);
  bytes.writeUInt16LE(1, 22);
  bytes.writeUInt32LE(rate, 24);
  bytes.writeUInt32LE(rate * 2, 28);
  bytes.writeUInt16LE(2, 32);
  bytes.writeUInt16LE(16, 34);
  bytes.write("data", 36);
  bytes.writeUInt32LE(bytes.length - 44, 40);
  for (let i = 0; i < (end - start) * rate; i++)
    bytes.writeInt16LE(((start * rate + i) % 30000) + 1, 44 + i * 2);
  return {
    startSeconds: start,
    durationSeconds: end - start,
    coreStartSeconds: coreStart,
    coreEndSeconds: coreEnd,
    audioBase64: bytes.toString("base64"),
  };
}

it("expands only transport context using exact original PCM samples, never core ownership or synthetic silence", () => {
  const windows = [
    window(0, 11, 0, 10),
    window(9, 21, 10, 20),
    window(19, 30, 20, 30),
  ];
  const result = expandRecordingWindowContext(windows, 1);
  expect(result).toEqual(window(7, 23, 10, 20));
});
it("does not bridge an omitted silence gap or exceed the original recording", () => {
  const windows = [window(0, 5, 0, 5), window(9, 20, 10, 20)];
  expect(expandRecordingWindowContext(windows, 1)).toEqual(windows[1]);
  expect(expandRecordingWindowContext([window(0, 10, 0, 10)], 0)).toEqual(
    window(0, 10, 0, 10),
  );
});
it("rejects invalid target indices without a provider call", () => {
  expect(() =>
    expandRecordingWindowContext([window(0, 10, 0, 10)], -1),
  ).toThrow();
});
