#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

if [[ -f "$SCRIPT_DIR/env.sh" ]]; then
    source "$SCRIPT_DIR/env.sh"
fi

DUCKDB="${DUCKDB_CLI:-$HOME/bin/duckdb}"
EXTENSION="${INTERLIS_EXTENSION:-$REPO_ROOT/build/release/extension/interlis/interlis.duckdb_extension}"

echo "=== Native DuckDB smoke test ==="
echo "Extension: $EXTENSION"
"$DUCKDB" -unsigned -cmd "LOAD '$EXTENSION';" < "$REPO_ROOT/sql/smoke.sql"
echo "=== Smoke test passed ==="
