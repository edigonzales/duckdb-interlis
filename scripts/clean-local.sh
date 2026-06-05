#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

echo "Cleaning local build artifacts..."

cd "$REPO_ROOT"
./gradlew clean 2>/dev/null || true
rm -rf duckdb-extension/build
rm -rf java/ili-native/build/native

echo "Cleaned."
