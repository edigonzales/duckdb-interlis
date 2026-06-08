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
TESTDATA="$REPO_ROOT/testdata/synthetic/simple"

export DUCKDB_ILI_NATIVE_LIB="$NATIVE_LIB"

echo "=== Phase 4 Concurrency Test ==="
echo "Extension: $EXTENSION"
echo "Native lib: $NATIVE_LIB"
echo ""

PASS=0
FAIL=0

# Helper: run a single query and check exit code
run_query() {
    local id="$1"
    local query="$2"
    local out
    out=$("$DUCKDB" -unsigned -cmd "LOAD '$EXTENSION'" -c "$query" 2>&1)
    local rc=$?
    if [ $rc -eq 0 ]; then
        echo "  [$id] PASS: $query"
        ((PASS++)) || true
    else
        echo "  [$id] FAIL (rc=$rc): $query"
        echo "    $out" | head -5
        ((FAIL++)) || true
    fi
}

echo "--- Test 1: 50 parallel version queries ---"
for i in $(seq 1 50); do
    run_query "v$i" "SELECT ili_native_version();" &
done
wait
echo ""

echo "--- Test 2: 20 parallel model info queries ---"
for i in $(seq 1 20); do
    run_query "m$i" "SELECT count(*) FROM ili_models('$TESTDATA');" &
done
wait
echo ""

echo "--- Test 3: 10 parallel validation queries ---"
for i in $(seq 1 10); do
    run_query "val$i" "SELECT count(*) FROM ili_validate('$TESTDATA/valid.xtf', modeldir := '$TESTDATA');" &
done
wait
echo ""

echo "--- Test 4: 10 parallel XTF read queries ---"
for i in $(seq 1 10); do
    run_query "xtf$i" "SELECT count(*) FROM read_xtf_objects('$TESTDATA/valid.xtf', modeldir := '$TESTDATA');" &
done
wait
echo ""

echo "--- Test 5: 5 parallel import SQL queries ---"
for i in $(seq 1 5); do
    run_query "imp$i" "SELECT count(*) FROM ili_generate_import_sql('$TESTDATA/valid.xtf', schema := 'conc_test_$i', modeldir := '$TESTDATA');" &
done
wait
echo ""

echo ""
echo "=== Results: $PASS passed, $FAIL failed ==="

if [ "$FAIL" -gt 0 ]; then
    echo "CONCURRENCY TEST FAILED"
    exit 1
else
    echo "CONCURRENCY TEST PASSED"
fi
