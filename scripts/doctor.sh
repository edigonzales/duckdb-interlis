#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

if [[ -f "$SCRIPT_DIR/env.sh" ]]; then
    source "$SCRIPT_DIR/env.sh"
fi

echo "=== DuckDB ILI Toolchain Doctor ==="
echo ""

pass=0
fail=0

check_ok() { echo "  $1 ... OK"; ((pass++)) || true; }
check_fail() { echo "  $1 ... FAIL"; ((fail++)) || true; }

echo "--- Platform ---"
if [[ "$(uname -m)" == "arm64" ]]; then
    check_ok "macOS ARM64"
else
    check_fail "macOS ARM64 (got $(uname -m))"
fi

echo ""
echo "--- Java / GraalVM ---"
if [[ -z "${GRAALVM_HOME:-}" ]]; then
    check_fail "GRAALVM_HOME not set (source scripts/env.sh)"
else
    if [[ -x "$GRAALVM_HOME/bin/java" ]]; then
        check_ok "GRAALVM_HOME=$GRAALVM_HOME"
        if [[ -x "$GRAALVM_HOME/bin/native-image" ]]; then
            check_ok "native-image in GRAALVM_HOME"
        else
            check_fail "native-image in GRAALVM_HOME"
        fi
        echo -n "  GraalVM version ... "
        "$GRAALVM_HOME/bin/java" --version 2>&1 | head -1
        ((pass++)) || true
    else
        check_fail "GRAALVM_HOME set but java not found: $GRAALVM_HOME/bin/java"
    fi
fi

echo ""
echo "--- Build Tools ---"
if command -v gradle >/dev/null 2>&1; then
    check_ok "gradle ($(command -v gradle))"
elif [[ -x "$REPO_ROOT/gradlew" ]]; then
    check_ok "gradlew ($REPO_ROOT/gradlew)"
else
    check_fail "gradle/gradlew not found"
fi
DUCKDB_BIN="${DUCKDB_CLI:-$HOME/bin/duckdb}"
if [[ -x "$DUCKDB_BIN" ]]; then
    check_ok "DuckDB CLI ($DUCKDB_BIN)"
    echo -n "  DuckDB version ... "
    "$DUCKDB_BIN" --version
else
    check_fail "DuckDB CLI not found"
fi
command -v cmake >/dev/null 2>&1 && check_ok "cmake ($(command -v cmake))" || \
    ([[ -x "${CMAKE:-}" ]] && check_ok "cmake ($CMAKE)" || check_fail "cmake not found")
command -v clang >/dev/null 2>&1 && check_ok "clang" || check_fail "clang not found"
command -v make >/dev/null 2>&1 && check_ok "make" || check_fail "make not found"

echo ""
echo "--- Gradle Project ---"
[[ -f "$REPO_ROOT/settings.gradle" ]] && check_ok "settings.gradle" || check_fail "settings.gradle"
[[ -x "$REPO_ROOT/gradlew" ]] && check_ok "gradlew" || check_fail "gradlew"

echo ""
echo "--- Project Dirs ---"
for dir in java/ili-core java/ili-native duckdb-extension scripts sql testdata docs; do
    [[ -d "$REPO_ROOT/$dir" ]] && check_ok "  $dir" || check_fail "  $dir"
done

echo ""
echo "--- Environment Variables ---"
for var in GRAALVM_HOME JAVA_HOME DUCKDB_ILI_NATIVE_LIB DUCKDB_ILI_EXTENSION; do
    if [[ -n "${!var:-}" ]]; then
        echo "  $var = ${!var}"
        ((pass++)) || true
    else
        echo "  $var = (not set)"
        ((fail++)) || true
    fi
done

echo ""
echo "=== Result: $pass passed, $fail failed ==="

[[ $fail -gt 0 ]] && exit 1 || exit 0
