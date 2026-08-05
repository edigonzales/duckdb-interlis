# Native MVP implementation log

## Phase 0 — baseline

Date: 2026-08-05
Platform: macOS 15.x, ARM64 (Apple Silicon)
Compiler: AppleClang 17.0.0.17000013
CMake: 4.1.0 (`/Users/stefan/cmake-4.1.0/CMake.app/Contents/bin/cmake`)
Node.js: 22.13.0
Java/GraalVM: Oracle GraalVM 25.0.3
Emscripten: 3.1.64
DuckDB CLI: v1.5.3 (reported commit `14eca11bd9`)
GEOS: not installed in the baseline environment; no `geos_c.h`, `geos-config`, or GEOS package was found.

### Repository revisions

| Repository | Branch | HEAD before Phase 0 | Existing worktree state |
|---|---|---|---|
| `ilic-fork` | `main` | `bd4625485a990629366a770d630db5d9ffa15cc5` | pre-existing untracked `ilic-0.9.10-release-coding-agent-spec-updated.md` and `ilic-p2-compiler-context-spec.md` |
| `iox-cpp` | `main` | `ff098e26b0da7697208ad02d3fbb48d60810d300` | pre-existing untracked `duckdb-interlis-native-mvp-coding-agent-spec-snapshot.md` |
| `duckdb-interlis` | `main` | `a408e60fdd63f7cd7c0e55e1cb2caa8d5693b107` | pre-existing untracked `duckdb-interlis-native-mvp-coding-agent-spec-snapshot.md` |

The three untracked files listed above are preserved and are not part of this implementation.
The excluded projects `interlis-language-tools` and `interlis-web-ide` were not opened, built,
tested, or changed.

### Baseline component versions

| Component | Baseline value |
|---|---|
| `ilic` CMake/package version | `0.9.10` |
| `iox-cpp` CMake version | `0.2.0` |
| `duckdb-interlis` VERSION | `0.1.0-dev` |
| DuckDB extension template commit | not applicable to the pre-migration C extension; to be pinned in Phase 7 |

### Commands and results

#### `ilic-fork`

The exact requested command against the existing `build/baseline` directory could not
configure because that pre-existing cache selected Ninja, which is not installed:

```text
CMake was unable to find a build program corresponding to "Ninja"
```

The cache was not deleted. The equivalent non-destructive Makefiles baseline was run in
`build/baseline-make`:

```sh
/Users/stefan/cmake-4.1.0/CMake.app/Contents/bin/cmake -S . -B build/baseline-make -G 'Unix Makefiles' \
  -DCMAKE_BUILD_TYPE=Debug -DBUILD_TESTING=ON -DILIC_ENABLE_NATIVE_REPOSITORY=OFF
/Users/stefan/cmake-4.1.0/CMake.app/Contents/bin/cmake --build build/baseline-make --parallel
/Users/stefan/cmake-4.1.0/CMake.app/Contents/bin/ctest --test-dir build/baseline-make --output-on-failure
./scripts/build-wasm.sh
node --test --test-concurrency=1 packages/compiler-wasm/test/*.test.mjs
```

Results:

- Native build: passed.
- Native CTest: 131/132 passed.
- Known failure: `ilic_docs_links`; the repository's documentation canary rejects the
  pre-existing untracked file `ilic-p2-compiler-context-spec.md` as a phase-prefixed artifact.
- WASM build: passed with Emscripten 3.1.64.
- WASM/Node tests: 15 passed, 1 skipped, 0 failed. The skipped test is the existing
  native/WASM editor snapshot comparison because its native snapshot driver was not built.
- Compiler warnings are pre-existing warnings in legacy output/parser sources.

#### `iox-cpp`

```sh
/Users/stefan/cmake-4.1.0/CMake.app/Contents/bin/cmake -S . -B build/baseline -G 'Unix Makefiles' \
  -DCMAKE_BUILD_TYPE=Debug -DBUILD_TESTING=ON -DIOX_ENABLE_ILIC=ON \
  -DIOX_ENABLE_GEOS=ON -DIOX_ILIC_SOURCE_DIR=../ilic-fork
/Users/stefan/cmake-4.1.0/CMake.app/Contents/bin/cmake --build build/baseline --parallel
/Users/stefan/cmake-4.1.0/CMake.app/Contents/bin/ctest --test-dir build/baseline --output-on-failure
```

Results:

- Configure: passed, with a warning that the current checkout does not yet use `IOX_ENABLE_GEOS`.
- Native build: passed.
- CTest: 40/40 passed.
- GEOS was not linked because the current checkout has no GEOS option and the baseline host
  has no GEOS development installation.

#### `duckdb-interlis`

```sh
source scripts/env.sh
./scripts/doctor.sh
./scripts/build-java.sh
./scripts/build-native.sh
./scripts/build-extension.sh
./scripts/smoke-test.sh
```

Results:

- Toolchain doctor: 22 passed, 0 failed.
- Java/GraalVM tests: passed.
- GraalVM native shared library: passed; produced the existing
  `libduckdb_ili_native.dylib` architecture.
- C extension build and metadata append: passed.
- Smoke test: passed.
- The smoke test confirms the pre-migration public surface still exposes validator and
  legacy read functions; removing those is part of the native migration, not a baseline claim.

### Decisions and baseline deviations

1. Existing generated build directories were reused where possible. No build cache was
   deleted, because the specification forbids destructive cleanup and the source trees
   contained pre-existing user files.
2. The Makefiles generator was used for the `ilic-fork` baseline because the requested
   `build/baseline` cache was stale and referenced unavailable Ninja.
3. GEOS installation and the DuckDB extension-template revision are deferred until the
   phases that introduce those dependencies. They are intentionally recorded as unknown
   rather than guessed.
4. The pre-existing documentation-canary failure is not fixed in Phase 0 because its
   triggering file is untracked user work. Later phase documentation must avoid changing
   or deleting that file; the failure will be re-evaluated after the native work is committed.
