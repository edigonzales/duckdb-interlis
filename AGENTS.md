# AGENTS.md

Toolchain for development on macOS ARM64.

## Paths

| Tool | Location |
|---|---|
| GraalVM JDK | `/Users/stefan/.sdkman/candidates/java/25.0.3-graal` |
| `native-image` | `/Users/stefan/.sdkman/candidates/java/25.0.3-graal/bin/native-image` |
| DuckDB CLI | `~/bin/duckdb` (v1.5.3) |
| CMake | `~/cmake-4.1.0/CMake.app/Contents/bin/cmake` |

## Environment

Source `scripts/env.sh` before running builds or tests:

```bash
source scripts/env.sh
```

`scripts/env.sh` is local (gitignored). Template: `scripts/env.example.sh`.

## Build Commands

| Script | Purpose |
|---|---|
| `scripts/doctor.sh` | Verify toolchain |
| `scripts/build-java.sh` | Compile and test Java |
| `scripts/build-native.sh` | Build GraalVM native shared library |
| `scripts/build-extension.sh` | Build DuckDB C API extension |
| `scripts/build-all.sh` | Full build |
| `scripts/dev-duckdb.sh` | Start DuckDB with extension loaded |
| `scripts/smoke-test.sh` | Run smoke tests |
| `scripts/download-testdata.sh` | Download external test data |

## Important

- DuckDB version: **1.5.3** (pinned)
- Extension loading requires `-unsigned` flag
- Never commit `scripts/env.sh`
