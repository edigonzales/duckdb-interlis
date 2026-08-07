#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

if [[ -f "$SCRIPT_DIR/env.sh" ]]; then
    source "$SCRIPT_DIR/env.sh"
fi

cd "$REPO_ROOT"
"$SCRIPT_DIR/doctor.sh"

CMAKE="${CMAKE:-cmake}"
DUCKDB_SRCDIR="${DUCKDB_SRCDIR:-$REPO_ROOT/third_party/duckdb}"
DUCKDB_SRCDIR="$(cd "$DUCKDB_SRCDIR" && pwd)"
EXPECTED_DUCKDB_SRCDIR="$(cd "$REPO_ROOT/third_party/duckdb" && pwd)"

check_build_cache() {
    local build_dir="$1"
    local cache="$REPO_ROOT/$build_dir/CMakeCache.txt"
    [[ -f "$cache" ]] || return 0
    local cached_source
    cached_source="$(sed -n 's#^CMAKE_HOME_DIRECTORY:INTERNAL=##p' "$cache" | head -n 1)"
    if [[ -n "$cached_source" && "$cached_source" != "$EXPECTED_DUCKDB_SRCDIR" ]]; then
        echo "Stale CMake cache in $build_dir: expected DuckDB source $EXPECTED_DUCKDB_SRCDIR, found $cached_source" >&2
        echo "Run scripts/clean-local.sh and retry; no build files were removed automatically." >&2
        exit 1
    fi
}

check_geos_mode_cache() {
    local build_dir="$1"
    local cache="$REPO_ROOT/$build_dir/CMakeCache.txt"
    [[ -f "$cache" ]] || return 0
    local cached_mode
    cached_mode="$(sed -n 's#^INTERLIS_ENABLE_GEOS:BOOL=##p' "$cache" | head -n 1)"
    if [[ -n "$cached_mode" && "$cached_mode" != "$INTERLIS_ENABLE_GEOS" ]]; then
        echo "CMake cache in $build_dir uses INTERLIS_ENABLE_GEOS=$cached_mode, requested $INTERLIS_ENABLE_GEOS." >&2
        echo "Use scripts/clean-local.sh or a separate build directory before switching GEOS modes." >&2
        exit 1
    fi
}

if [[ "$DUCKDB_SRCDIR" != "$EXPECTED_DUCKDB_SRCDIR" ]]; then
    echo "DUCKDB_SRCDIR must point to the pinned third_party/duckdb submodule: $EXPECTED_DUCKDB_SRCDIR" >&2
    exit 1
fi
check_build_cache build/debug
check_build_cache build/release

if [[ "$CMAKE" == */* ]]; then
    export PATH="$(dirname "$CMAKE"):$PATH"
fi

INTERLIS_ENABLE_GEOS="${INTERLIS_ENABLE_GEOS:-OFF}"
case "$INTERLIS_ENABLE_GEOS" in
    ON|OFF) ;;
    *)
        echo "INTERLIS_ENABLE_GEOS must be ON or OFF (got $INTERLIS_ENABLE_GEOS)" >&2
        exit 1
        ;;
esac

check_geos_mode_cache build/debug
check_geos_mode_cache build/release

vcpkg_manifest_features=""
if [[ "$INTERLIS_ENABLE_GEOS" == "ON" ]]; then
    vcpkg_manifest_features="geos"
fi

native_flags="-DINTERLIS_BUILD_NATIVE_TESTS=OFF"
native_flags+=" -DINTERLIS_ENABLE_GEOS=$INTERLIS_ENABLE_GEOS"
native_flags+=" -DVCPKG_MANIFEST_FEATURES=$vcpkg_manifest_features"
if [[ -n "${INTERLIS_ILIC_SOURCE_DIR:-}" ]]; then
    native_flags+=" -DINTERLIS_ILIC_SOURCE_DIR=${INTERLIS_ILIC_SOURCE_DIR}"
fi
if [[ -n "${INTERLIS_IOX_SOURCE_DIR:-}" ]]; then
    native_flags+=" -DINTERLIS_IOX_SOURCE_DIR=${INTERLIS_IOX_SOURCE_DIR}"
fi

make debug \
    DUCKDB_SRCDIR="$DUCKDB_SRCDIR" \
    CMAKE="$CMAKE" \
    EXT_DEBUG_FLAGS="$native_flags" \
    VCPKG_TOOLCHAIN_PATH="${VCPKG_TOOLCHAIN_PATH:-}" \
    VCPKG_TARGET_TRIPLET="${VCPKG_TARGET_TRIPLET:-}" \
    DISABLE_SANITIZER=1

make release \
    DUCKDB_SRCDIR="$DUCKDB_SRCDIR" \
    CMAKE="$CMAKE" \
    EXT_RELEASE_FLAGS="$native_flags" \
    VCPKG_TOOLCHAIN_PATH="${VCPKG_TOOLCHAIN_PATH:-}" \
    VCPKG_TARGET_TRIPLET="${VCPKG_TARGET_TRIPLET:-}" \
    DISABLE_SANITIZER=1

make test_release

echo "Native build and SQLLogicTests completed (GEOS: $INTERLIS_ENABLE_GEOS)."
