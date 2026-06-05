#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

if [[ -f "$SCRIPT_DIR/env.sh" ]]; then
    source "$SCRIPT_DIR/env.sh"
fi

export JAVA_HOME="${GRAALVM_HOME:-/Users/stefan/.sdkman/candidates/java/25.0.3-graal}"
export PATH="$JAVA_HOME/bin:$PATH"

cd "$REPO_ROOT"

echo "=== Stage 1: Doctor ==="
"$SCRIPT_DIR/doctor.sh"

echo ""
echo "=== Stage 2: Build Java ==="
"$SCRIPT_DIR/build-java.sh"

echo ""
echo "=== Stage 3: Build Native Library ==="
"$SCRIPT_DIR/build-native.sh" || echo "WARNING: Native build failed (Phase 1 will complete this)"

echo ""
echo "=== Stage 4: Build DuckDB Extension ==="
"$SCRIPT_DIR/build-extension.sh"

echo ""
echo "=== Build complete ==="
echo "Extension: $REPO_ROOT/duckdb-extension/build/ili.duckdb_extension"
echo "Native lib (may not exist yet): $REPO_ROOT/java/ili-native/build/native/libduckdb_ili_native.dylib"
