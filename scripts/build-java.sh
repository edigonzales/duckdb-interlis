#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

if [[ -f "$SCRIPT_DIR/env.sh" ]]; then
    source "$SCRIPT_DIR/env.sh"
fi

if [[ -z "${GRAALVM_HOME:-}" ]]; then
    echo "ERROR: GRAALVM_HOME is not set. Run: source scripts/env.sh" >&2
    exit 1
fi
export JAVA_HOME="$GRAALVM_HOME"
export PATH="$JAVA_HOME/bin:$PATH"

cd "$REPO_ROOT"

./gradlew :java:ili-core:test :java:ili-native:test
