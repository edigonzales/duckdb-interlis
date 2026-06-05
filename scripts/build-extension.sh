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
CMAKE="${CMAKE:-~/cmake-4.1.0/CMake.app/Contents/bin/cmake}"

mkdir -p "$BUILD_DIR"

echo "Building DuckDB extension..."
"$CMAKE" -S "$EXT_DIR" -B "$BUILD_DIR" -DCMAKE_BUILD_TYPE=Release
"$CMAKE" --build "$BUILD_DIR" -j

echo "Appending extension metadata..."
python3 "$REPO_ROOT/scripts/append_extension_metadata.py" \
    -l "$BUILD_DIR/interlis.duckdb_extension" \
    -n interlis \
    -p osx_arm64 \
    -dv v1.2.0 \
    -ev 0.1.0-dev \
    -o "$BUILD_DIR/interlis.duckdb_extension"

echo "Extension built: $BUILD_DIR/interlis.duckdb_extension"
