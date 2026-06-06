#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# Source env if available
if [[ -f "$SCRIPT_DIR/env.sh" ]]; then
    source "$SCRIPT_DIR/env.sh"
fi

EXT_DIR="$REPO_ROOT/duckdb-extension"
BUILD_DIR="$EXT_DIR/build"
CMAKE="${CMAKE:-cmake}"
# DuckDB C_STRUCT ABI version for extension metadata
# This is the C API ABI version, not the DuckDB binary version
# The local DuckDB binary may use a different C_STRUCT ABI version
DUCKDB_ABI_VERSION="${DUCKDB_ABI_VERSION:-v1.2.0}"
[[ "$DUCKDB_ABI_VERSION" != v* ]] && DUCKDB_ABI_VERSION="v$DUCKDB_ABI_VERSION"
EXT_VERSION="${EXT_VERSION:-0.1.0-dev}"

# Detect DuckDB platform identifier
detect_platform() {
    local os arch
    case "$(uname -s)" in
        Darwin) os="osx" ;;
        Linux)  os="linux" ;;
        MINGW*|MSYS*|CYGWIN*) os="windows" ;;
        *) echo "Error: Unknown OS: $(uname -s)" >&2; exit 1 ;;
    esac
    case "$(uname -m)" in
        arm64|aarch64) arch="arm64" ;;
        x86_64)        arch="amd64" ;;
        *) echo "Error: Unknown arch: $(uname -m)" >&2; exit 1 ;;
    esac
    if [ "$os" = "windows" ]; then
        DUCKDB_PLATFORM="windows_amd64"
    else
        DUCKDB_PLATFORM="${os}_${arch}"
    fi
    echo "$DUCKDB_PLATFORM"
}

# Detect native library suffix
detect_lib_suffix() {
    case "$(uname -s)" in
        Darwin) echo "dylib" ;;
        Linux)  echo "so" ;;
        MINGW*|MSYS*|CYGWIN*) echo "dll" ;;
        *) echo "so" ;;
    esac
}

PLATFORM="${DUCKDB_PLATFORM:-$(detect_platform)}"
LIB_SUFFIX="${NATIVE_LIB_SUFFIX:-$(detect_lib_suffix)}"

mkdir -p "$BUILD_DIR"

echo "Building DuckDB extension for platform: $PLATFORM"
echo "C_STRUCT ABI version: $DUCKDB_ABI_VERSION"
echo "Extension version: $EXT_VERSION"
"$CMAKE" -S "$EXT_DIR" -B "$BUILD_DIR" -DCMAKE_BUILD_TYPE=Release
"$CMAKE" --build "$BUILD_DIR" -j

echo "Appending extension metadata..."
python3 "$REPO_ROOT/scripts/append_extension_metadata.py" \
    -l "$BUILD_DIR/interlis.duckdb_extension" \
    -n interlis \
    -p "$PLATFORM" \
    -dv "$DUCKDB_ABI_VERSION" \
    -ev "$EXT_VERSION" \
    -o "$BUILD_DIR/interlis.duckdb_extension"

echo "Extension built: $BUILD_DIR/interlis.duckdb_extension"
