// Transfers only the freshly generated isolated session through stdin; never exposes it in arguments/output.
import { spawnSync } from "node:child_process";
import { lstat, readFile } from "node:fs/promises";
import { dirname, isAbsolute } from "node:path";

try {
  const [metaPath, adbPath, serial] = process.argv.slice(2);
  if (
    !metaPath ||
    !isAbsolute(metaPath) ||
    !adbPath ||
    !isAbsolute(adbPath) ||
    !serial?.startsWith("emulator-")
  ) {
    throw new Error("Expected absolute metadata/adb paths and emulator serial");
  }
  const metadata = JSON.parse(await readFile(metaPath, "utf8"));
  if (
    !Number.isSafeInteger(metadata.pid) ||
    !Number.isSafeInteger(metadata.port) ||
    metadata.metaPath !== metaPath ||
    dirname(metadata.sessionPath) !== dirname(metaPath)
  )
    throw new Error("Invalid metadata");
  process.kill(metadata.pid, 0);
  const stat = await lstat(metadata.sessionPath);
  if (
    !stat.isFile() ||
    stat.isSymbolicLink() ||
    (stat.mode & 0o077) !== 0 ||
    stat.size > 16_384
  )
    throw new Error("Session file is not private");
  const bytes = await readFile(metadata.sessionPath);
  const session = JSON.parse(bytes.toString("utf8"));
  if (
    session.fixture !== "REAL_MESSAGES_HTTP_V1" ||
    session.baseUrl !== `http://10.0.2.2:${metadata.port}`
  )
    throw new Error("Wrong isolated fixture");
  const invoke = (args, input) =>
    spawnSync(adbPath, ["-s", serial, ...args], {
      input,
      stdio: ["pipe", "ignore", "pipe"],
    });
  const existing = invoke([
    "shell",
    "run-as",
    "com.reallanguage.app",
    "test",
    "-e",
    "files/live-messages-session.json",
  ]);
  if (existing.status !== 1) throw new Error("Destination must be absent");
  const transferred = invoke(
    [
      "shell",
      "-T",
      "run-as",
      "com.reallanguage.app",
      "sh",
      "-c",
      "'umask 077; exec tee files/live-messages-session.json'",
    ],
    bytes,
  );
  if (
    transferred.status !== 0 ||
    invoke([
      "shell",
      "run-as",
      "com.reallanguage.app",
      "test",
      "-s",
      "files/live-messages-session.json",
    ]).status !== 0
  ) {
    throw new Error("Private session transfer failed");
  }
  process.stdout.write(
    "Isolated message session transferred; contents omitted.\n",
  );
} catch {
  process.stderr.write(
    "Isolated message session transfer failed; no credential output.\n",
  );
  process.exitCode = 1;
}
