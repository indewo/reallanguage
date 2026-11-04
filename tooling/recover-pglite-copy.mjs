// Offline, explicitly authorized recovery experiments ONLY. Never run on the live DB.
// PG18 layouts: postgres/REL_18_STABLE src/include/catalog/pg_control.h,
// src/include/access/xlog_internal.h and src/bin/pg_resetwal/pg_resetwal.c.
// Recovery strategy also reviewed against electric-sql/pglite PR #994 (PG17 only).
import {
  readFile,
  readdir,
  realpath,
  lstat,
  rename,
  mkdir,
  open,
  access,
} from "node:fs/promises";
import { resolve, join } from "node:path";

export function crc32c(...parts) {
  let crc = 0xffffffff;
  for (const part of parts)
    for (const byte of part) {
      crc ^= byte;
      for (let bit = 0; bit < 8; bit++)
        crc = (crc >>> 1) ^ (crc & 1 ? 0x82f63b78 : 0);
    }
  return (crc ^ 0xffffffff) >>> 0;
}

async function durableWrite(path, bytes) {
  const file = await open(path, "wx", 0o600);
  try {
    await file.writeFile(bytes);
    await file.sync();
  } finally {
    await file.close();
  }
}

export async function resetCopyWal(directory) {
  const root = resolve(directory);
  const allowed = resolve(".local-data") + "/db-recovery-";
  if (
    !root.startsWith(allowed) ||
    !/^db-recovery-[^/]+\/database$/.test(
      root.slice(resolve(".local-data").length + 1),
    )
  )
    throw new Error("Only an explicitly named recovery copy is allowed");
  for (const path of [
    root,
    join(root, "global"),
    join(root, "global/pg_control"),
    join(root, "pg_wal"),
  ]) {
    if ((await lstat(path)).isSymbolicLink() || (await realpath(path)) !== path)
      throw new Error("Symlink refused");
  }
  if ((await readFile(join(root, "PG_VERSION"), "utf8")).trim() !== "18")
    throw new Error("Requires PostgreSQL 18");
  const original = await readFile(join(root, "global/pg_control"));
  if (
    original.length !== 8192 ||
    original.readUInt32LE(8) !== 1800 ||
    original.readUInt32LE(292) !== crc32c(original.subarray(0, 292))
  )
    throw new Error("Unsupported or corrupt PG18 control layout/CRC");
  const size = original.readUInt32LE(228);
  if (original.readUInt32LE(224) !== 8192 || size !== 16777216)
    throw new Error("Unexpected WAL configuration");
  const names = (await readdir(join(root, "pg_wal"))).filter((name) =>
    /^[0-9A-F]{24}$/.test(name),
  );
  let segment = original.readBigUInt64LE(40) / BigInt(size);
  for (const name of names) {
    const number =
      BigInt("0x" + name.slice(8, 16)) * 256n + BigInt("0x" + name.slice(16));
    if (number > segment) segment = number;
  }
  segment++;
  const timeline = original.readUInt32LE(48);
  if (timeline === 0) throw new Error("Invalid timeline");
  const control = Buffer.from(original);
  const lsn = segment * BigInt(size) + 40n;
  const now = BigInt(Math.floor(Date.now() / 1000));
  control.writeInt32LE(1, 16);
  control.writeBigUInt64LE(now, 24);
  control.writeBigUInt64LE(lsn, 32);
  control.writeBigUInt64LE(lsn, 40);
  control.writeInt32LE(0, 60); // CheckPoint.wal_level (PG18)
  control.writeBigUInt64LE(now, 104);
  for (const offset of [136, 152, 160]) control.writeBigUInt64LE(0n, offset);
  control.writeUInt32LE(0, 144);
  control.writeUInt8(0, 168);
  control.writeInt32LE(0, 172);
  control.writeUInt32LE(crc32c(control.subarray(0, 292)), 292);
  const wal = Buffer.alloc(size);
  wal.writeUInt16LE(0xd118, 0);
  wal.writeUInt16LE(2, 2);
  wal.writeUInt32LE(timeline, 4);
  wal.writeBigUInt64LE(lsn - 40n, 8);
  wal.writeBigUInt64LE(control.readBigUInt64LE(0), 24);
  wal.writeUInt32LE(size, 32);
  wal.writeUInt32LE(8192, 36);
  wal.writeUInt32LE(114, 40); // 24-byte record + 2-byte data header + 88-byte CheckPoint
  wal[64] = 255;
  wal[65] = 88;
  control.copy(wal, 66, 40, 128);
  wal.writeUInt32LE(crc32c(wal.subarray(64, 154), wal.subarray(40, 60)), 60);
  const filename = [BigInt(timeline), segment / 256n, segment % 256n]
    .map((n) => n.toString(16).toUpperCase().padStart(8, "0"))
    .join("");
  // Preserve all old WAL/control bytes, including interrupted attempts; never delete.
  const backup = join(root, "pre-reset");
  await mkdir(backup, { mode: 0o700 });
  await durableWrite(join(backup, "pg_control"), original);
  await rename(join(root, "pg_wal"), join(backup, "pg_wal"));
  await mkdir(join(root, "pg_wal"), { mode: 0o700 });
  await mkdir(join(root, "pg_wal/archive_status"), { mode: 0o700 });
  await durableWrite(join(root, "pg_wal", filename), wal);
  try {
    await access(join(root, "postmaster.pid"));
    await rename(join(root, "postmaster.pid"), join(backup, "postmaster.pid"));
  } catch (error) {
    if (error.code !== "ENOENT") throw error;
  }
  await durableWrite(join(root, "global/pg_control.recovered"), control);
  await rename(
    join(root, "global/pg_control.recovered"),
    join(root, "global/pg_control"),
  );
  return { status: "RESET_COPY_ONLY", pgVersion: 18 };
}
