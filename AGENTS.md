# AGENTS.md

Native C++ toolchain for macOS ARM64.

## Paths

| Tool | Location |
|---|---|
| DuckDB CLI | `~/bin/duckdb` (v1.5.3) |
| CMake | `~/cmake-4.1.0/CMake.app/Contents/bin/cmake` |
| ilic-fork | `/Users/stefan/sources/ilic-fork` |
| iox-cpp | `/Users/stefan/sources/iox-cpp` |

## Environment

Source `scripts/env.sh` before local builds and tests:

```bash
source scripts/env.sh
```

The file is local and gitignored. Use `scripts/env.example.sh` as its template.
Set `INTERLIS_ILIC_SOURCE_DIR`, `INTERLIS_IOX_SOURCE_DIR`, and
`VCPKG_TOOLCHAIN_PATH` for a local dependency build.

## Build commands

| Script | Purpose |
|---|---|
| `scripts/doctor.sh` | Verify native toolchain and dependencies |
| `scripts/build-extension.sh` | Build the Release loadable extension |
| `scripts/build-all.sh` | Build Debug/Release and run SQLLogicTests |
| `scripts/dev-duckdb.sh` | Start DuckDB with the native extension loaded |
| `scripts/smoke-test.sh` | Run the native smoke SQL |
| `scripts/download-testdata.sh` | Download optional external fixtures |

Equivalent extension-template targets are `make debug`, `make release`, and
`make test_release`.

## Important

- DuckDB version: **1.5.3** (pinned).
- Extension loading requires the `-unsigned` flag.
- Models are local `.ili` files or non-recursive local directories in the MVP.
- No Java, GraalVM, validator, remote model repository, or `ATTACH` support is
  part of the native MVP.
- Never commit `scripts/env.sh`.
