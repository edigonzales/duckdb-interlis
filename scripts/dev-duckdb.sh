#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

if [[ -f "$SCRIPT_DIR/env.sh" ]]; then
    source "$SCRIPT_DIR/env.sh"
fi

DUCKDB="${DUCKDB_CLI:-$HOME/bin/duckdb}"
EXTENSION="${INTERLIS_EXTENSION:-$REPO_ROOT/build/release/extension/interlis/interlis.duckdb_extension}"

if [[ ! -x "$DUCKDB" ]]; then
    echo "DuckDB CLI not found: $DUCKDB" >&2
    exit 1
fi
if [[ ! -f "$EXTENSION" ]]; then
    echo "Native extension not found: $EXTENSION" >&2
    echo "Run scripts/build-extension.sh first." >&2
    exit 1
fi

exec "$DUCKDB" -unsigned -cmd "LOAD '$EXTENSION';" "$@"
