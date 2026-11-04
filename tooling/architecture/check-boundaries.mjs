import { readFile, readdir } from "node:fs/promises";
import { extname, join, relative } from "node:path";

const roots = ["apps", "database", "packages"];
const violations = [];

const walk = async (directory) => {
  for (const entry of await readdir(directory, { withFileTypes: true })) {
    if (["dist", "build", "node_modules"].includes(entry.name)) continue;
    const path = join(directory, entry.name);
    if (entry.isDirectory()) await walk(path);
    else if ([".ts", ".tsx"].includes(extname(path))) {
      const content = await readFile(path, "utf8");
      if (
        /backend-modules\/src\/.+\/(application|domain|infrastructure|presentation)\//.test(
          content,
        )
      ) {
        violations.push(
          `${relative(".", path)} imports backend module internals`,
        );
      }
      if (
        /from ["'][^"']*infrastructure/.test(content) &&
        path.includes("/domain/")
      ) {
        violations.push(
          `${relative(".", path)} makes domain depend on infrastructure`,
        );
      }
    }
  }
};

for (const root of roots) await walk(root);
if (violations.length) {
  console.error(violations.join("\n"));
  process.exitCode = 1;
} else {
  console.info("Architecture boundaries are valid.");
}
