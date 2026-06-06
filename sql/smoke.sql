-- Smoke test for DuckDB ILI extension (Phase 4)
-- Run with: duckdb -unsigned -cmd "LOAD 'interlis.duckdb_extension'" < sql/smoke.sql
-- or load from a specific path:
-- duckdb -unsigned -cmd "LOAD '/path/to/interlis.duckdb_extension'" < sql/smoke.sql

-- The extension must be loaded before running this script.
-- This file is used by scripts/smoke-test.sh which handles loading.

SELECT '--- Extension Version ---' AS test;
SELECT ili_extension_version() AS version;

SELECT '--- Native Version ---' AS test;
SELECT ili_native_version() AS version;

SELECT '--- Validate: valid XTF (summary) ---' AS test;
SELECT json_extract(result, '$.valid') AS valid,
       json_extract(result, '$.errorCount') AS errors
FROM (
    SELECT ili_validate_summary_json(
        'testdata/synthetic/simple/valid.xtf',
        'testdata/synthetic/simple'
    ) AS result
);

SELECT '--- Validate: invalid XTF (summary) ---' AS test;
SELECT json_extract(result, '$.valid') AS valid,
       json_extract(result, '$.errorCount') AS errors
FROM (
    SELECT ili_validate_summary_json(
        'testdata/synthetic/simple/invalid.xtf',
        'testdata/synthetic/simple'
    ) AS result
);

SELECT '--- Validate: table function (errors only) ---' AS test;
SELECT severity, message, line
FROM ili_validate('testdata/synthetic/simple/invalid.xtf', modeldir := 'testdata/synthetic/simple')
WHERE severity = 'ERROR';
