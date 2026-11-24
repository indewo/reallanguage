import fs from "node:fs";
import path from "node:path";
import crypto from "node:crypto";
import { fileURLToPath } from "node:url";

// Read-only audit. Include test sources: their R references must still compile.
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const sourceRoot = path.join(root, "apps/android/app/src");
function filesIn(dir) {
  return fs.readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const target = path.join(dir, entry.name);
    return entry.isDirectory() ? filesIn(target) : [target];
  });
}
const relative = (file) => path.relative(root, file).replaceAll("\\", "/");
const sources = filesIn(sourceRoot).filter((file) =>
  /\.(kt|java|xml)$/.test(file),
);
const referenced = new Set();
const dynamicLookupFiles = [];
for (const file of sources) {
  const text = fs.readFileSync(file, "utf8");
  for (const match of text.matchAll(
    /\bR\.drawable\.([a-zA-Z0-9_]+)\b|@(?:[\w.]+:)?drawable\/([a-zA-Z0-9_]+)/g,
  )) {
    referenced.add(match[1] ?? match[2]);
  }
  if (/\bgetIdentifier\s*\(/.test(text))
    dynamicLookupFiles.push(relative(file));
}
const images = filesIn(path.join(sourceRoot, "main/res/drawable-nodpi"));
const unused = images
  .filter((file) => !referenced.has(path.basename(file, path.extname(file))))
  .map((file) => ({
    path: relative(file),
    bytes: fs.statSync(file).size,
    sha256: crypto
      .createHash("sha256")
      .update(fs.readFileSync(file))
      .digest("hex"),
  }))
  .sort((a, b) => a.path.localeCompare(b.path));
const result = {
  sourceFilesScanned: sources.length,
  drawableFiles: images.length,
  dynamicLookupFiles,
  unusedCount: unused.length,
  unusedBytes: unused.reduce((sum, item) => sum + item.bytes, 0),
  unused,
};
process.stdout.write(`${JSON.stringify(result, null, 2)}\n`);
