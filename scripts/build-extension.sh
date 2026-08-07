#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

if [[ -f "$SCRIPT_DIR/env.sh" ]]; then
    source "$SCRIPT_DIR/env.sh"
fi

cd "$REPO_ROOT"
CMAKE="${CMAKE:-cmake}"
DUCKDB_SRCDIR="${DUCKDB_SRCDIR:-$REPO_ROOT/third_party/duckdb}"
DUCKDB_SRCDIR="$(cd "$DUCKDB_SRCDIR" && pwd)"
EXPECTED_DUCKDB_SRCDIR="$(cd "$REPO_ROOT/third_party/duckdb" && pwd)"

if [[ "$DUCKDB_SRCDIR" != "$EXPECTED_DUCKDB_SRCDIR" ]]; then
    echo "DUCKDB_SRCDIR must point to the pinned third_party/duckdb submodule: $EXPECTED_DUCKDB_SRCDIR" >&2
    exit 1
fi

cache="$REPO_ROOT/build/release/CMakeCache.txt"
if [[ -f "$cache" ]]; then
    cached_source="$(sed -n 's#^CMAKE_HOME_DIRECTORY:INTERNAL=##p' "$cache" | head -n 1)"
    if [[ -n "$cached_source" && "$cached_source" != "$EXPECTED_DUCKDB_SRCDIR" ]]; then
        echo "Stale CMake cache in build/release: expected $EXPECTED_DUCKDB_SRCDIR, found $cached_source" >&2
        echo "Run scripts/clean-local.sh and retry; no build files were removed automatically." >&2
        exit 1
    fi
fi

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

if [[ -f "$cache" ]]; then
    cached_mode="$(sed -n 's#^INTERLIS_ENABLE_GEOS:BOOL=##p' "$cache" | head -n 1)"
    if [[ -n "$cached_mode" && "$cached_mode" != "$INTERLIS_ENABLE_GEOS" ]]; then
        echo "CMake cache in build/release uses INTERLIS_ENABLE_GEOS=$cached_mode, requested $INTERLIS_ENABLE_GEOS." >&2
        echo "Use scripts/clean-local.sh or a separate build directory before switching GEOS modes." >&2
        exit 1
    fi
fi

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

make release \
    DUCKDB_SRCDIR="$DUCKDB_SRCDIR" \
    CMAKE="$CMAKE" \
    EXT_RELEASE_FLAGS="$native_flags" \
    VCPKG_TOOLCHAIN_PATH="${VCPKG_TOOLCHAIN_PATH:-}" \
    VCPKG_TARGET_TRIPLET="${VCPKG_TARGET_TRIPLET:-}" \
    DISABLE_SANITIZER=1

EXTENSION="$REPO_ROOT/build/release/extension/interlis/interlis.duckdb_extension"
if [[ ! -f "$EXTENSION" ]]; then
    echo "Native extension artifact not found: $EXTENSION" >&2
    exit 1
fi
echo "Native extension built: $EXTENSION (GEOS: $INTERLIS_ENABLE_GEOS)"
