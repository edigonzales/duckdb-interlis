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

SELECT '--- Model metadata: new model/model_sources API ---' AS test;
SELECT count(*) AS models
FROM ili_models(NULL, model_sources := 'testdata/synthetic/simple');
SELECT count(*) AS selected_model
FROM ili_models('SO_AGI_Simple_20260605', model_sources := 'testdata/synthetic/simple');
SELECT count(*) AS topics
FROM ili_topics('SO_AGI_Simple_20260605', model_sources := 'testdata/synthetic/simple');
SELECT count(*) AS classes
FROM ili_classes('SO_AGI_Simple_20260605', model_sources := 'testdata/synthetic/simple');
SELECT count(*) AS filtered_classes
FROM ili_classes('SO_AGI_Simple_20260605',
    model_sources := 'testdata/synthetic/simple/SO_AGI_Simple_20260605.ili,testdata/synthetic/structures;testdata/synthetic/keywords',
    class := 'Gemeinde');
SELECT count(*) AS attributes
FROM ili_attributes('SO_AGI_Simple_20260605',
    model_sources := 'testdata/synthetic/simple', class := 'Gemeinde');
SELECT count(*) AS enumerations
FROM ili_enumerations('SO_AGI_Simple_20260605', model_sources := 'testdata/synthetic/simple');
SELECT count(*) AS geometry_attributes
FROM ili_geometry_attributes(NULL, model_sources := 'testdata/synthetic/geometries');
