import { describe, expect, it } from "vitest";
import { splitUncoveredCapture } from "./capture-speech-coverage.js";

const rate = 16000;
const bytes = Buffer.alloc(44 + rate * 2 * 16);
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
for (let f = 0; f < rate * 16; f++)
  bytes.writeInt16LE(
    f % (2 * rate) < 1.8 * rate ? (f % 16 < 8 ? 800 : -800) : 5,
    44 + 2 * f,
  );
const window = {
  startSeconds: 7,
  durationSeconds: 16,
  audioBase64: bytes.toString("base64"),
};
const word = (start: number) => ({
  word: "test",
  textStart: 0,
  start,
  end: start + 1.8,
});
describe("capture speech coverage", () => {
  it("re-observes acoustic bursts missing from a transcript without duplicating, trimming or reordering any sample", () => {
    const parts = splitUncoveredCapture(window, {
      text: "test",
      language: "en",
      words: [word(0)],
    });
    expect(parts.length).toBeGreaterThan(2);
    expect(parts[0]?.startSeconds).toBe(7);
    expect(parts.at(-1)!.startSeconds + parts.at(-1)!.durationSeconds).toBe(23);
    expect(
      Buffer.concat(
        parts.map((p) => Buffer.from(p.audioBase64, "base64").subarray(44)),
      ).equals(bytes.subarray(44)),
    ).toBe(true);
  });
  it("does not re-observe a transcript that already accounts for each spoken region", () => {
    expect(
      splitUncoveredCapture(window, {
        text: "test ".repeat(8),
        language: "en",
        words: Array.from({ length: 8 }, (_, i) => word(i * 2)),
      }),
    ).toEqual([]);
  });
});
