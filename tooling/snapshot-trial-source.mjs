import { createHash } from "node:crypto";
import { execFileSync } from "node:child_process";
import {
  closeSync,
  constants,
  existsSync,
  fstatSync,
  lstatSync,
  mkdirSync,
  mkdtempSync,
  openSync,
  readdirSync,
  readFileSync,
  realpathSync,
  renameSync,
  rmSync,
  rmdirSync,
  unlinkSync,
  writeFileSync,
} from "node:fs";
import {
  basename,
  dirname,
  extname,
  isAbsolute,
  relative,
  resolve,
  sep,
} from "node:path";

// A source snapshot is not a backup of the private machine or its build credentials.
const repository = realpathSync(".");
const sha256 = (bytes) => createHash("sha256").update(bytes).digest("hex");
function checkedPath(path, expectedType) {
  if (
    [...path].some(
      (character) =>
        character.charCodeAt(0) < 32 || character.charCodeAt(0) === 127,
    )
  )
    throw new Error("Unsafe source snapshot path");
  const fullPath = resolve(repository, path);
  const insidePath = relative(repository, fullPath);
  if (
    !insidePath ||
    insidePath === ".." ||
    insidePath.startsWith(".." + sep) ||
    isAbsolute(insidePath)
  ) {
    throw new Error("Source snapshot path is outside the repository");
  }
  let current = repository;
  const parts = insidePath.split(sep);
  for (let index = 0; index < parts.length; index += 1) {
    current = resolve(current, parts[index]);
    const stat = lstatSync(current);
    if (stat.isSymbolicLink())
      throw new Error("Symbolic links are not allowed in source snapshots");
    const isDirectory =
      index < parts.length - 1 || expectedType === "directory";
    if (isDirectory ? !stat.isDirectory() : !stat.isFile()) {
      throw new Error("A regular source file or directory is required");
    }
  }
  if (realpathSync(fullPath) !== fullPath)
    throw new Error("Source snapshot path is outside the repository");
  return fullPath;
}
function readSource(path) {
  const fullPath = checkedPath(path, "file");
  const descriptor = openSync(
    fullPath,
    constants.O_RDONLY | constants.O_NOFOLLOW,
  );
  try {
    const before = fstatSync(descriptor);
    if (!before.isFile()) throw new Error("A regular source file is required");
    const bytes = readFileSync(descriptor);
    const after = fstatSync(descriptor);
    checkedPath(path, "file");
    const current = lstatSync(fullPath);
    if (
      before.dev !== after.dev ||
      before.ino !== after.ino ||
      before.size !== after.size ||
      before.mtimeMs !== after.mtimeMs ||
      before.ctimeMs !== after.ctimeMs ||
      current.dev !== after.dev ||
      current.ino !== after.ino
    ) {
      throw new Error("Source changed during snapshot creation");
    }
    return { bytes, mode: before.mode & 0o777 };
  } finally {
    closeSync(descriptor);
  }
}
const buildConfig = readSource(
  "apps/android/app/build.gradle.kts",
).bytes.toString("utf8");
const versionName = buildConfig.match(/versionName\s*=\s*"([^"]+)"/u)?.[1];
const versionCode = Number(buildConfig.match(/versionCode\s*=\s*(\d+)/u)?.[1]);
if (
  !/^\d+\.\d+\.\d+-trial\.\d{8}\.\d+$/u.test(versionName ?? "") ||
  !Number.isSafeInteger(versionCode)
) {
  throw new Error("A traceable trial build version is required");
}
const apiBaseUrl = process.argv[2];
if (!/^http:\/\/(?:\d{1,3}\.){3}\d{1,3}:3000$/u.test(apiBaseUrl ?? "")) {
  throw new Error("Pass the independently verified LAN API URL");
}
const sourceRoots = [
  "apps",
  "packages",
  "database",
  "tooling",
  "product-showcase/ui-design-final",
  "docs",
];
for (const path of sourceRoots) checkedPath(path, "directory");
const candidates = execFileSync(
  "rg",
  [
    "--no-config",
    "--files",
    "--null",
    "--hidden",
    "apps",
    "packages",
    "database",
    "tooling",
    "product-showcase/ui-design-final",
  ],
  { encoding: "utf8" },
)
  .split("\0")
  .filter(Boolean);
const rootFiles = [
  ...execFileSync(
    "rg",
    ["--no-config", "--files", "--null", "-g", "*.md", "docs"],
    { encoding: "utf8" },
  )
    .split("\0")
    .filter(Boolean),
  ...execFileSync(
    "rg",
    [
      "--no-config",
      "--files",
      "--null",
      "-g",
      "*.md",
      "-g",
      "!node_modules",
      ".",
    ],
    { encoding: "utf8" },
  )
    .split("\0")
    .filter((path) => /^\.\/[^/]+\.md$/u.test(path)),
  "package.json",
  "pnpm-lock.yaml",
  "pnpm-workspace.yaml",
  "tsconfig.base.json",
  "eslint.config.mjs",
  "vitest.workspace.ts",
  ".node-version",
  ".editorconfig",
  ".gitignore",
  ".prettierignore",
];
const extensions = new Set([
  ".kt",
  ".kts",
  ".ts",
  ".tsx",
  ".js",
  ".mjs",
  ".mts",
  ".json",
  ".yaml",
  ".yml",
  ".xml",
  ".toml",
  ".md",
  ".css",
  ".html",
  ".sql",
  ".png",
  ".jpg",
  ".webp",
  ".svg",
  ".ttf",
  ".otf",
  ".py",
]);
const privateDataExtensions = new Set([
  ".json",
  ".yaml",
  ".yml",
  ".xml",
  ".toml",
]);
function isExcluded(path) {
  const name = basename(path).toLowerCase();
  if (
    /(?:^|\/)(?:\.env[^/]*|\.npmrc|\.local-data|node_modules|build|\.gradle)(?:\/|$)/iu.test(
      path,
    ) ||
    /^(?:gradle|local|key|keystore|signing)\.properties$/u.test(name) ||
    /\.(?:jks|keystore|pem|p12|pfx|key)$/u.test(name) ||
    /^id_(?:rsa|dsa|ecdsa|ed25519)(?:\.|$)/u.test(name)
  )
    return true;
  // Sensitive-name matching applies to data/config files, never implementation source names.
  if (!privateDataExtensions.has(extname(name))) return false;
  const tokens = path.replace(/([a-z0-9])([A-Z])/gu, "$1-$2").toLowerCase();
  return (
    /(?:^|[/_.-])(?:credentials?|secrets?|keys?|tokens?|sessions?|signing|keystore)(?:[/_.-]|$)/u.test(
      tokens,
    ) ||
    /(?:^|[/_.-])(?:service[_.-]?account|runtime[_.-]?config|local[_.-]?config|google[_.-]?services)(?:[/_.-]|$)/u.test(
      tokens,
    )
  );
}
const files = [
  ...new Set([
    ...candidates.filter(
      (path) =>
        extensions.has(extname(path)) ||
        basename(path) === "gradlew" ||
        path.endsWith("gradle-wrapper.jar") ||
        path.endsWith("gradle-wrapper.properties"),
    ),
    ...rootFiles,
  ]),
]
  .filter((path) => !isExcluded(path))
  .sort();
for (const path of files) checkedPath(path, "file");
// rg does not follow links. Also explicitly reject source-shaped links in represented
// source directories, instead of silently producing a snapshot missing those sources.
const representedDirectories = new Set(sourceRoots);
for (const path of files) {
  let parent = dirname(path);
  while (parent !== ".") {
    representedDirectories.add(parent);
    parent = dirname(parent);
  }
}
for (const directory of representedDirectories) {
  for (const entry of readdirSync(checkedPath(directory, "directory"), {
    withFileTypes: true,
  })) {
    if (
      entry.isSymbolicLink() &&
      !isExcluded(directory + "/" + entry.name) &&
      (extensions.has(extname(entry.name)) || entry.name === "gradlew")
    ) {
      throw new Error("Symbolic links are not allowed in source snapshots");
    }
  }
}

// Validate each output ancestor before creating descendants, never following a local-data symlink.
for (const path of [".local-data", ".local-data/releases"]) {
  const fullPath = resolve(repository, path);
  if (!existsSync(fullPath)) mkdirSync(fullPath, { mode: 0o700 });
  checkedPath(path, "directory");
}
const outputDirectory = resolve(
  repository,
  ".local-data/releases/" + versionName,
);
if (existsSync(outputDirectory))
  throw new Error("Refusing to overwrite an existing release snapshot");
mkdirSync(outputDirectory, { mode: 0o700 });
const ownedDirectory = lstatSync(outputDirectory);
const stagingDirectory = mkdtempSync(outputDirectory + "/.source-staging-");
const manifestPath = outputDirectory + "/source-manifest.json";
const archivePath = outputDirectory + "/source.tar.gz";
const pendingArchivePath = outputDirectory + "/source.tar.gz.pending";
try {
  // Archive immutable copies, not moving working-tree files. Only allowlisted bytes are read.
  const entries = files.map((path) => {
    const { bytes, mode } = readSource(path);
    if (
      path === "apps/android/app/build.gradle.kts" &&
      sha256(bytes) !== sha256(buildConfig)
    ) {
      throw new Error("Build version source changed during snapshot creation");
    }
    const stagedPath = resolve(stagingDirectory, path);
    mkdirSync(dirname(stagedPath), { recursive: true, mode: 0o700 });
    writeFileSync(stagedPath, bytes, { flag: "wx", mode });
    return { path, sha256: sha256(bytes) };
  });
  const sourceFingerprint = sha256(JSON.stringify(entries));
  execFileSync(
    "/usr/bin/tar",
    ["-czf", pendingArchivePath, "-C", stagingDirectory, "-T", "-"],
    {
      input: files.join("\n") + "\n",
      env: { ...process.env, COPYFILE_DISABLE: "1" },
    },
  );
  const archivedPaths = execFileSync(
    "/usr/bin/tar",
    ["-tzf", pendingArchivePath],
    { encoding: "utf8" },
  )
    .trimEnd()
    .split("\n");
  if (JSON.stringify(archivedPaths) !== JSON.stringify(files))
    throw new Error("Unstable source archive entries");
  for (const entry of entries) {
    if (
      sha256(readSource(entry.path).bytes) !== entry.sha256 ||
      sha256(
        readSource(relative(repository, resolve(stagingDirectory, entry.path)))
          .bytes,
      ) !== entry.sha256
    ) {
      throw new Error("Source changed during snapshot creation");
    }
  }
  checkedPath(relative(repository, outputDirectory), "directory");
  const manifest = {
    versionName,
    versionCode,
    apiBaseUrl,
    sourceFingerprint,
    entries,
    configurationOmissions: [
      ".npmrc",
      "**/.npmrc",
      "**/local.properties",
      "**/gradle.properties",
      "**/.env*",
      "runtime credentials and signing material",
    ],
    reproducibilityLimitations: [
      "Source-only snapshot: private build configuration, SDK paths, runtime data, credentials and signing keys are intentionally omitted without reading their values.",
      "Rebuilding requires independently provisioned tools, non-secret project build settings and authorized credentials/signing configuration; this archive is not a complete private build environment.",
    ],
  };
  renameSync(pendingArchivePath, archivePath);
  writeFileSync(manifestPath, JSON.stringify(manifest, null, 2), {
    flag: "wx",
    mode: 0o600,
  });
  console.log(
    JSON.stringify({
      manifestPath,
      archivePath,
      sourceFingerprint,
      fileCount: files.length,
      archiveSha256: sha256(readFileSync(archivePath)),
    }),
  );
} catch (error) {
  // Remove only artifacts created by this invocation, never a previous release.
  const current = lstatSync(outputDirectory);
  if (
    !current.isSymbolicLink() &&
    current.dev === ownedDirectory.dev &&
    current.ino === ownedDirectory.ino
  ) {
    for (const path of [manifestPath, archivePath, pendingArchivePath]) {
      if (existsSync(path)) unlinkSync(path);
    }
  }
  throw error;
} finally {
  const current = lstatSync(outputDirectory);
  if (
    !current.isSymbolicLink() &&
    current.dev === ownedDirectory.dev &&
    current.ino === ownedDirectory.ino
  ) {
    rmSync(stagingDirectory, { recursive: true, force: true });
    if (!existsSync(manifestPath)) rmdirSync(outputDirectory);
  }
}
