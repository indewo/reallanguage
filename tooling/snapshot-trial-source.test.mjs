import assert from "node:assert/strict";
import { execFileSync, spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import {
  existsSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  rmSync,
  symlinkSync,
  writeFileSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { afterEach, test } from "node:test";

const script = resolve(
  dirname(fileURLToPath(import.meta.url)),
  "snapshot-trial-source.mjs",
);
const roots = [];
const fixture = () => {
  const outer = mkdtempSync(join(tmpdir(), "rl-source-snapshot-test-"));
  roots.push(outer);
  const root = join(outer, "repo");
  mkdirSync(root);
  const put = (path, body = "isolated non-secret fixture\n") => {
    const full = join(root, path);
    mkdirSync(dirname(full), { recursive: true });
    writeFileSync(full, body);
  };
  for (const folder of [
    "apps",
    "packages",
    "database",
    "tooling",
    "docs",
    "product-showcase/ui-design-final",
  ])
    mkdirSync(join(root, folder), { recursive: true });
  put(
    "apps/android/app/build.gradle.kts",
    'versionCode = 14\nversionName = "1.26.0-trial.20260909.13"\n',
  );
  for (const path of [
    "README.md",
    "docs/TEST.md",
    "package.json",
    "pnpm-lock.yaml",
    "pnpm-workspace.yaml",
    "tsconfig.base.json",
    "eslint.config.mjs",
    "vitest.workspace.ts",
    ".node-version",
    ".npmrc",
    ".editorconfig",
    ".gitignore",
    ".prettierignore",
  ])
    put(path, path === ".gitignore" ? "" : "isolated fixture\n");
  put("packages/source.ts", "export const stable = 1;\n");
  const output = join(root, ".local-data/releases/1.26.0-trial.20260909.13");
  const run = (preload) =>
    spawnSync(
      process.execPath,
      [
        ...(preload ? ["--import", preload] : []),
        script,
        "http://192.168.1.5:3000",
      ],
      {
        cwd: root,
        encoding: "utf8",
        env: { ...process.env, COPYFILE_DISABLE: "1" },
      },
    );
  const manifest = () =>
    JSON.parse(readFileSync(join(output, "source-manifest.json"), "utf8"));
  const paths = () =>
    execFileSync("/usr/bin/tar", ["-tzf", join(output, "source.tar.gz")], {
      encoding: "utf8",
    })
      .trim()
      .split("\n");
  return { outer, root, put, run, output, manifest, paths };
};
afterEach(() => {
  for (const root of roots.splice(0))
    rmSync(root, { recursive: true, force: true });
});

test("omits npmrc and private machine properties without inspecting their values", () => {
  const f = fixture();
  f.put(".npmrc", "//isolated.invalid/:_authToken=FAKE_SENTINEL_ONLY\n");
  f.put(
    "apps/android/gradle.properties",
    "isolatedFakePassword=FAKE_SENTINEL_ONLY\n",
  );
  f.put(
    "apps/android/local.properties",
    "isolatedFakePath=FAKE_SENTINEL_ONLY\n",
  );
  const result = f.run();
  assert.equal(result.status, 0, result.stderr);
  assert.ok(!f.paths().includes(".npmrc"));
  assert.ok(
    !f
      .paths()
      .some((path) => /(?:^|\/)(?:gradle|local)\.properties$/u.test(path)),
  );
  assert.ok(f.manifest().configurationOmissions.includes(".npmrc"));
  assert.ok(f.manifest().reproducibilityLimitations.length > 0);
});

test("excludes sensitive runtime and signing files but retains similarly named source implementations", () => {
  const f = fixture();
  const sensitive = [
    "packages/credentials.json",
    "apps/api/keys.json",
    "apps/api/.env.production.json",
    "apps/api/service-account.json",
    "apps/api/runtime-config.json",
    "apps/api/oauth-token.json",
    "apps/android/signing-config.xml",
    "packages/local-config.yaml",
    "tooling/session.json",
    "packages/key.pfx",
    "packages/private.key",
    "packages/id_rsa",
    "packages/signing.keystore",
  ];
  for (const path of sensitive) f.put(path, "FAKE_SENTINEL_ONLY\n");
  for (const path of [
    "apps/android/SessionCredentialStore.kt",
    "packages/session-token.test.ts",
    "packages/crypto-keys.ts",
  ])
    f.put(path);
  const result = f.run();
  assert.equal(result.status, 0, result.stderr);
  const paths = f.paths();
  for (const path of sensitive) assert.ok(!paths.includes(path), path);
  for (const path of [
    "apps/android/SessionCredentialStore.kt",
    "packages/session-token.test.ts",
    "packages/crypto-keys.ts",
  ])
    assert.ok(paths.includes(path), path);
});

test("rejects a source symlink without hashing or archiving an outside target", () => {
  const f = fixture();
  const outside = join(f.outer, "outside.ts");
  writeFileSync(outside, "FAKE_OUTSIDE_SENTINEL\n");
  symlinkSync(outside, join(f.root, "packages/outside.ts"));
  const result = f.run();
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /symbolic|outside|regular/iu);
  assert.ok(!existsSync(join(f.output, "source-manifest.json")));
});

test("rejects a release parent symlink outside the workspace", () => {
  const f = fixture();
  const outside = join(f.outer, "outside-output");
  mkdirSync(outside);
  symlinkSync(outside, join(f.root, ".local-data"));
  const result = f.run();
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /symbolic|outside|regular/iu);
  assert.ok(
    !existsSync(
      join(outside, "releases/1.26.0-trial.20260909.13/source.tar.gz"),
    ),
  );
});

test("rejects a symlinked version configuration before reading it", () => {
  const f = fixture();
  const outside = join(f.outer, "outside-build.gradle.kts");
  writeFileSync(
    outside,
    'versionCode = 14\nversionName = "1.26.0-trial.20260909.13"\n',
  );
  rmSync(join(f.root, "apps/android/app/build.gradle.kts"));
  symlinkSync(outside, join(f.root, "apps/android/app/build.gradle.kts"));
  const result = f.run();
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /symbolic|outside|regular/iu);
  assert.ok(!existsSync(join(f.output, "source-manifest.json")));
});

test("rejects a symlinked source root instead of traversing external directories", () => {
  const f = fixture();
  const outside = join(f.outer, "outside-packages");
  mkdirSync(outside);
  writeFileSync(join(outside, "external.ts"), "FAKE_OUTSIDE_SENTINEL\n");
  rmSync(join(f.root, "packages"), { recursive: true });
  symlinkSync(outside, join(f.root, "packages"));
  const result = f.run();
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /symbolic|outside|regular/iu);
  assert.ok(!existsSync(join(f.output, "source-manifest.json")));
});

test("refuses changed source between hashing and archive creation without publishing a false manifest", () => {
  const f = fixture();
  const preload = join(f.outer, "concurrent-change.mjs");
  writeFileSync(
    preload,
    `import childProcess from "node:child_process"; import {syncBuiltinESMExports} from "node:module"; import {writeFileSync} from "node:fs"; const original=childProcess.execFileSync; childProcess.execFileSync=function(command,args,options){if(command==="/usr/bin/tar"&&args.includes("-czf"))writeFileSync(${JSON.stringify(join(f.root, "packages/source.ts"))},"export const changed = 2;\\n");return original.call(this,command,args,options);};syncBuiltinESMExports();`,
  );
  const result = f.run(preload);
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /changed during|unstable/iu);
  assert.ok(!existsSync(join(f.output, "source-manifest.json")));
});

test("does not overwrite an existing release or its files", () => {
  const f = fixture();
  mkdirSync(f.output, { recursive: true });
  const original = "PREVIOUS_RELEASE_SENTINEL";
  writeFileSync(join(f.output, "source-manifest.json"), original);
  const result = f.run();
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /overwrite/iu);
  assert.equal(
    readFileSync(join(f.output, "source-manifest.json"), "utf8"),
    original,
  );
});

test("refuses a changed build version between metadata parsing and source hashing", () => {
  const f = fixture();
  const preload = join(f.outer, "concurrent-version-change.mjs");
  writeFileSync(
    preload,
    `import childProcess from "node:child_process"; import {syncBuiltinESMExports} from "node:module"; import {writeFileSync} from "node:fs"; const original=childProcess.execFileSync; let changed=false; childProcess.execFileSync=function(command,args,options){if(command==="rg"&&!changed){changed=true;writeFileSync(${JSON.stringify(join(f.root, "apps/android/app/build.gradle.kts"))},'versionCode = 15\\nversionName = "1.26.0-trial.20260909.14"\\n');}return original.call(this,command,args,options);};syncBuiltinESMExports();`,
  );
  const result = f.run(preload);
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /changed during|unstable/iu);
  assert.ok(!existsSync(join(f.output, "source-manifest.json")));
});

test("keeps manifest fingerprint and actual archive entries consistent for non-sensitive source", () => {
  const f = fixture();
  const result = f.run();
  assert.equal(result.status, 0, result.stderr);
  const manifest = f.manifest();
  assert.equal(manifest.versionCode, 14);
  assert.equal(manifest.versionName, "1.26.0-trial.20260909.13");
  assert.equal(
    manifest.sourceFingerprint,
    createHash("sha256").update(JSON.stringify(manifest.entries)).digest("hex"),
  );
  assert.deepEqual(
    f.paths().sort(),
    manifest.entries.map(({ path }) => path).sort(),
  );
  for (const entry of manifest.entries) {
    const archivedBytes = execFileSync("/usr/bin/tar", [
      "-xOzf",
      join(f.output, "source.tar.gz"),
      entry.path,
    ]);
    assert.equal(
      createHash("sha256").update(archivedBytes).digest("hex"),
      entry.sha256,
    );
  }
});
