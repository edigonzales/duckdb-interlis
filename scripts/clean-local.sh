#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

echo "Cleaning native DuckDB build artifacts..."
cd "$REPO_ROOT"
rm -rf build
echo "Cleaned build/."
