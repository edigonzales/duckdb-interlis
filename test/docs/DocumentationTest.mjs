import assert from "node:assert/strict";
import { existsSync, readFileSync, readdirSync, statSync } from "node:fs";
import { dirname, join, resolve } from "node:path";

const root = resolve(process.argv[2] ?? ".");
const retired = [
  "native-mvp",
  "native-architecture.md",
  "native-extension-bootstrap.md",
  "migration-from-graalvm.md",
  "getting-started.md",
  "VERSIONS.md",
  "legacy-registry-restore.md",
  "AGENTS.md",
  "spec/",
];

function markdownFiles(directory) {
  if (!existsSync(directory)) return [];
  return readdirSync(directory).flatMap((name) => {
    if (["build", "extension-ci-tools", "node_modules"].includes(name)) return [];
    const path = join(directory, name);
    return statSync(path).isDirectory() ? markdownFiles(path) : path.endsWith(".md") ? [path] : [];
  });
}

const files = [
  join(root, "README.md"),
  ...markdownFiles(join(root, "docs")),
  join(root, "vcpkg/README.md"),
  join(root, "sql/examples/README.md"),
];

for (const path of files) {
  const text = readFileSync(path, "utf8");
  assert.equal((text.match(/^```/gmu) ?? []).length % 2, 0, `${path}: offener Codeblock`);
  assert.doesNotMatch(text, /^#{1,6}\s+Phase\s+\d+/gimu, `${path}: phasenbezogene Überschrift`);
  for (const name of retired) assert.ok(!text.includes(name), `${path}: veralteter Verweis ${name}`);
  const prose = text.replace(/^```[\s\S]*?^```/gmu, "");
  for (const match of prose.matchAll(/\[[^\]]*\]\(([^)]+)\)/gu)) {
    let target = match[1].trim();
    if (target.startsWith("<") && target.endsWith(">")) target = target.slice(1, -1);
    if (!target || /^(?:https?:|mailto:)/u.test(target)) continue;
    const local = target.split("#", 1)[0];
    if (local) assert.ok(existsSync(resolve(dirname(path), decodeURIComponent(local))), `${path}: defekter Link ${target}`);
  }
}
const release = readFileSync(join(root, "docs/release.md"), "utf8");
for (const claim of [
  "extensions/interlis/description.yml",
  "community-extensions/blob/main/UPDATING.md",
  "duckdb.org/community_extensions/development",
  "ref_next",
  "0.2.1",
]) assert.ok(release.includes(claim), `release.md: Community-Release-Angabe fehlt: ${claim}`);
console.log(`validated ${files.length} documentation pages`);
