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
if [[ "$CMAKE" == */* ]]; then
    export PATH="$(dirname "$CMAKE"):$PATH"
fi
native_flags="-DINTERLIS_BUILD_NATIVE_TESTS=OFF"
if [[ -n "${INTERLIS_ILIC_SOURCE_DIR:-}" ]]; then
    native_flags+=" -DINTERLIS_ILIC_SOURCE_DIR=${INTERLIS_ILIC_SOURCE_DIR}"
fi
if [[ -n "${INTERLIS_IOX_SOURCE_DIR:-}" ]]; then
    native_flags+=" -DINTERLIS_IOX_SOURCE_DIR=${INTERLIS_IOX_SOURCE_DIR}"
fi

make debug \
    CMAKE="$CMAKE" \
    EXT_DEBUG_FLAGS="$native_flags" \
    VCPKG_TOOLCHAIN_PATH="${VCPKG_TOOLCHAIN_PATH:-}" \
    VCPKG_TARGET_TRIPLET="${VCPKG_TARGET_TRIPLET:-}" \
    DISABLE_SANITIZER=1

make release \
    CMAKE="$CMAKE" \
    EXT_RELEASE_FLAGS="$native_flags" \
    VCPKG_TOOLCHAIN_PATH="${VCPKG_TOOLCHAIN_PATH:-}" \
    VCPKG_TARGET_TRIPLET="${VCPKG_TARGET_TRIPLET:-}" \
    DISABLE_SANITIZER=1

make test_release

echo "Native build and SQLLogicTests completed."
