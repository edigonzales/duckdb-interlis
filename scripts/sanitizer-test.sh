#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

if [[ -f "$SCRIPT_DIR/env.sh" ]]; then
    source "$SCRIPT_DIR/env.sh"
fi

# ---- Configuration ----
DUCKDB_LIB="${DUCKDB_LIB:-$HOME/bin/libduckdb.dylib}"
EXTENSION="${INTERLIS_EXTENSION:-$REPO_ROOT/duckdb-extension/build/interlis.duckdb_extension}"
NATIVE_LIB="${DUCKDB_ILI_NATIVE_LIB:-$REPO_ROOT/java/ili-native/build/native/libduckdb_ili_native.dylib}"
TESTDIR="$REPO_ROOT/duckdb-extension/test"

# ---- Ensure libduckdb exists ----
if [[ ! -f "$DUCKDB_LIB" ]]; then
    echo "==> Downloading libduckdb v1.5.3..."
    mkdir -p "$(dirname "$DUCKDB_LIB")"
    if [[ "$(uname -s)" == "Darwin" ]]; then
        DUCKDB_LIB_URL="https://github.com/duckdb/duckdb/releases/download/v1.5.3/libduckdb-osx-universal.zip"
    else
        DUCKDB_LIB_URL="https://github.com/duckdb/duckdb/releases/download/v1.5.3/libduckdb-linux-amd64.zip"
    fi
    TMPDIR="$(mktemp -d)"
    curl -fsSL -o "$TMPDIR/libduckdb.zip" "$DUCKDB_LIB_URL"
    unzip -o "$TMPDIR/libduckdb.zip" -d "$(dirname "$DUCKDB_LIB")"
    rm -rf "$TMPDIR"
    echo "   -> $DUCKDB_LIB"
fi

# ---- Check prerequisites ----
if [[ ! -f "$NATIVE_LIB" ]]; then
    echo "ERROR: Native library not found: $NATIVE_LIB"
    echo "Run: ./gradlew :java:ili-native:nativeSharedLibrary"
    exit 1
fi

# ---- 1: Build extension with ASan + UBSan + LSan ----
echo ""
echo "=== Step 1: Build extension with ASan+UBSan ==="
ASAN_BUILD_DIR="$REPO_ROOT/duckdb-extension/build-asan"

# Copy native lib for embedding
mkdir -p "$ASAN_BUILD_DIR/native/current"
cp "$NATIVE_LIB" "$ASAN_BUILD_DIR/native/current/"

cmake -S "$REPO_ROOT/duckdb-extension" -B "$ASAN_BUILD_DIR" \
    -DCMAKE_BUILD_TYPE=Debug \
    -DCMAKE_C_FLAGS="-fsanitize=address,undefined -fno-omit-frame-pointer -g"
cmake --build "$ASAN_BUILD_DIR" -j

ASAN_EXT="$ASAN_BUILD_DIR/interlis.duckdb_extension"
echo "   -> $ASAN_EXT"

# ---- 2: Compile and run smoke test ----
echo ""
echo "=== Step 2: Compile and Run Smoke Test ==="
cc -g \
    -o "$TESTDIR/duckdb_extension_smoke_test" \
    "$TESTDIR/duckdb_extension_smoke_test.c" \
    -I"$REPO_ROOT/duckdb-extension/src/include" \
    -ldl

ASAN_OPTIONS="detect_leaks=1" \
DUCKDB_LIB="$DUCKDB_LIB" \
INTERLIS_EXTENSION="$ASAN_EXT" \
    "$TESTDIR/duckdb_extension_smoke_test"

echo ""
echo "=== Smoke test PASSED ==="

# ---- 3: Build extension with TSan ----
echo ""
echo "=== Step 3: Build extension with TSan ==="
TSAN_BUILD_DIR="$REPO_ROOT/duckdb-extension/build-tsan"

mkdir -p "$TSAN_BUILD_DIR/native/current"
cp "$NATIVE_LIB" "$TSAN_BUILD_DIR/native/current/"

cmake -S "$REPO_ROOT/duckdb-extension" -B "$TSAN_BUILD_DIR" \
    -DCMAKE_BUILD_TYPE=Debug \
    -DCMAKE_C_FLAGS="-fsanitize=thread -fno-omit-frame-pointer -g"
cmake --build "$TSAN_BUILD_DIR" -j

TSAN_EXT="$TSAN_BUILD_DIR/interlis.duckdb_extension"
echo "   -> $TSAN_EXT"

# ---- 4: Compile and run concurrency test ----
echo ""
echo "=== Step 4: Compile and Run Concurrency Test ==="
cc -g \
    -o "$TESTDIR/duckdb_extension_concurrency_test" \
    "$TESTDIR/duckdb_extension_concurrency_test.c" \
    -I"$REPO_ROOT/duckdb-extension/src/include" \
    -ldl -lpthread

TSAN_OPTIONS="suppressions=$TESTDIR/tsan_suppressions.txt" \
DUCKDB_LIB="$DUCKDB_LIB" \
INTERLIS_EXTENSION="$TSAN_EXT" \
    "$TESTDIR/duckdb_extension_concurrency_test"

echo ""
echo "=== Concurrency test PASSED ==="
echo ""
echo "=== All sanitizer tests passed ==="
