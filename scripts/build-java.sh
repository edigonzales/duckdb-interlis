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

./gradlew :java:ili-core:test :java:ili-native:test
