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

## Phase 1 — `ilic` 0.10.0-SNAPSHOT line

Repository revision: `ilic-fork` commit `3aa3b95` (`phase 1: begin ilic 0.10.0 snapshot line`).

The compiler now has one source version contract, `0.10.0-SNAPSHOT`, derived from the
numeric CMake project version `0.10.0` and the `SNAPSHOT` qualifier. The native CLI,
C++/C version APIs, WASM wrapper, and the three source npm manifests use that source
version. Concrete package artifacts use an immutable UTC timestamp and optional numeric
build ID, for example `0.10.0-SNAPSHOT.20260805213000.123456789`. Stable npm staging is
explicitly rejected while the source line carries the snapshot qualifier.

The package verifier writes the ignored `artifacts/compiler-wasm-snapshot.json` manifest.
The verified local artifact recorded during this phase was:

```json
{
  "baseVersion": "0.10.0-SNAPSHOT",
  "snapshotId": "20260805213000.123456789",
  "createdAt": "2026-08-05T21:30:00Z",
  "resolvedVersion": "0.10.0-SNAPSHOT.20260805213000.123456789",
  "sourceRevision": "3aa3b95bb05b602e02041d4292255496cb66d302",
  "tarball": "ilic-compiler-wasm-0.10.0-SNAPSHOT.20260805213000.123456789.tgz",
  "sha256": "764784a1e497a4b1e7bf861a3d90e0099dffb40fd081ed7f3cc7f84af6b8512a"
}
```

### Phase 1 verification

- Focused npm preparation/release/version tests: 19 passed, 0 failed.
- Fresh Emscripten WASM build with the concrete snapshot version: passed.
- Packed and installed three-package npm consumer smoke test: passed.
- Debug native build: passed; `build/native-mvp/ilic -version` reported
  `ilic 0.10.0-SNAPSHOT`.
- Native version-contract CTest: 2/2 passed.
- Full native CTest: 131/132 passed. The sole known failure remains the documentation
  canary rejecting the pre-existing untracked `ilic-p2-compiler-context-spec.md`.

No npm package was published, no Git tag or release was created, and neither excluded
repository (`interlis-language-tools`, `interlis-web-ide`) was changed.

## Phase 5 — iox minimal IOM path access

Repository revision: `iox-cpp` commit `36e3a7d` (`phase 5: add minimal IOM path access`).

The native core now provides a small parsed `IomPath` selector with first, one-based
index, and wildcard steps. Primitive reads return copied values and zero-based indexes;
single primitive writes validate the model, reject wildcard or ambiguous matches, and
recursively write nested child objects back through the parent object. Missing names,
invalid selectors, type mismatches, and invalid write targets use the existing iox error
categories. Documentation and focused tests were added.

### Phase 5 verification

- ilic-enabled Debug build and full CTest: 35/35 passed.
- Focused `iox-test-core` IomPath cases: 5/5 passed.
- Fresh Emscripten WASM build: passed.
- WASM/Node tests: 10/10 passed.
- `git diff --check`: passed.

No package publication, Git tag/release, push, or excluded-repository change was made.

## Phase 6 — iox native IOM geometry projection

Repository revision: `iox-cpp` commit `dcd2002` (`phase 6: add native IOM geometry projection`).

The native geometry module now converts model-free IOM objects into deterministic WKB
for coordinates, multi-coordinates, polylines, directed variants, surfaces, areas, and
multi-geometries. It supports straight and circular-arc segments, strict dimensional
and numeric validation, holes, configurable arc sagitta, and reports whether arcs were
approximated. GEOS integration is optional and reentrant behind `IOX_ENABLE_GEOS`; no
GEOS download or fallback was added. The converter and WKB writer are also compiled in
the WASM build with GEOS disabled.

### Phase 6 verification

- ilic-enabled Debug build and full CTest: 36/36 passed.
- Focused geometry cases: 5/5 passed.
- DuckDB 1.5.3 CLI decoded representative 2D and ISO 3D WKB as `POINT` and
  `POINT Z (1 2 3)`.
- GEOS-enabled configuration failed clearly because no local GEOS CMake package is
  installed; GEOS remained disabled as required by the no-download policy.
- Fresh Emscripten WASM build: passed.
- WASM/Node tests: 10/10 passed.
- `git diff --check`: passed after restoring the generated whitespace-only wrapper.

No Java golden fixture generation, package publication, Git tag/release, push, or
excluded-repository change was made.

## Phase 7 — native DuckDB C++ extension bootstrap

Repository revision: this phase's `phase 7: bootstrap native DuckDB C++ extension`
commit.

The repository now has the DuckDB extension-template structure at the root, with
explicit `InterlisExtension` loading and registration seams for version, model, XTF
scan, value, and update functions. The existing GraalVM/C-API tree remains intact for
the migration comparison phase. DuckDB `v1.5.3` is pinned to
`14eca11bd9d4a0de2ea0f078be588a9c1c5b279c`; the extension-template reference is
`cfaf3e236008e782d27f4341b0ee036002d0a449`; extension-ci-tools `v1.5.3` is pinned to
`4b3b37b0c9de00da54e1765d65abfea3f94617f4`; and the vcpkg baseline is
`ce613c41372b23b1f51333815feb3edd87ef8a8b`. Native builds enable iox ilic, GEOS, and
JSON support exactly once, passing the selected local ilic source into iox to avoid a
second `ilic-core` registration.

### Phase 7 verification

- Pinned vcpkg installed GEOS `3.13.0` for `arm64-osx`; the host had no `pkg-config`,
  so only a temporary local validation shim was used for vcpkg's package-file check.
  The shim is not part of the repository.
- Debug build with local `/Users/stefan/sources/ilic-fork` and
  `/Users/stefan/sources/iox-cpp`: passed.
- Release build with the same local sources: passed.
- Debug SQLLogicTest: 1/1 passed.
- Release SQLLogicTest: 1/1 passed.
- Direct Debug DuckDB load with `-unsigned` returned the deterministic
  `interlis_version()` component line for DuckDB `1.5.3`.
- `git diff --cached --check`: passed.

The environment did not provide Ninja, so the exact verification used the pinned
CMake `4.1.0` Unix Makefiles generator; the template-compatible `GEN=ninja` commands
remain documented for installations that provide Ninja. No legacy implementation was
deleted, and no package publication, Git tag/release, push, or excluded-repository
change was made.

## Phase 8 — local INTERLIS model sources and compiled model

Repository revision: this phase's `phase 8: compile local INTERLIS model sources`
commit.

`ModelSourceResolver` now accepts local regular `.ili` files and non-recursive
directories. It reads source bytes unchanged, assigns normalized absolute paths as
deterministic URIs, sorts directory entries, removes duplicate normalized paths, and
uses every resolved file as a `CompilationRequest` root. HTTP(S) model sources fail
with the exact native-MVP message rather than falling back to a repository. Empty
directories, missing files, non-`.ili` files, and non-regular files produce clear
DuckDB input errors.

`CompiledModel` owns `ilic::ModelCompilation` and then builds `iox::ilic::IlicModelIndex`
from the retained `MetaModelStore`. Compiler failures are summarized as a bounded
DuckDB exception with source location, code/message, and total detail count. No global
model cache was introduced.

### Phase 8 verification

- Debug extension build with local `/Users/stefan/sources/ilic-fork` and
  `/Users/stefan/sources/iox-cpp`: passed.
- Optional native `interlis_model_source_test`: passed; it covers individual files,
  directory filtering/order, duplicate removal, missing and URL errors, invalid
  compilation, two model sources, and index/store lifetime.
- `git diff --check`: passed.

The default build keeps the optional native test target disabled. No remote model
download, legacy implementation deletion, package publication, Git tag/release, push,
or excluded-repository change was made.

## Phase 4 — iox compact ilic property descriptors

Repository revision: `iox-cpp` commit `9fad2be` (`phase 4: expose compact ilic property descriptors`).

The native ilic adapter now exposes standalone value-only `PropertyDescriptor` and
`GeometryDescriptor` APIs. The index copies semantic property FQNs, translated transfer
names, cardinalities, role metadata, primitive kind classification, and geometry metadata
without retaining pointers into the source `MetaModelStore`. Geometry values include line
kind, coordinate dimension/domain, lexical and parsed `MaxOverlap`, standard/custom line
forms, the default straight-segment semantics for an empty line-form list, and line
attribute presence. Documentation and model-based coverage were added for local,
inherited, translated, 2D/3D, area/surface/polyline, integer/double, and store-lifetime
cases.

### Phase 4 verification

- ilic-enabled Debug build with the local `ilic-fork` `0.10.0-SNAPSHOT` source: passed.
- Full ilic-enabled CTest: 34/34 passed.
- Focused `iox-test-model-based`: 16/16 passed.
- `git diff --check`: passed.

No GEOS conversion, WASM model adapter, package publication, Git tag/release, or push was
performed in this phase.

## Phase 3 — `ilic` geometry metadata contract

Repository revision: `ilic-fork` commit `bfcf010ed8e0034a18559409fda1a030f6e79517`
(`phase 3: verify ilic geometry metadata contract`).

No new geometry abstraction was introduced in `ilic`. A direct native metamodel test
now verifies the existing `metamodel::LineType` representation: Surface and Area kinds,
two-axis `CoordType`, `MaxOverlap`, built-in `STRAIGHTS`/`ARCS` line forms, a local
Polyline type, a custom line form, and `LINE ATTRIBUTES` ownership. The fixture uses two
decimal places for its coordinate domain because the existing semantic checker requires
`MaxOverlap` and coordinate precision to match; parser and compiler semantics were not
changed.

### Phase 3 verification

- `ilic_geometry_metadata` targeted CTest: passed.
- Full Debug native build: passed.
- Full native CTest: 133/134 passed. The new geometry test and all existing tests passed;
  the sole failure remains the documentation canary rejecting the pre-existing untracked
  `ilic-p2-compiler-context-spec.md`.

No npm package was published, no Git tag or release was created, and neither excluded
repository (`interlis-language-tools`, `interlis-web-ide`) was changed.

## Phase 2 — owned native model compilation

Repository revision: `ilic-fork` commit `a54bf070ed03cf8648c287fd252625efa5e40673`
(`phase 2: expose owned native model compilation`).

The additive C++ API `ilic::ModelCompilation` now accepts in-memory `ModelSource`
values and a `CompilationRequest`, runs one private `detail::CompilerContext`, and
retains that context until the owning object is destroyed or move-assigned. Its
`metamodel::MetaModelStore` is therefore valid for native introspection after the
compilation call. Copying is disabled; move construction and move assignment preserve
ownership. Duplicate source URIs with different content are rejected, and `models()`
throws for failed compilations. The C-ABI, WASM JSON API, and existing
`CompilerSession` contract remain unchanged.

### Phase 2 verification

- Native `ilic_model_compilation` test: passed, covering store lifetime, move/copy
  semantics, failed compilation, duplicate URI rejection, and independent stores.
- Full Debug native build: passed.
- Full native CTest: 132/133 passed. The new test and all existing tests passed; the
  sole failure remains the documentation canary rejecting the pre-existing untracked
  `ilic-p2-compiler-context-spec.md`.
- Fresh Emscripten build including `ModelCompilation.cpp`: passed.
- WASM/Node tests: 15 passed, 1 skipped, 0 failed.

No npm package was published, no Git tag or release was created, and neither excluded
repository (`interlis-language-tools`, `interlis-web-ide`) was changed.
