# Getting started

This guide builds and uses the native DuckDB extension from a local checkout on
macOS ARM64. The native MVP currently uses DuckDB 1.5.5 and accepts local `.ili`
model files or non-recursive model directories only.

## 1. Build a local installation

Install AppleClang, CMake 4.1 or newer, DuckDB 1.5.5, Git, and vcpkg if you want
the manifest-based dependency path. The default extension profile is GEOS-free.

For a vcpkg build, use the same baseline as the DuckDB 1.5.5 community toolchain:

```sh
git clone https://github.com/microsoft/vcpkg.git /path/to/vcpkg
cd /path/to/vcpkg
git checkout 84bab45d415d22042bd0b9081aea57f362da3f35
./bootstrap-vcpkg.sh -disableMetrics
./vcpkg install \
  --x-manifest-root=/path/to/duckdb-interlis \
  --x-install-root=/path/to/vcpkg-installed
```

Add `--x-feature=geos` to install the optional strict geometry profile. Set the
corresponding `VCPKG_TOOLCHAIN_PATH`, `VCPKG_TARGET_TRIPLET=arm64-osx`, and
`VCPKG_INSTALLED_DIR` values in the copied `scripts/env.sh`. The sibling
ilic/iox source checkouts are optional. With vcpkg, the root overlay ports build
the pinned public GitHub source revisions; without vcpkg or sibling overrides,
CMake uses the pinned FetchContent revisions in `CMakeLists.txt`.

From the repository root:

```sh
cp scripts/env.example.sh scripts/env.sh
source scripts/env.sh
scripts/doctor.sh
scripts/build-all.sh
```

`scripts/build-all.sh` defaults to the GEOS-free profile. To enable native
topological geometry validation, use a fresh or cleaned build directory and
run:

```sh
INTERLIS_ENABLE_GEOS=ON scripts/build-all.sh
```

The GEOS-free profile still emits DuckDB `GEOMETRY` values for supported
geometries. It performs structural conversion checks, but not native
topological validity checks.

The release extension is written to:

```text
build/release/extension/interlis/interlis.duckdb_extension
```

If a build reports a stale CMake source directory, run
`scripts/clean-local.sh` and repeat the build. The build scripts never remove a
stale build automatically.
