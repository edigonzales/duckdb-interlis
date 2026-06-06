-- Version functions
-- Run with: duckdb -unsigned -cmd "LOAD 'interlis.duckdb_extension'" < sql/examples/01-version.sql
--   or:    scripts/dev-duckdb.sh < sql/examples/01-version.sql

SELECT '=== Extension Version ===' AS example;
SELECT ili_extension_version() AS version;

SELECT '=== Extension Loaded Check ===' AS example;
SELECT CASE
    WHEN ili_extension_version() IS NOT NULL THEN 'Extension loaded'
    ELSE 'Extension NOT loaded'
END AS status;

SELECT '=== Native Version (full JSON) ===' AS example;
SELECT ili_native_version() AS native_info;

SELECT '=== Native Version (individual fields) ===' AS example;
SELECT
    json_extract_string(ili_native_version(), '$.nativeVersion') AS native_version,
    json_extract_string(ili_native_version(), '$.coreVersion') AS core_version,
    json_extract_string(ili_native_version(), '$.platform') AS platform,
    json_extract_string(ili_native_version(), '$.nativeLibName') AS lib_name;
