import { createCipheriv, createDecipheriv, createHash, randomBytes, randomUUID } from "node:crypto";
import { mkdir, readFile, writeFile, unlink, rm, readdir } from "node:fs/promises";
import { join } from "node:path";
import { measureSentenceWav } from "./synthesized-sentence-audio.js";

/** Private, account-scoped cache; HTTP callers must authenticate before passing accountId. */
export class SentenceAudioStore {
  constructor(private readonly root: string, private readonly key: Buffer, private readonly now = () => Date.now()) {
    if (key.length !== 32) throw new Error("Sentence audio encryption requires a 32-byte key");
  }
  private folder(accountId: string) {
    if (!accountId.trim()) throw new Error("Missing audio owner");
    return join(this.root, createHash("sha256").update(accountId).digest("hex"));
  }
  private ownerKey(accountId: string) { return createHash("sha256").update(accountId).digest("hex"); }
  private decode(stored: Buffer, ownerKey: string, objectRef: string) {
    if (stored.length < 28) throw new Error("Invalid encrypted sentence audio");
    const cipher = createDecipheriv("aes-256-gcm", this.key, stored.subarray(0, 12));
    cipher.setAAD(Buffer.from(`${ownerKey}:${objectRef}`));
    cipher.setAuthTag(stored.subarray(12, 28));
    return JSON.parse(Buffer.concat([cipher.update(stored.subarray(28)), cipher.final()]).toString("utf8")) as { expiresAtMs: number; wav: string };
  }
  private path(accountId: string, objectRef: string) {
    if (!/^tts_[0-9a-f-]{36}$/u.test(objectRef)) throw new Error("Invalid sentence audio reference");
    return join(this.folder(accountId), `${objectRef}.bin`);
  }
  async put(accountId: string, bytes: Buffer, retentionMs: number) {
    if (!Number.isSafeInteger(retentionMs) || retentionMs <= 0 || retentionMs > 86_400_000 || bytes.length > 16 * 1024 * 1024) {
      throw new Error("Invalid sentence audio retention or size");
    }
    const durationMs = measureSentenceWav(bytes);
    const objectRef = `tts_${randomUUID()}`;
    const expiresAtMs = this.now() + retentionMs;
    const iv = randomBytes(12);
    const cipher = createCipheriv("aes-256-gcm", this.key, iv);
    cipher.setAAD(Buffer.from(`${this.ownerKey(accountId)}:${objectRef}`));
    const payload = Buffer.from(JSON.stringify({ expiresAtMs, wav: bytes.toString("base64") }));
    const encrypted = Buffer.concat([cipher.update(payload), cipher.final()]);
    await mkdir(this.folder(accountId), { recursive: true, mode: 0o700 });
    await writeFile(this.path(accountId, objectRef), Buffer.concat([iv, cipher.getAuthTag(), encrypted]), { flag: "wx", mode: 0o600 });
    return { audioObjectRef: objectRef, durationMs, expiresAtMs };
  }
  async get(accountId: string, objectRef: string): Promise<Buffer | null> {
    const path = this.path(accountId, objectRef);
    let stored: Buffer;
    try { stored = await readFile(path); }
    catch (error) { if ((error as NodeJS.ErrnoException).code === "ENOENT") return null; throw error; }
    const payload = this.decode(stored, this.ownerKey(accountId), objectRef);
    if (!Number.isFinite(payload.expiresAtMs) || payload.expiresAtMs <= this.now()) { await unlink(path); return null; }
    const bytes = Buffer.from(payload.wav, "base64");
    measureSentenceWav(bytes);
    return bytes;
  }
  async deleteAccount(accountId: string) { await rm(this.folder(accountId), { recursive: true, force: true }); }
  async purgeExpired() {
    let owners;
    try { owners = await readdir(this.root, { withFileTypes: true }); }
    catch (error) { if ((error as NodeJS.ErrnoException).code === "ENOENT") return; throw error; }
    for (const owner of owners) {
      if (!owner.isDirectory() || !/^[0-9a-f]{64}$/u.test(owner.name)) continue;
      const folder = join(this.root, owner.name);
      for (const file of await readdir(folder, { withFileTypes: true })) {
        if (!file.isFile() || !/^tts_[0-9a-f-]{36}\.bin$/u.test(file.name)) continue;
        const path = join(folder, file.name);
        try {
          const payload = this.decode(await readFile(path), owner.name, file.name.slice(0, -4));
          if (!Number.isFinite(payload.expiresAtMs) || payload.expiresAtMs <= this.now()) await unlink(path);
        } catch (error) { if ((error as NodeJS.ErrnoException).code !== "ENOENT") throw error; }
      }
    }
  }
}
