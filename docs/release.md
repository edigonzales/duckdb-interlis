# Release and publishing

The extension is built as a DuckDB 1.5.3 loadable extension. The repository
publishes one binary per DuckDB platform below a product-version directory:

```text
v1.5.3/
  linux_amd64/interlis.duckdb_extension
  linux_arm64/interlis.duckdb_extension
  osx_arm64/interlis.duckdb_extension
  windows_amd64/interlis.duckdb_extension
```

Build locally with:

```sh
source scripts/env.sh
scripts/build-extension.sh
```

The CI workflow builds the extension-template release target, runs the native
SQLLogicTests, produces SHA-256 sidecar files, and uploads platform artifacts.
The deploy and GitHub-release jobs are still gated by manual dispatch or a Git
tag. This repository does not publish from a local development run.

Before a release:

1. update `VERSION` and `CHANGELOG.md`;
2. verify the DuckDB, ilic, iox-cpp, and GEOS component revisions reported by
   `interlis_components()`;
3. run Debug and Release builds plus the native SQLLogicTests;
4. inspect the generated artifact and checksum for every platform;
5. publish only after the exact DuckDB product-version directory is selected.

The extension is currently unsigned for local testing and must be loaded with
DuckDB's `-unsigned` option. Distribution signing and repository credentials are
CI concerns, not local build steps.
