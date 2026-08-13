# Development

## Prerequisites

Use DuckDB 1.5.5, CMake 4.1 or newer, a C++17 compiler, and initialized
DuckDB/extension-ci-tools submodules. GEOS is optional: the default build does
not enable strict geometry validation. A local ilic-fork and iox-cpp checkout
can be used for development, while a vcpkg build consumes the pinned overlay
ports from the root manifest.

```sh
cp scripts/env.example.sh scripts/env.sh
source scripts/env.sh
scripts/doctor.sh
```

The default build is GEOS-free:

```sh
INTERLIS_ENABLE_GEOS=OFF scripts/build-all.sh
```

The optional strict geometry build uses the `geos` vcpkg feature:

```sh
INTERLIS_ENABLE_GEOS=ON scripts/build-all.sh
```

## Build and test

```sh
scripts/build-all.sh
```

Equivalent extension-template commands are:

```sh
make debug
make release
make test_debug
make test_release
```

Use `INTERLIS_ILIC_SOURCE_DIR` and `INTERLIS_IOX_SOURCE_DIR` to test sibling
working trees. Without these overrides, a non-vcpkg build uses the pinned
FetchContent revisions in `CMakeLists.txt`. When DuckDB configures with vcpkg,
`VCPKG_BUILD` automatically selects the installed `iox::ilic` and `ilic::core`
package targets from the root manifest. `IOX_ENABLE_GEOS` follows
`INTERLIS_ENABLE_GEOS`.

Switching the GEOS mode requires a fresh or cleaned `build/debug` and
`build/release` directory. The scripts reject foreign DuckDB CMake caches but do
not delete stale build files automatically.

The SQLLogicTests live in `test/sql/*.test`. Native model resolver tests can be
enabled with `-DINTERLIS_BUILD_NATIVE_TESTS=ON`. The checked-in files under
`testdata/native/` are intentionally small deterministic fixtures.

## Code boundaries

Keep model compilation and iox indexing in `CompiledModel`; keep streaming state
local to a table-function invocation; and preserve RAII ownership at all iox,
GEOS, file, and DuckDB boundaries. New public API belongs in the native C++
interfaces rather than a compatibility transport.

Before committing, run:

```sh
git diff --check
make test_release
```
