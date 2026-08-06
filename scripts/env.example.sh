#!/usr/bin/env bash
# Optional local overrides for the native C++ build. This file is safe to copy
# to scripts/env.sh; scripts/env.sh is intentionally gitignored.
export DUCKDB_VERSION="1.5.3"
export DUCKDB_CLI="$HOME/bin/duckdb"
export CMAKE="$HOME/cmake-4.1.0/CMake.app/Contents/bin/cmake"
# export INTERLIS_ILIC_SOURCE_DIR="/path/to/ilic-fork"
# export INTERLIS_IOX_SOURCE_DIR="/path/to/iox-cpp"
# export VCPKG_TOOLCHAIN_PATH="/path/to/vcpkg/scripts/buildsystems/vcpkg.cmake"
# export VCPKG_TARGET_TRIPLET="arm64-osx"
# export DUCKDB_BUILD_DIR="$PWD/build/release"
