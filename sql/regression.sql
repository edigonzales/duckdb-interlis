-- Regression test suite for previously found bugs
-- Run with: duckdb -unsigned -cmd "LOAD '<ext>'" < sql/regression.sql
-- Each section documents the behavior that must remain stable.

-- REGRESSION-1: Error message details visible through DuckDB error
-- Expected: DuckDB error with meaningful message (not generic "Validation call failed")
SELECT '--- REGRESSION-1: Error messages now propagated ---' AS test;
SELECT validate_xtf_summary_json(
    '/nonexistent/file.xtf',
    '/nonexistent/dir'
);
-- Expected: DuckDB error containing error details

-- REGRESSION-2: Missing input path produces DuckDB error with details
SELECT '--- REGRESSION-2: File-not-found message propagated ---' AS test;
SELECT severity, message
FROM validate_xtf('/nonexistent/file.xtf', model_sources := '/nonexistent/dir');
-- Expected: DuckDB error containing error details

-- REGRESSION-4: Validation profile parameter now available
SELECT '--- REGRESSION-4: Validation with profiles ---' AS test;
SELECT severity, code, message
FROM validate_xtf('testdata/synthetic/simple/valid.xtf',
    model_sources := 'testdata/synthetic/simple',
    profile := 'full');

-- REGRESSION-4b: Fast profile
SELECT severity, code, message
FROM validate_xtf('testdata/synthetic/simple/valid.xtf',
    model_sources := 'testdata/synthetic/simple',
    profile := 'fast');

-- REGRESSION-4c: max_messages parameter
SELECT severity, message
FROM validate_xtf('testdata/synthetic/simple/invalid.xtf',
    model_sources := 'testdata/synthetic/simple',
    max_messages := 3);

-- REGRESSION-5: CSV parser now handles comma-containing messages
-- The code column is now populated (was always empty before)
-- Verified by the Java CSV parser unit tests (parseCsvLine)

-- REGRESSION-6: Class name matching uses full FQNs
-- This query guards against reintroducing short-name matching.
SELECT '--- REGRESSION-6: Class matching by short name ---' AS test;
SELECT xtf_class, xtf_tid
FROM read_xtf_class('testdata/synthetic/simple/valid.xtf',
    class := 'SO_AGI_Simple_20260605.Topic.Gemeinde',
    model_sources := 'testdata/synthetic/simple');

-- REGRESSION-7: NULL vs empty string are distinguished
-- The transport uses the NULL sentinel (\N) for actual NULL values.
SELECT '--- REGRESSION-7: NULL vs empty distinction ---' AS test;
SELECT xtf_tid, Name, bfs_nr
FROM read_xtf_class('testdata/synthetic/simple/valid.xtf',
    class := 'SO_AGI_Simple_20260605.Topic.Gemeinde',
    model_sources := 'testdata/synthetic/simple');

-- REGRESSION-8: Table names use topic__class naming
SELECT '--- REGRESSION-8: topic__class table naming (FIXED) ---' AS test;
SELECT sql_statement
FROM ili_generate_import_sql('testdata/synthetic/simple/valid.xtf',
    schema := 'regression_test',
    model_sources := 'testdata/synthetic/simple')
WHERE sql_statement LIKE '%CREATE TABLE%';

-- REGRESSION-9: Unsupported mapping values are rejected
-- Expected: DuckDB error for unsupported mapping
SELECT '--- REGRESSION-9: mapping rejection (FIXED) ---' AS test;
-- This should now produce a DuckDB error:
-- SELECT sql_statement
-- FROM ili_generate_import_sql('testdata/synthetic/simple/valid.xtf',
--     schema := 'regression_test',
--     model_sources := 'testdata/synthetic/simple',
--     mapping := 'unsupported_mode');

-- REGRESSION-10: Generated SQL is wrapped in a transaction
SELECT '--- REGRESSION-10: Transaction wrapping (FIXED) ---' AS test;
SELECT sql_statement
FROM ili_generate_import_sql('testdata/synthetic/simple/valid.xtf',
    schema := 'regression_test',
    model_sources := 'testdata/synthetic/simple')
WHERE sql_statement ILIKE '%BEGIN%' OR sql_statement ILIKE '%COMMIT%';

-- REGRESSION-11: Model info produces a DuckDB error on compilation failure
-- Expected: DuckDB error, NOT an empty result
SELECT '--- REGRESSION-11: Error instead of empty result ---' AS test;
SELECT * FROM ili_models(NULL, model_sources := '/nonexistent/directory');
-- Expected: DuckDB error with compilation failure details

-- REGRESSION-12: No ERROR: prefix in result data
-- Errors use status > 0 with JSON, never an ERROR: prefix in data.
-- Verification: valid XTF read should produce clean data with no ERROR: rows
SELECT '--- REGRESSION-12: No ERROR prefix in data ---' AS test;
SELECT * FROM read_xtf_objects('testdata/synthetic/simple/valid.xtf',
    model_sources := 'testdata/synthetic/simple');
-- Expected: data rows only, no rows containing "ERROR:"

-- Geometry column behavior: GEOMETRY columns,
-- hex-WKB transport, no WKT cast needed.

-- REGRESSION-G0: Geometry columns are GEOMETRY type
SELECT '--- REGRESSION-G0: Geometry column type is GEOMETRY ---' AS test;
SELECT typeof(Lage_geom) AS geometry_column_type
FROM read_xtf_class('testdata/synthetic/geometries/valid.xtf',
    class := 'SO_AGI_Geometries_20260605.Topic.PunktObjekt',
    model_sources := 'testdata/synthetic/geometries');
-- Expected: GEOMETRY

-- REGRESSION-G1: Geometry WKT via CAST
SELECT '--- REGRESSION-G1: Geometry WKT via CAST ---' AS test;
SELECT xtf_tid, Name, Lage_geom::VARCHAR AS wkt
FROM read_xtf_class('testdata/synthetic/geometries/valid.xtf',
    class := 'SO_AGI_Geometries_20260605.Topic.PunktObjekt',
    model_sources := 'testdata/synthetic/geometries');
-- Expected: Lage_geom::VARCHAR = 'POINT (2605000 1203000)'

-- REGRESSION-G2: All basic geometry types
-- GEOMETRY columns with valid WKT representation
SELECT '--- REGRESSION-G2a: POINT ---' AS test;
SELECT xtf_tid, Lage_geom::VARCHAR AS wkt
FROM read_xtf_class('testdata/synthetic/geometries/valid.xtf',
    class := 'SO_AGI_Geometries_20260605.Topic.PunktObjekt',
    model_sources := 'testdata/synthetic/geometries');

SELECT '--- REGRESSION-G2b: MULTIPOINT ---' AS test;
SELECT xtf_tid, Lagen_geom::VARCHAR AS wkt
FROM read_xtf_class('testdata/synthetic/geometries/valid.xtf',
    class := 'SO_AGI_Geometries_20260605.Topic.MultiPunktObjekt',
    model_sources := 'testdata/synthetic/geometries');

SELECT '--- REGRESSION-G2c: LINESTRING ---' AS test;
SELECT xtf_tid, Verlauf_geom::VARCHAR AS wkt
FROM read_xtf_class('testdata/synthetic/geometries/valid.xtf',
    class := 'SO_AGI_Geometries_20260605.Topic.LinienObjekt',
    model_sources := 'testdata/synthetic/geometries');

SELECT '--- REGRESSION-G2d: MULTILINESTRING ---' AS test;
SELECT xtf_tid, Verlaeufe_geom::VARCHAR AS wkt
FROM read_xtf_class('testdata/synthetic/geometries/valid.xtf',
    class := 'SO_AGI_Geometries_20260605.Topic.MultiLinienObjekt',
    model_sources := 'testdata/synthetic/geometries');

SELECT '--- REGRESSION-G2e: POLYGON ---' AS test;
SELECT xtf_tid, Flaeche_geom::VARCHAR AS wkt
FROM read_xtf_class('testdata/synthetic/geometries/valid.xtf',
    class := 'SO_AGI_Geometries_20260605.Topic.FlaechenObjekt',
    model_sources := 'testdata/synthetic/geometries');

SELECT '--- REGRESSION-G2f: MULTIPOLYGON ---' AS test;
SELECT xtf_tid, Flaechen_geom::VARCHAR AS wkt
FROM read_xtf_class('testdata/synthetic/geometries/valid.xtf',
    class := 'SO_AGI_Geometries_20260605.Topic.MultiFlaechenObjekt',
    model_sources := 'testdata/synthetic/geometries');

-- REGRESSION-G3: Import SQL maps geometry to GEOMETRY
SELECT '--- REGRESSION-G3: Import SQL geometry type is GEOMETRY ---' AS test;
SELECT sql_statement
FROM ili_generate_import_sql('testdata/synthetic/geometries/valid.xtf',
    schema := 'regression_test',
    model_sources := 'testdata/synthetic/geometries')
WHERE sql_statement ILIKE '%lage_geom%';
-- Expected: "lage_geom" GEOMETRY

-- REGRESSION-G4: CRS-aware import mode
-- Requires: ILI_GEOMETRY_CRS_MAP='SO_AGI_Geometries_20260605.Koord=EPSG:2056'
-- SELECT '--- REGRESSION-G4: CRS typed geometry ---' AS test;
-- SELECT sql_statement
-- FROM ili_generate_import_sql('testdata/synthetic/geometries/valid.xtf',
--     schema := 'regression_test',
--     model_sources := 'testdata/synthetic/geometries')
-- WHERE sql_statement ILIKE '%EPSG:2056%';
-- Expected: "lage_geom" GEOMETRY('EPSG:2056'), SELECT includes CAST
