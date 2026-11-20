import { mkdtemp, readdir, readFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { expect, it } from "vitest";
import { SentenceAudioStore } from "./sentence-audio-store.js";

it("persists encrypted audio across instances, isolates owners and expires without regeneration", async () => {
  const root = await mkdtemp(join(tmpdir(), "rl-tts-test-"));
  try {
    const wav = Buffer.alloc(46);
    wav.write("RIFF"); wav.writeUInt32LE(38, 4); wav.write("WAVEfmt ", 8); wav.writeUInt32LE(16, 16);
    wav.writeUInt16LE(1, 20); wav.writeUInt16LE(1, 22); wav.writeUInt32LE(24000, 24); wav.writeUInt32LE(48000, 28);
    wav.writeUInt16LE(2, 32); wav.writeUInt16LE(16, 34); wav.write("data", 36); wav.writeUInt32LE(2, 40);
    const key = Buffer.alloc(32, 7);
    let now = 1000;
    const first = new SentenceAudioStore(root, key, () => now);
    const saved = await first.put("owner-one", wav, 1000);
    const restored = new SentenceAudioStore(root, key, () => now);
    expect(await restored.get("owner-one", saved.audioObjectRef)).toEqual(wav);
    expect(await restored.get("owner-two", saved.audioObjectRef)).toBeNull();
    const folder = (await readdir(root))[0]!;
    const disk = await readFile(join(root, folder, `${saved.audioObjectRef}.bin`));
    expect(disk.includes(Buffer.from(wav.toString("base64")))).toBe(false);
    await expect(restored.get("owner-one", "../../secret")).rejects.toThrow();
    now = 2000;
    expect(await restored.get("owner-one", saved.audioObjectRef)).toBeNull();
    expect(await readdir(join(root, folder))).toEqual([]);
    await restored.deleteAccount("owner-one");
    expect(await readdir(root)).toEqual([]);
    now = 3000;
    const unread = await restored.put("owner-one", wav, 1000);
    const live = await restored.put("owner-two", wav, 3000);
    now = 4000;
    await restored.purgeExpired();
    expect(await restored.get("owner-one", unread.audioObjectRef)).toBeNull();
    expect(await restored.get("owner-two", live.audioObjectRef)).toEqual(wav);
  } finally { await rm(root, { recursive: true, force: true }); }
});
