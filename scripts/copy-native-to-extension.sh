#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

echo "Copying native library to extension build directory..."
mkdir -p "$REPO_ROOT/duckdb-extension/build/native/current"

NATIVE_LIB="$REPO_ROOT/java/ili-native/build/native/libduckdb_ili_native.dylib"
if [[ -f "$NATIVE_LIB" ]]; then
    cp "$NATIVE_LIB" "$REPO_ROOT/duckdb-extension/build/native/current/"
    echo "Copied $NATIVE_LIB"
else
    echo "Native library not found: $NATIVE_LIB"
    echo "Run scripts/build-native.sh first."
    exit 1
fi
