import { describe, expect, it } from "vitest";
import { decodeSpeakingAudio } from "./decode-speaking-audio.js";

describe("original speaking audio decoder", () => {
  const pcm = (seconds: number) => {
    const dataBytes = seconds * 16000 * 2;
    const bytes = Buffer.alloc(44 + dataBytes);
    bytes.write("RIFF", 0);
    bytes.writeUInt32LE(bytes.length - 8, 4);
    bytes.write("WAVE", 8);
    bytes.write("fmt ", 12);
    bytes.writeUInt32LE(16, 16);
    bytes.writeUInt16LE(1, 20);
    bytes.writeUInt16LE(1, 22);
    bytes.writeUInt32LE(16000, 24);
    bytes.writeUInt32LE(32000, 28);
    bytes.writeUInt16LE(2, 32);
    bytes.writeUInt16LE(16, 34);
    bytes.write("data", 36);
    bytes.writeUInt32LE(dataBytes, 40);
    return bytes.toString("base64");
  };
  it("recording mode accepts bounded two-minute audio larger than speaking input limit", async () => {
    const audio = pcm(120);
    expect(
      await decodeSpeakingAudio(audio, "audio/wav", {
        maximumBase64Length: 8_000_000,
        maximumDurationSeconds: 121,
      }),
    ).toBe(audio);
  });
  it("sends only the accepted PCM prefix and excludes all bytes of the discarded tail", async () => {
    const original = Buffer.from(pcm(2), "base64");
    original.fill(17, 44, 44 + 32000);
    original.fill(93, 44 + 32000);
    const limits = {
      maximumBase64Length: 8_000_000,
      maximumDurationSeconds: 121,
      acceptedEndMs: 1000,
    };
    const clipped = Buffer.from(
      await decodeSpeakingAudio(
        original.toString("base64"),
        "audio/wav",
        limits,
      ),
      "base64",
    );
    expect(clipped.length).toBe(44 + 32000);
    expect(clipped.readUInt32LE(40)).toBe(32000);
    expect(clipped.readUInt32LE(4)).toBe(clipped.length - 8);
    expect(clipped.subarray(44)).toEqual(original.subarray(44, 44 + 32000));
  });
  it.each([0, 2001, NaN, 1.25])(
    "rejects unsupported accepted boundary %s before provider upload",
    async (acceptedEndMs) => {
      const limits = {
        maximumBase64Length: 8_000_000,
        maximumDurationSeconds: 121,
        acceptedEndMs,
      };
      await expect(
        decodeSpeakingAudio(pcm(2), "audio/wav", limits),
      ).rejects.toThrow();
    },
  );
  const extensiblePcm = () => {
    const standard = Buffer.from(pcm(1), "base64");
    const bytes = Buffer.alloc(standard.length + 24);
    standard.copy(bytes, 0, 0, 36);
    bytes.writeUInt32LE(bytes.length - 8, 4);
    bytes.writeUInt32LE(40, 16);
    bytes.writeUInt16LE(0xfffe, 20);
    bytes.writeUInt16LE(22, 36);
    bytes.writeUInt16LE(16, 38);
    bytes.writeUInt32LE(4, 40);
    Buffer.from("0100000000001000800000aa00389b71", "hex").copy(bytes, 44);
    standard.copy(bytes, 60, 36);
    return bytes;
  };
  it("accepts actual platform WAVE_EXTENSIBLE output only when its subformat is PCM", async () => {
    const audio = extensiblePcm().toString("base64");
    expect(
      await decodeSpeakingAudio(audio, "audio/wav", {
        maximumBase64Length: 8_000_000,
        maximumDurationSeconds: 121,
      }),
    ).toBe(audio);
  });
  it("rejects extensible non-PCM audio instead of interpreting its bytes as PCM duration", async () => {
    const audio = extensiblePcm();
    audio.writeUInt32LE(3, 44);
    await expect(
      decodeSpeakingAudio(audio.toString("base64"), "audio/wav", {
        maximumBase64Length: 8_000_000,
        maximumDurationSeconds: 121,
      }),
    ).rejects.toThrow("Unsupported recording waveform");
  });
  it("recording mode rejects excess measured duration before any provider request", async () => {
    await expect(
      decodeSpeakingAudio(pcm(2), "audio/wav", {
        maximumBase64Length: 8_000_000,
        maximumDurationSeconds: 1,
      }),
    ).rejects.toThrow();
  });
  it.each(["", "!not-base64!", "Y".repeat(900_001)])(
    "rejects invalid or oversized input before conversion",
    async (value) => {
      await expect(decodeSpeakingAudio(value, "audio/mp4")).rejects.toThrow(
        "Invalid speaking audio",
      );
    },
  );
  it("rejects arbitrary formats instead of treating them as recordings", async () => {
    await expect(decodeSpeakingAudio("YWJj", "text/plain")).rejects.toThrow(
      "Unsupported speaking audio format",
    );
  });
});
