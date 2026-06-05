#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

if [[ -f "$SCRIPT_DIR/env.sh" ]]; then
    source "$SCRIPT_DIR/env.sh"
fi

DUCKDB="${DUCKDB_CLI:-~/bin/duckdb}"
EXTENSION="${DUCKDB_ILI_EXTENSION:-$REPO_ROOT/duckdb-extension/build/interlis.duckdb_extension}"
NATIVE_LIB="${DUCKDB_ILI_NATIVE_LIB:-$REPO_ROOT/java/ili-native/build/native/libduckdb_ili_native.dylib}"

export DUCKDB_ILI_NATIVE_LIB="$NATIVE_LIB"
exec "$DUCKDB" -unsigned -cmd "LOAD '$EXTENSION';" "$@"
