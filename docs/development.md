# Development

## Prerequisites

Use DuckDB 1.5.3, CMake 4.1 or newer, a C++17 compiler, vcpkg with GEOS, and
initialized DuckDB/extension-ci-tools submodules. A local ilic-fork and iox-cpp
checkout is recommended for development.

```sh
cp scripts/env.example.sh scripts/env.sh
source scripts/env.sh
scripts/doctor.sh
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
working trees. CMake records pinned fallback revisions when those overrides are
absent. `IOX_ENABLE_GEOS` and JSON support are enabled by the root CMake file;
there is no ad-hoc dependency download in the extension source.

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
