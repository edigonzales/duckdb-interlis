#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

if [[ -f "$SCRIPT_DIR/env.sh" ]]; then
    source "$SCRIPT_DIR/env.sh"
fi

pass=0
fail=0
check_ok() { echo "  $1 ... OK"; ((pass++)) || true; }
check_fail() { echo "  $1 ... FAIL"; ((fail++)) || true; }

echo "=== duckdb-interlis native toolchain doctor ==="
echo "--- Host ---"
[[ "$(uname -s)" == "Darwin" ]] && check_ok "macOS" || check_fail "macOS (got $(uname -s))"
[[ "$(uname -m)" == "arm64" ]] && check_ok "ARM64" || check_fail "ARM64 (got $(uname -m))"

echo "--- Build tools ---"
CMAKE="${CMAKE:-cmake}"
[[ -x "$CMAKE" || "$(command -v "$CMAKE" 2>/dev/null || true)" ]] && check_ok "CMake ($CMAKE)" || check_fail "CMake ($CMAKE)"
command -v make >/dev/null 2>&1 && check_ok "make" || check_fail "make"
command -v clang++ >/dev/null 2>&1 && check_ok "clang++" || check_fail "clang++"

DUCKDB="${DUCKDB_CLI:-$HOME/bin/duckdb}"
if [[ -x "$DUCKDB" ]]; then
    check_ok "DuckDB CLI ($DUCKDB)"
    "$DUCKDB" --version
else
    check_fail "DuckDB CLI ($DUCKDB)"
fi

echo "--- Sources and dependencies ---"
[[ -f "$REPO_ROOT/duckdb/CMakeLists.txt" ]] && check_ok "DuckDB submodule" || check_fail "DuckDB submodule"
[[ -f "$REPO_ROOT/extension-ci-tools/makefiles/duckdb_extension.Makefile" ]] && check_ok "extension-ci-tools submodule" || check_fail "extension-ci-tools submodule"
if [[ -n "${INTERLIS_ILIC_SOURCE_DIR:-}" ]]; then
    [[ -f "$INTERLIS_ILIC_SOURCE_DIR/CMakeLists.txt" ]] && check_ok "local ilic-fork" || check_fail "local ilic-fork"
else
    check_ok "ilic FetchContent fallback"
fi
if [[ -n "${INTERLIS_IOX_SOURCE_DIR:-}" ]]; then
    [[ -f "$INTERLIS_IOX_SOURCE_DIR/CMakeLists.txt" ]] && check_ok "local iox-cpp" || check_fail "local iox-cpp"
else
    check_ok "iox-cpp FetchContent fallback"
fi
if [[ -n "${VCPKG_TOOLCHAIN_PATH:-}" ]]; then
    [[ -f "$VCPKG_TOOLCHAIN_PATH" ]] && check_ok "vcpkg toolchain" || check_fail "vcpkg toolchain"
else
    check_ok "host/vcpkg dependency discovery"
fi
INTERLIS_ENABLE_GEOS="${INTERLIS_ENABLE_GEOS:-OFF}"
case "$INTERLIS_ENABLE_GEOS" in
    ON|OFF) check_ok "GEOS mode ($INTERLIS_ENABLE_GEOS)" ;;
    *) check_fail "GEOS mode must be ON or OFF (got $INTERLIS_ENABLE_GEOS)" ;;
esac
[[ -f "$REPO_ROOT/vcpkg.json" ]] && check_ok "vcpkg manifest" || check_fail "vcpkg manifest"

echo "=== Result: $pass passed, $fail failed ==="
[[ "$fail" -eq 0 ]]
