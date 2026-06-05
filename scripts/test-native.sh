#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
NATIVE_LIB="$REPO_ROOT/java/ili-native/build/native/libduckdb_ili_native.dylib"
TEST_DIR="$REPO_ROOT/duckdb-extension/test"
TEST_BIN="$TEST_DIR/native_smoke_test"

if [[ ! -f "$NATIVE_LIB" ]]; then
    echo "Native library not found: $NATIVE_LIB"
    echo "Run scripts/build-native.sh first."
    exit 1
fi

echo "Compiling native smoke test..."
cc -o "$TEST_BIN" "$TEST_DIR/native_smoke_test.c" \
    -I"$REPO_ROOT/java/ili-native/build/native" \
    -ldl

echo "Running native smoke test..."
ILI_NATIVE_LIB="$NATIVE_LIB" "$TEST_BIN"
echo ""
echo "Native smoke test passed."
