# Release and publishing

The extension is currently built as a DuckDB 1.5.5 loadable extension. The
project release repository publishes one binary per DuckDB platform below a
product-version directory:

```text
v1.5.5/
  linux_amd64/interlis.duckdb_extension
  osx_arm64/interlis.duckdb_extension
  windows_amd64/interlis.duckdb_extension
```

GitHub Release assets use platform-specific names so the binaries remain
unambiguous outside the repository directory structure:

```text
interlis-linux-x86_64.duckdb_extension
interlis-linux-x86_64.duckdb_extension.sha256
interlis-osx-aarch64.duckdb_extension
interlis-osx-aarch64.duckdb_extension.sha256
interlis-windows-x86_64.duckdb_extension
interlis-windows-x86_64.duckdb_extension.sha256
```

Build locally with:

```sh
source scripts/env.sh
scripts/build-extension.sh
```

The CI workflow builds the loadable INTERLIS extension and the native SQLLogicTest
runner, runs the SQLLogicTests, produces SHA-256 sidecar files, and uploads
platform artifacts. Deploy and GitHub-release jobs run for a `v*` tag, a manual
workflow dispatch, or an explicit `release:` commit on `main`. Manual dispatches
create a draft release; tag and `release:` runs publish directly. This repository
does not publish from a local development run.

The root vcpkg manifest and overlay ports are also the source-build contract for
the DuckDB Community Extensions infrastructure. Community publishing is kept
separate from this project's unsigned release repository.

Before a release:

1. update `VERSION` and `CHANGELOG.md`;
2. verify the ilic, iox-cpp, GEOS, and runtime DuckDB revisions reported by
   `interlis_components()`;
3. run Debug and Release builds plus the native SQLLogicTests;
4. inspect the generated artifact and checksum for every platform;
5. publish only after the exact DuckDB product-version directory is selected.

The project-hosted extension is unsigned for local testing and must be loaded
with DuckDB's `-unsigned` option. A Community Extensions build is signed and
published by DuckDB's infrastructure instead.
