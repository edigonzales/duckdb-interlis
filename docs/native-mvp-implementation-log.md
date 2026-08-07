# Native MVP implementation log

This is the phase log for the local native MVP implementation. It records the
revisions, immutable snapshot inputs, commands, verification results, and
intentional deviations. Dates and versions refer to the macOS ARM64 validation
environment used on 2026-08-05 and 2026-08-06.

## Phase 0 — environment and baseline

Platform: macOS 15.x, Apple Silicon ARM64. The toolchain reported AppleClang
17.0.0.17000013, CMake 4.1.0 at
`/Users/stefan/cmake-4.1.0/CMake.app/Contents/bin/cmake`, Node.js 22.13.0,
Emscripten 3.1.64, Java/GraalVM 25.0.3, and DuckDB CLI 1.5.3. The project
pins DuckDB commit `14eca11bd9d4a0de2ea0f078be588a9c1c5b279c`.

The starting revisions were ilic-fork `bd4625485a990629366a770d630db5d9ffa15cc5`,
iox-cpp `ff098e26b0da7697208ad02d3fbb48d60810d300`, and
duckdb-interlis `a408e60fdd63f7cd7c0e55e1cb2caa8d5693b107`. The untracked
specification files in the three repositories were pre-existing user-owned
files and were preserved. `interlis-language-tools` and `interlis-web-ide`
were outside the scope and were not changed.

The baseline commands used the pinned CMake Makefiles generator when an old
cache requested unavailable Ninja. Native ilic and iox tests passed except for
the known ilic documentation canary, which rejects the user-owned
`ilic-p2-compiler-context-spec.md` as a phase-prefixed artifact. The baseline
GEOS development package was not available to the standalone iox build.

## Phase 1 — ilic snapshot contract

The ilic snapshot line is `0.10.0-SNAPSHOT`. The immutable local snapshot
manifest was:

```text
snapshotId       20260805213000.123456789
createdAt        2026-08-05T21:30:00Z
resolvedVersion  0.10.0-SNAPSHOT.20260805213000.123456789
sourceRevision   3aa3b95bb05b602e02041d4292255496cb66d302
tarball          ilic-compiler-wasm-0.10.0-SNAPSHOT.20260805213000.123456789.tgz
sha256           764784a1e497a4b1e7bf861a3d90e0099dffb40fd081ed7f3cc7f84af6b8512a
```

The manifest is `ilic-fork/artifacts/compiler-wasm-snapshot.json`. The ilic
native/WASM/package verification passed, with the same documentation-canary
exception caused by the preserved user file. No package was published and no
tag or release was created.

## Phases 2–6 — ilic and iox native contracts

The ilic native model-compilation ownership and geometry metadata contract were
verified in the ilic-enabled native tests. The relevant ilic revisions reached
`bfcf010ed8e0034a18559409fda1a030f6e79517`.

The iox revisions delivered compact property descriptors, model-owned IOM path
access, and native geometry/WKB projection. The pre-repair iox revision was
`dcd2002d87a7f218845abc930994b900a4a2206e`. The existing focused geometry
coverage passed. The standalone GEOS-enabled configure was attempted again for
this implementation and is recorded as a deferred external dependency if the
host cannot provide a GEOS CMake package; no global dependency was installed.

## Phase 7 — DuckDB extension bootstrap

The extension-template reference used for the native bootstrap is
`cfaf3e236008e782d27f4341b0ee036002d0a449`; extension-ci-tools is pinned at
`4b3b37b0c9de00da54e1765d65abfea3f94617f4`; the vcpkg baseline is
`ce613c41372b23b1f51333815feb3edd87ef8a8b`. DuckDB is loaded with
`-unsigned` during local development. The extension remains a local source
build and does not publish artifacts.

The build scripts now enforce the canonical source directory
`duckdb-interlis/third_party/duckdb`. Before invoking CMake they inspect
`build/debug/CMakeCache.txt` and `build/release/CMakeCache.txt`; a cache whose
`CMAKE_HOME_DIRECTORY` points elsewhere fails with an actionable message and
is not removed automatically.

## Phases 8–12 — native SQL surface

The native extension implements local `.ili` model resolution and the nine
documented SQL functions: `interlis_version`, `interlis_components`,
`ili_models`, `ili_classes`, `ili_properties`, `ili_geometry_properties`,
`xtf_scan`, `xtf_values`, and `xtf_set`. Model introspection preserves model,
topic, declaration, and transfer order. `xtf_scan` is fixed-buffer streaming;
`xtf_values` supports primitive path reads, TID/BID filters, and wildcards;
`xtf_set` performs a one-value, transactional local rewrite with expected-value
conflict checking and overwrite policy.

The existing native fixtures cover introspection, primitive values, geometry
error policy, path reads, and rewrite safety. This implementation adds XTF 2.4
fixtures and coverage for primitive columns, TID/BID selection, empty results,
duplicate TIDs, geometry preservation, arc preservation, input hashing, and
large-file/test-output behavior. Large inputs are generated in temporary
directories at test time rather than committed as large fixtures.

## Phase 13 — golden geometry and documentation completion

An isolated, unchanged checkout of the Java reference `iox-ili` at commit
`54c7109f7dd05d6be8331c081cc94aa12e79d2e8` was used only to generate
`Iox2wkb` goldens. The generator used GraalVM Java 17.0.12, little-endian ISO
WKB, EWKB disabled, arc tolerance 0.1, and straight tolerance 0.0. The native
test stores these values in `iox-cpp/test/fixtures/geometry/java-iox2wkb.golden`
and compares straight geometries byte-for-byte; arcs are checked semantically
because native and Java arc stroking are not required to have identical point
sampling. The fixtures cover 2D/3D point, multipoint, line, arc, polygon,
polygon with hole, multiline, multipolygon, and max-overlap metadata. Fixture
metadata and the generation command are in the adjacent fixture README.

The Getting Started guide now starts with a local macOS ARM64 installation,
walks through environment setup, doctor/build/load commands, and contains
executable examples for all nine SQL functions using the repository fixtures.
`sql/examples/10-native-mvp.sql` is the corresponding CLI example and includes
`geometry_errors`, TID/BID and wildcard selection, `expected`, and
`overwrite`.

## Verification command record

The implementation was checked with these commands, using local paths from the
repository instructions:

```sh
source scripts/env.sh
scripts/doctor.sh
scripts/build-all.sh
scripts/smoke-test.sh
```

The iox standalone validation used a fresh temporary build directory:

```sh
cmake -S /Users/stefan/sources/iox-cpp -B /tmp/iox-native-mvp-validation \
  -G 'Unix Makefiles' -DCMAKE_BUILD_TYPE=Debug -DBUILD_TESTING=ON \
  -DIOX_BUILD_EXAMPLES=OFF -DIOX_BUILD_TOOLS=OFF -DIOX_ENABLE_GEOS=OFF \
  -DIOX_ENABLE_ILIC=ON \
  -DIOX_ILIC_SOURCE_DIR=/Users/stefan/sources/ilic-fork \
  -DIOX_ILIC_VERSION=0.10.0-SNAPSHOT -DIOX_WARNINGS_AS_ERRORS=ON
cmake --build /tmp/iox-native-mvp-validation --target \
  iox-test-geometry iox-test-geometry-java-golden
ctest --test-dir /tmp/iox-native-mvp-validation --output-on-failure
```

The final acceptance run and its exact counts are summarized in
`native-mvp-implementation-report.md`; this log intentionally retains the
commands and immutable inputs so the run can be reproduced.

Acceptance results: ilic CTest 133/134 (only the preserved user-file
documentation canary failed), iox CTest 37/37 including the Java golden test,
DuckDB Debug and Release builds passed with temporary vcpkg GEOS 3.13.0,
DuckDB SQLLogicTests passed with 163 assertions in 8 cases, the smoke test
passed, and the complete Getting Started example passed through the local
DuckDB CLI. The standalone iox GEOS-on configure failed only because no GEOS
CMake package exists on the host; this is the documented external dependency
deviation.

## Deviations and preserved worktree state

- Local ignored `java/` and GraalVM build artifacts remain untouched. They are
  not runtime dependencies of the native MVP, and `scripts/env.sh` no longer
  exports Java/GraalVM paths; `scripts/env.example.sh` is the only committed
  environment template.
- User-owned untracked specification files remain in the root, ilic-fork, and
  iox-cpp repositories. They prevent a completely clean worktree and continue
  to trigger the ilic documentation-canary failure; they were not deleted.
- Validator, `ATTACH`, remote model resolution, multi-object updates, and
  unspecified update functions remain intentionally unimplemented. Geometry
  updates, line-attribute/custom-line-form/clipped-geometry support remain
  explicit unsupported/error cases for this MVP.
- Standalone iox GEOS verification is deferred when the host lacks a GEOS
  CMake package. The DuckDB integration's existing dependency setup is not
  replaced by a new global installation.

## Phase 14 — optional GEOS profile

The default DuckDB extension profile is now GEOS-free. The root CMake option is
`INTERLIS_ENABLE_GEOS=OFF`; it is forwarded to iox as `IOX_ENABLE_GEOS`, and
the `geos` vcpkg feature is requested only when the option is `ON`. Build
scripts validate the option, pass `VCPKG_MANIFEST_FEATURES=geos` for the strict
profile, and require a fresh or cleaned CMake build when switching profiles.

The GEOS-free active build was validated with fresh Debug and Release
directories. SQLLogicTests passed with 163 assertions in 8 cases, the smoke
test and complete native example passed, DuckDB Spatial consumed the emitted
WKB successfully, and `otool -L` showed no external GEOS dependency. The
component table and version string reported `geos/disabled`.

The strict profile was validated in separate fresh Debug and Release
directories with the existing local arm64 GEOS 3.13.0 package from the pinned
vcpkg baseline `ce613c41372b23b1f51333815feb3edd87ef8a8b`. CMake resolved
`GEOS::geos_c`; iox compiled its GEOS sources; SQLLogicTests and the smoke test
passed; and the component table reported `geos/3.x`. The local host had no
vcpkg executable or standalone GEOS package configuration on `PATH`, so the
fresh vcpkg bootstrap path was not rerun in this phase and no global dependency
was installed.

The WKB comparison was byte-identical for the currently valid Point,
MultiPoint, LineString, MultiLineString, and MultiPolygon fixtures. The new
`invalid-topology.xtf` fixture demonstrates the intended semantic difference:
GEOS-free emits WKB that DuckDB Spatial reports as invalid, while strict iox
rejects it natively. Existing arc and 3D synthetic fixtures still fail before
this mode decision because of their pre-existing XTF/model descriptor issues;
they are tracked separately as pre-repair findings. The repair is recorded in
the follow-up phase below.

## Phase 15 — valid arc and 3D fixture repair

The ilic compiler fix was implemented and committed as
`c5c37108fe2cde282aa9a29ff6b2a01cf4e2974e`, then pinned in the root
`CMakeLists.txt`. `MetaModel.cpp` now preserves `CoordType::Axis` when cloning
named coordinate domains, and `Ili2Input_type.cpp` reads the third coordinate
axis from `numtype3` instead of reusing `numtype2`. The ilic regression test
compiles a named 3D `COORD`, asserts three axes, and checks the C3 bounds
`0.00 .. 100.00`.

The iox metadata index fix is committed as
`0b1cb5136a2db27f61724c22aa4143555987812b` and now follows cloned coordinate types to the named
coordinate domain before reporting `coordinate_domain_fqn`. The fixture
`testdata/synthetic/geometries/valid-arcs.xtf` was changed to canonical XTF
2.4: `coord`, `arc`, and the continuation `coord` occur directly under
`geom:polyline`; no `sequence` or `segment` XML wrapper is used. The initial
coordinate precedes the arc. `valid-3d.xtf` was deliberately left unchanged.

Focused ilic and DuckDB regression tests cover the repaired metadata and scan
paths. The complete ilic CTest run still reports the pre-existing
documentation-canary failure for the user-owned untracked
`ilic-p2-compiler-context-spec.md`; that file was preserved. The Java reference
checkout `/Users/stefan/sources/iox-ili` was used only as a read-only structural
reference and was not modified. This repair has no GEOS prerequisite; the
optional strict build is only an additional comparison.

The repair verification produced ilic CTest 133/133 with the documentation
canary excluded, iox CTest 37/37, GEOS-free Debug and Release builds, 172
DuckDB SQLLogicTest assertions in 8 cases, a passing smoke test, the complete
Getting Started example, and a passing `sql/spatial.sql`. DuckDB Spatial
reported `POINT Z` with `Z=450` for `valid-3d.xtf` and a valid `LINESTRING`
with 251 points for `valid-arcs.xtf`.
