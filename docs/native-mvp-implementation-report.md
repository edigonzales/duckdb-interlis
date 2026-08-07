# Native MVP implementation report

Date: 2026-08-07

## Result

The native MVP gaps requested for this phase are implemented and documented.
The DuckDB surface contains the nine local SQL functions described by the
project documentation. The Getting Started guide and the executable native
example cover installation, build/load, model introspection, streaming reads,
geometry diagnostics, filtered value reads, and transactional updates.

Final source revisions used for the validation:

| Repository | Revision |
|---|---|
| duckdb-interlis | `bfc1cda992487d95ae00ed9f09be5dfb0b5e1da6` |
| ilic-fork | `c5c37108fe2cde282aa9a29ff6b2a01cf4e2974e` |
| iox-cpp | `0b1cb5136a2db27f61724c22aa4143555987812b` |
| iox-ili Java reference | `54c7109f7dd05d6be8331c081cc94aa12e79d2e8` |
| DuckDB submodule | `14eca11bd9d4a0de2ea0f078be588a9c1c5b279c` |

The ilic snapshot is shared across the local native build:

```text
snapshotId 20260805213000.123456789
tarball    ilic-compiler-wasm-0.10.0-SNAPSHOT.20260805213000.123456789.tgz
sha256     764784a1e497a4b1e7bf861a3d90e0099dffb40fd081ed7f3cc7f84af6b8512a
```

## Implemented scope

- Canonical DuckDB submodule enforcement and stale CMake-cache diagnostics.
- Local model compilation and the nine SQL functions:
  `interlis_version`, `interlis_components`, `ili_models`, `ili_classes`,
  `ili_properties`, `ili_geometry_properties`, `xtf_scan`, `xtf_values`, and
  `xtf_set`.
- XTF 2.4 fixtures and tests, including primitive values, filters, empty and
  duplicate selections, geometry errors, geometry retention, and arc retention.
- Java `Iox2wkb` golden fixtures for 2D/3D point, multipoint, line, arc,
  polygon, hole, multiline, multipolygon, and max-overlap cases. Straight WKB
  is compared byte-for-byte; arcs use semantic/tolerance checks.
- A complete local Getting Started tutorial and executable SQL example.
- Fixture metadata, generation parameters, snapshot identity, hashes, and
  intentional deviations.

## Verification

The acceptance commands were:

```sh
source scripts/env.sh
scripts/doctor.sh
scripts/build-all.sh
scripts/smoke-test.sh
```

The iox fresh build was configured with CMake 4.1.0, Unix Makefiles, Debug,
`IOX_ENABLE_ILIC=ON`, `IOX_ENABLE_GEOS=OFF`, and the local ilic source. The
Java golden target passed with 2/2 checks, and the complete iox CTest run passed
37/37.

The standalone GEOS-enabled iox configure was also attempted without a
toolchain and failed at `find_package(GEOS CONFIG REQUIRED)` because the host
has no GEOS package configuration. For the integrated DuckDB acceptance build,
a temporary `/tmp` vcpkg checkout at the repository's baseline
`ce613c41372b23b1f51333815feb3edd87ef8a8b` supplied GEOS 3.13.0 for
`arm64-osx`. Because the host has no `pkg-config`, a temporary validation shim
was placed under `/tmp`; neither workaround changes the repository or global
installations.

The final acceptance results are:

- ilic full CTest: 133/134 passed; the sole failure is `ilic_docs_links`,
  caused by the preserved untracked user-owned
  `ilic-p2-compiler-context-spec.md`.
- iox full CTest: 37/37 passed, including the Java golden test.
- DuckDB: fresh Debug and Release builds passed with GEOS 3.13.0.
- DuckDB SQLLogicTests: 172 assertions passed in 8 test cases.
- Native smoke test: passed.
- Complete Getting Started SQL example: passed with the local DuckDB CLI;
  temporary update output was moved to the user's trash after the run.
- `git diff --check`: passed for the root and iox-cpp tracked changes.
- Stale-cache guard: passed; the pre-existing cache pointing at
  `/tmp/duckdb-native-build.TyfbDw/duckdb` was rejected before the fresh build.

## Deliberately deferred or unsupported

- Validator, `ATTACH`, remote models, multi-object updates, and unspecified
  update functions are outside the native MVP contract.
- Line attributes, custom line forms, clipped geometry, and geometry updates
  remain explicit unsupported/error cases. The tests assert the expected
  failures where applicable.
- Standalone iox GEOS-enabled validation is deferred if the system does not
  expose a usable GEOS CMake package. No global dependency installation is
  performed for this task.

## Worktree exceptions and risks

Ignored local `java/` and GraalVM build remnants were not removed. The local
`scripts/env.sh` no longer contains Java/GraalVM exports and is not versioned;
`scripts/env.example.sh` remains the sole committed template. The root,
ilic-fork, and iox-cpp repositories retain user-owned untracked specification
files, so a completely clean worktree cannot be claimed. The Java reference
checkout was used read-only for golden generation and remains independently
dirty from pre-existing user changes.

The remaining main risk is environment-specific GEOS package discovery for a
standalone iox build. The native DuckDB build uses its existing dependency
configuration; standalone iox GEOS support should be rerun when a matching
package configuration is available.

## Recommended next step

Run the same acceptance commands on a clean CI worker with the pinned DuckDB,
ilic snapshot, and a discoverable GEOS CMake package. If that passes, the next
product step is to design the deferred validator/remote-model and broader
update contracts before implementing them.

## Optional GEOS build follow-up — 2026-08-07

The extension now has two explicit profiles. `INTERLIS_ENABLE_GEOS=OFF` is the
default and propagates `IOX_ENABLE_GEOS=OFF`; `interlis_components()` reports
`geos = disabled`. `INTERLIS_ENABLE_GEOS=ON` propagates the iox option and
requests the `geos` vcpkg manifest feature through
`VCPKG_MANIFEST_FEATURES=geos`. GEOS was removed from the default manifest
dependencies and is now declared only by that feature.

The active repository build was restored to the GEOS-free profile after the
comparison. Fresh GEOS-free Debug and Release builds passed, the SQLLogicTest
suite passed with 163 assertions in 8 cases, the smoke test passed, the full
native example passed, and `sql/spatial.sql` passed with DuckDB Spatial. The
release extension's `otool -L` output contains only the system C++ and macOS
libraries; it has no external GEOS dependency.

The strict profile was also built in fresh Debug and Release directories using
the existing local arm64 GEOS package from the pinned vcpkg installation
(`GEOS 3.13.0`, baseline `ce613c41372b23b1f51333815feb3edd87ef8a8b`). CMake
found `GEOS::geos_c`, iox compiled its GEOS validation sources, the strict
SQLLogicTests passed with the same 163 assertions, and the strict smoke test
reported `geos/3.x`. The current host did not have the vcpkg executable or a
standalone GEOS package on `PATH`; therefore this run verified the CMake/link
integration against the existing local package, while the documented vcpkg
bootstrap/install command remains the reproducible fresh-environment path.

For supported valid Point, MultiPoint, LineString, MultiLineString, and
MultiPolygon fixtures, GEOS-free and strict WKB was compared byte-for-byte and
was identical. The deliberately self-intersecting
`invalid-topology.xtf` fixture is accepted as WKB by the GEOS-free build and
returns `ST_IsValid = false` through DuckDB Spatial; the strict build rejects it
with `GEOS rejected invalid projected geometry`.

The follow-up fixture repair below supersedes the earlier pre-repair note: the
arc fixture now uses direct XTF 2.4 `coord`/`arc` children, while the 3D
fixture remains byte-for-byte unchanged and is compiled with the repaired ilic
snapshot.

## Fixture repair follow-up — 2026-08-07

The ilic fix is pinned at `c5c37108fe2cde282aa9a29ff6b2a01cf4e2974e`. Cloning a
`CoordType` now preserves its axis list, and the Ili2 parser reads the third
axis from `numtype3`; the ilic regression test checks three axes and the C3
range `0.00 .. 100.00`. iox now resolves a cloned named coordinate domain to
its named domain FQN for geometry metadata. `valid-arcs.xtf` was corrected to
canonical XTF 2.4 with a start coordinate, an arc, and a continuation
coordinate directly below `geom:polyline`; `valid-3d.xtf` was not modified.

The focused ilic regression and complete native geometry paths were rerun after
these changes. The preserved user-owned `ilic-p2-compiler-context-spec.md`
still causes the existing ilic documentation-canary failure when the complete
ilic CTest suite is run; it was not deleted or changed. The Java reference
checkout `/Users/stefan/sources/iox-ili` remains read-only. GEOS is not required
for this repair; the same valid WKB comparison remains part of the optional
strict-build verification.

The latest repair verification also reports: ilic CTest 133/133 when the
preserved documentation-canary case is excluded (the full 134-test inventory
has that one known user-file failure), iox CTest 37/37, GEOS-free DuckDB Debug
and Release builds, 172 SQLLogicTest assertions, the native smoke test, the
complete Getting Started SQL example, and `sql/spatial.sql` all passed. Direct
Spatial checks returned `POINT Z` with `Z=450` for `valid-3d.xtf` and a valid
`LINESTRING` with 251 points for `valid-arcs.xtf`.
