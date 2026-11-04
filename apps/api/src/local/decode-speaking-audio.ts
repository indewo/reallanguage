import { execFile } from "node:child_process";
import {
  mkdtemp,
  readFile,
  writeFile,
  unlink,
  rmdir,
  stat,
} from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { promisify } from "node:util";

const execute = promisify(execFile);

/** Decode the original recording; never synthesize audio from the corrected transcript. */
export async function decodeSpeakingAudio(
  base64: string,
  mimeType: string,
  recordingLimits?: Readonly<{
    maximumBase64Length: number;
    maximumDurationSeconds: number;
    acceptedEndMs?: number;
  }>,
): Promise<string> {
  if (
    !base64 ||
    base64.length >
      Math.min(recordingLimits?.maximumBase64Length ?? 900_000, 8_000_000) ||
    !/^[A-Za-z0-9+/]+={0,2}$/u.test(base64)
  )
    throw new Error("Invalid speaking audio");
  if (mimeType === "audio/wav") {
    if (recordingLimits)
      verifyPcmDuration(
        Buffer.from(base64, "base64"),
        recordingLimits.maximumDurationSeconds,
      );
    return recordingLimits?.acceptedEndMs === undefined
      ? base64
      : acceptedPcmPrefix(
          Buffer.from(base64, "base64"),
          recordingLimits.acceptedEndMs,
        ).toString("base64");
  }
  if (mimeType !== "audio/mp4")
    throw new Error("Unsupported speaking audio format");
  const directory = await mkdtemp(join(tmpdir(), "reallanguage-speaking-"));
  const source = join(directory, "source.m4a");
  const destination = join(directory, "decoded.wav");
  try {
    await writeFile(source, Buffer.from(base64, "base64"), { mode: 0o600 });
    const options = { timeout: 15_000, maxBuffer: 64_000 };
    if (process.platform === "darwin") {
      await execute(
        "/usr/bin/afconvert",
        ["-f", "WAVE", "-d", "LEI16@16000", source, destination],
        options,
      );
    } else {
      await execute(
        "ffmpeg",
        [
          "-nostdin",
          "-v",
          "error",
          "-i",
          source,
          "-ac",
          "1",
          "-ar",
          "16000",
          "-f",
          "wav",
          destination,
        ],
        options,
      );
    }
    const size = (await stat(destination)).size;
    if (size <= 44 || size > 8_000_000)
      throw new Error("Invalid decoded audio size");
    const decoded = await readFile(destination);
    if (recordingLimits)
      verifyPcmDuration(decoded, recordingLimits.maximumDurationSeconds);
    return (
      recordingLimits?.acceptedEndMs === undefined
        ? decoded
        : acceptedPcmPrefix(decoded, recordingLimits.acceptedEndMs)
    ).toString("base64");
  } catch {
    // Do not propagate converter stderr or recorded content to API clients/logs.
    throw new Error("Could not decode original speaking audio");
  } finally {
    await unlink(source).catch(() => undefined);
    await unlink(destination).catch(() => undefined);
    await rmdir(directory);
  }
}

/** Preserve original sample bytes; omit everything after the confirmed semantic boundary. */
function acceptedPcmPrefix(bytes: Buffer, acceptedEndMs: number): Buffer {
  if (!Number.isSafeInteger(acceptedEndMs) || acceptedEndMs <= 0)
    throw new Error("Invalid accepted recording boundary");
  // The caller has already validated PCM format, length, channels and byte rate.
  let format: Buffer | undefined;
  const chunks: Buffer[] = [];
  for (let offset = 12; offset + 8 <= bytes.length;) {
    const length = bytes.readUInt32LE(offset + 4);
    const start = offset + 8;
    const name = bytes.toString("ascii", offset, offset + 4);
    if (name === "fmt ") {
      if (format) throw new Error("Ambiguous recording format");
      format = bytes.subarray(start, start + 16);
    }
    if (name === "data") chunks.push(bytes.subarray(start, start + length));
    offset = start + length + (length % 2);
  }
  if (!format) throw new Error("Missing recording format");
  const blockAlign = format.readUInt16LE(12);
  const byteRate = format.readUInt32LE(8);
  const data = Buffer.concat(chunks);
  if (acceptedEndMs * byteRate > data.length * 1000)
    throw new Error("Accepted boundary exceeds original audio");
  const length =
    Math.floor((acceptedEndMs * byteRate) / 1000 / blockAlign) * blockAlign;
  if (length <= 0) throw new Error("Empty accepted audio");
  const result = Buffer.alloc(44 + length);
  result.write("RIFF", 0);
  result.writeUInt32LE(result.length - 8, 4);
  result.write("WAVEfmt ", 8);
  result.writeUInt32LE(16, 16);
  format.copy(result, 20);
  result.writeUInt16LE(1, 20); // Normalize extensible PCM's header, not its samples.
  result.write("data", 36);
  result.writeUInt32LE(length, 40);
  data.copy(result, 44, 0, length);
  return result;
}

function verifyPcmDuration(bytes: Buffer, maximumSeconds: number) {
  if (
    !Number.isFinite(maximumSeconds) ||
    maximumSeconds <= 0 ||
    maximumSeconds > 121 ||
    bytes.length < 44 ||
    bytes.toString("ascii", 0, 4) !== "RIFF" ||
    bytes.toString("ascii", 8, 12) !== "WAVE"
  )
    throw new Error("Invalid recording waveform");
  let byteRate = 0;
  let dataBytes = 0;
  for (let offset = 12; offset + 8 <= bytes.length;) {
    const name = bytes.toString("ascii", offset, offset + 4);
    const length = bytes.readUInt32LE(offset + 4);
    const start = offset + 8;
    if (start + length > bytes.length)
      throw new Error("Truncated recording waveform");
    if (name === "fmt ") {
      if (length < 16) throw new Error("Unsupported recording waveform");
      const format = bytes.readUInt16LE(start);
      const extensiblePcm =
        format === 0xfffe &&
        length >= 40 &&
        bytes.readUInt16LE(start + 16) >= 22 &&
        bytes.readUInt16LE(start + 18) === 16 &&
        bytes
          .subarray(start + 24, start + 40)
          .equals(Buffer.from("0100000000001000800000aa00389b71", "hex"));
      if (format !== 1 && !extensiblePcm)
        throw new Error("Unsupported recording waveform");
      const channels = bytes.readUInt16LE(start + 2);
      const rate = bytes.readUInt32LE(start + 4);
      const bits = bytes.readUInt16LE(start + 14);
      byteRate = bytes.readUInt32LE(start + 8);
      if (
        channels < 1 ||
        channels > 2 ||
        rate < 8000 ||
        bits !== 16 ||
        bytes.readUInt16LE(start + 12) !== channels * 2 ||
        byteRate !== rate * channels * 2
      )
        throw new Error("Invalid recording waveform rate");
    }
    if (name === "data") dataBytes += length;
    offset = start + length + (length % 2);
  }
  if (!byteRate || !dataBytes || dataBytes / byteRate > maximumSeconds)
    throw new Error("Recording exceeds measured duration limit");
}
