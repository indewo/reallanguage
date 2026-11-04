import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import test from "node:test";

const workspace = fileURLToPath(new URL("../", import.meta.url));
function preflight(development: boolean) {
  return spawnSync(
    process.execPath,
    [
      "node_modules/.pnpm/tsx@4.23.13/node_modules/tsx/dist/cli.mjs",
      "--tsconfig",
      "apps/api/tsconfig.json",
      "tooling/check-live-formation.mts",
      "--explanation-runtime-preflight-only",
    ],
    {
      cwd: workspace,
      encoding: "utf8",
      timeout: 30_000,
      env: {
        ...process.env,
        NODE_OPTIONS: development ? "--conditions=development" : "",
        OPENAI_API_KEY: "",
      },
    },
  );
}

test("actual default exports must prove compatibility or stop before credentials or accounts", () => {
  const result = preflight(false);
  if (result.status === 0) {
    // A later normal build may refresh dist; do not incorrectly reject a compatible release.
    assert.match(result.stdout, /isolated-runtime-compatible/);
    assert.match(result.stdout, /"detailsPreserved":true/);
  } else {
    assert.equal(result.status, 1);
    assert.match(result.stdout, /isolated-runtime-incompatible/);
  }
  assert.doesNotMatch(
    result.stdout,
    /"stage":"registration"|isolated-provider-usage/,
  );
});

test("actual development exports preserve details without credentials or accounts", () => {
  const result = preflight(true);
  assert.equal(result.status, 0);
  assert.match(result.stdout, /isolated-runtime-compatible/);
  assert.match(result.stdout, /"detailsPreserved":true/);
  assert.match(result.stdout, /"targetEvidencePreserved":true/);
  assert.doesNotMatch(
    result.stdout,
    /"stage":"registration"|isolated-provider-usage/,
  );
});
