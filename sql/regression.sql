-- Regression test suite for known bugs (Phase 0 baseline)
-- Run with: duckdb -unsigned -cmd "LOAD '<ext>'" < sql/regression.sql
-- Each section documents a known bug that will be fixed in a later phase.

-- ============================================================================
-- Phase 1: Memory Ownership
-- ============================================================================

-- REGRESSION-1: Error message details visible through DuckDB error
-- Phase 2 fix: DuckDB errors now contain the actual Java error message
-- Expected: DuckDB error with meaningful message (not generic "Validation call failed")
SELECT '--- REGRESSION-1: Error messages now propagated ---' AS test;
SELECT ili_validate_summary_json(
    '/nonexistent/file.xtf',
    '/nonexistent/dir'
);
-- Expected: DuckDB error containing error details

-- REGRESSION-2: Missing input path produces DuckDB error with details
-- Phase 2 fix: DuckDB error contains the file-not-found message
SELECT '--- REGRESSION-2: File-not-found message propagated ---' AS test;
SELECT severity, message
FROM ili_validate('/nonexistent/file.xtf', modeldir := '/nonexistent/dir');
-- Expected: DuckDB error containing error details

-- ============================================================================
-- Phase 3: Request Transfer
-- ============================================================================

-- REGRESSION-3: Paths with special characters produce invalid JSON
-- Currently: may produce malformed JSON or truncated requests
-- After Phase 3: all paths handled correctly
SELECT '--- REGRESSION-3: Special characters in paths ---' AS test;
-- This requires a file with quotes/spaces in the name to test properly.
-- Documented limitation: paths containing " or \ may cause errors.

-- ============================================================================
-- Phase 6: Validator (FIXED)
-- ============================================================================

-- REGRESSION-4: Validation profile parameter now available
SELECT '--- REGRESSION-4: Validation with profiles ---' AS test;
SELECT severity, code, message
FROM ili_validate('testdata/synthetic/simple/valid.xtf',
    modeldir := 'testdata/synthetic/simple',
    profile := 'full');

-- REGRESSION-4b: Fast profile
SELECT severity, code, message
FROM ili_validate('testdata/synthetic/simple/valid.xtf',
    modeldir := 'testdata/synthetic/simple',
    profile := 'fast');

-- REGRESSION-4c: max_messages parameter
SELECT severity, message
FROM ili_validate('testdata/synthetic/simple/invalid.xtf',
    modeldir := 'testdata/synthetic/simple',
    max_messages := 3);

-- REGRESSION-5: CSV parser now handles comma-containing messages
-- The code column is now populated (was always empty before)
-- Verified by the Java CSV parser unit tests (parseCsvLine)

-- ============================================================================
-- Phase 7: XTF Reader
-- ============================================================================

-- REGRESSION-6: Class name matching uses short names only
-- Currently: uses endsWith(".ClassName") — collisions possible
-- After Phase 7: full FQN comparison
SELECT '--- REGRESSION-6: Class matching by short name ---' AS test;
SELECT xtf_class, xtf_tid
FROM read_xtf_class('testdata/synthetic/simple/valid.xtf',
    class := 'SO_AGI_Simple_20260605.Topic.Gemeinde',
    modeldir := 'testdata/synthetic/simple');

-- REGRESSION-7: NULL vs empty string not distinguished
-- Currently: both returned as ""
-- After Phase 7: NULL sentinel (\N) for actual NULL values
SELECT '--- REGRESSION-7: NULL vs empty distinction ---' AS test;
SELECT xtf_tid, Name, bfs_nr
FROM read_xtf_class('testdata/synthetic/simple/valid.xtf',
    class := 'SO_AGI_Simple_20260605.Topic.Gemeinde',
    modeldir := 'testdata/synthetic/simple');

-- ============================================================================
-- Phase 10: Import
-- ============================================================================

-- REGRESSION-8: Table names now use topic__class naming (Phase 10 FIXED)
SELECT '--- REGRESSION-8: topic__class table naming (FIXED) ---' AS test;
SELECT sql_statement
FROM ili_generate_import_sql('testdata/synthetic/simple/valid.xtf',
    schema := 'regression_test',
    modeldir := 'testdata/synthetic/simple')
WHERE sql_statement LIKE '%CREATE TABLE%';

-- REGRESSION-9: mapping parameter is now honored; unsupported values rejected (Phase 10 FIXED)
-- Expected: DuckDB error for unsupported mapping
SELECT '--- REGRESSION-9: mapping rejection (FIXED) ---' AS test;
-- This should now produce a DuckDB error:
-- SELECT sql_statement
-- FROM ili_generate_import_sql('testdata/synthetic/simple/valid.xtf',
--     schema := 'regression_test',
--     modeldir := 'testdata/synthetic/simple',
--     mapping := 'unsupported_mode');

-- REGRESSION-10: Transaction wrapping added (Phase 10 FIXED)
SELECT '--- REGRESSION-10: Transaction wrapping (FIXED) ---' AS test;
SELECT sql_statement
FROM ili_generate_import_sql('testdata/synthetic/simple/valid.xtf',
    schema := 'regression_test',
    modeldir := 'testdata/synthetic/simple')
WHERE sql_statement ILIKE '%BEGIN%' OR sql_statement ILIKE '%COMMIT%';
-- After Phase 10: should return BEGIN/COMMIT rows

-- ============================================================================
-- Phase 2: Error Contract
-- ============================================================================

-- REGRESSION-11: Model info now produces DuckDB error on compilation failure (Phase 2 FIXED)
-- Expected: DuckDB error, NOT an empty result
SELECT '--- REGRESSION-11: Error instead of empty result ---' AS test;
SELECT * FROM ili_models('/nonexistent/directory');
-- Expected: DuckDB error with compilation failure details

-- REGRESSION-12: No ERROR: prefix in result data (Phase 2 FIXED)
-- Phase 2 fix: errors always use status > 0 with JSON, never ERROR: prefix in data
-- Verification: valid XTF read should produce clean data with no ERROR: rows
SELECT '--- REGRESSION-12: No ERROR prefix in data ---' AS test;
SELECT * FROM read_xtf_objects('testdata/synthetic/simple/valid.xtf',
    modeldir := 'testdata/synthetic/simple');
-- Expected: data rows only, no rows containing "ERROR:"

-- ============================================================================
-- Phase 0 (Baseline): Geometry column behavior – NOW IMPLEMENTED (Phases 1-3)
-- These tests now show the post-implementation state: GEOMETRY columns,
-- hex-WKB transport, no WKT cast needed.
-- ============================================================================

-- REGRESSION-G0: Geometry columns are GEOMETRY type (Phase 2)
SELECT '--- REGRESSION-G0: Geometry column type is GEOMETRY ---' AS test;
SELECT typeof(Lage_geom) AS geometry_column_type
FROM read_xtf_class('testdata/synthetic/geometries/valid.xtf',
    class := 'SO_AGI_Geometries_20260605.Topic.PunktObjekt',
    modeldir := 'testdata/synthetic/geometries');
-- Expected: GEOMETRY

-- REGRESSION-G1: Geometry WKT via CAST (Phase 2)
SELECT '--- REGRESSION-G1: Geometry WKT via CAST ---' AS test;
SELECT xtf_tid, Name, Lage_geom::VARCHAR AS wkt
FROM read_xtf_class('testdata/synthetic/geometries/valid.xtf',
    class := 'SO_AGI_Geometries_20260605.Topic.PunktObjekt',
    modeldir := 'testdata/synthetic/geometries');
-- Expected: Lage_geom::VARCHAR = 'POINT (2605000 1203000)'

-- REGRESSION-G2: All basic geometry types (Phase 2)
-- GEOMETRY columns with valid WKT representation
SELECT '--- REGRESSION-G2a: POINT ---' AS test;
SELECT xtf_tid, Lage_geom::VARCHAR AS wkt
FROM read_xtf_class('testdata/synthetic/geometries/valid.xtf',
    class := 'SO_AGI_Geometries_20260605.Topic.PunktObjekt',
    modeldir := 'testdata/synthetic/geometries');

SELECT '--- REGRESSION-G2b: MULTIPOINT ---' AS test;
SELECT xtf_tid, Lagen_geom::VARCHAR AS wkt
FROM read_xtf_class('testdata/synthetic/geometries/valid.xtf',
    class := 'SO_AGI_Geometries_20260605.Topic.MultiPunktObjekt',
    modeldir := 'testdata/synthetic/geometries');

SELECT '--- REGRESSION-G2c: LINESTRING ---' AS test;
SELECT xtf_tid, Verlauf_geom::VARCHAR AS wkt
FROM read_xtf_class('testdata/synthetic/geometries/valid.xtf',
    class := 'SO_AGI_Geometries_20260605.Topic.LinienObjekt',
    modeldir := 'testdata/synthetic/geometries');

SELECT '--- REGRESSION-G2d: MULTILINESTRING ---' AS test;
SELECT xtf_tid, Verlaeufe_geom::VARCHAR AS wkt
FROM read_xtf_class('testdata/synthetic/geometries/valid.xtf',
    class := 'SO_AGI_Geometries_20260605.Topic.MultiLinienObjekt',
    modeldir := 'testdata/synthetic/geometries');

SELECT '--- REGRESSION-G2e: POLYGON ---' AS test;
SELECT xtf_tid, Flaeche_geom::VARCHAR AS wkt
FROM read_xtf_class('testdata/synthetic/geometries/valid.xtf',
    class := 'SO_AGI_Geometries_20260605.Topic.FlaechenObjekt',
    modeldir := 'testdata/synthetic/geometries');

SELECT '--- REGRESSION-G2f: MULTIPOLYGON ---' AS test;
SELECT xtf_tid, Flaechen_geom::VARCHAR AS wkt
FROM read_xtf_class('testdata/synthetic/geometries/valid.xtf',
    class := 'SO_AGI_Geometries_20260605.Topic.MultiFlaechenObjekt',
    modeldir := 'testdata/synthetic/geometries');

-- REGRESSION-G3: Import SQL maps geometry to GEOMETRY (Phase 3)
SELECT '--- REGRESSION-G3: Import SQL geometry type is GEOMETRY ---' AS test;
SELECT sql_statement
FROM ili_generate_import_sql('testdata/synthetic/geometries/valid.xtf',
    schema := 'regression_test',
    modeldir := 'testdata/synthetic/geometries')
WHERE sql_statement ILIKE '%lage_geom%';
-- Expected: "lage_geom" GEOMETRY

-- REGRESSION-G4: CRS-aware import mode (Phase 4)
-- Requires: ILI_GEOMETRY_CRS_MAP='SO_AGI_Geometries_20260605.Koord=EPSG:2056'
-- SELECT '--- REGRESSION-G4: CRS typed geometry ---' AS test;
-- SELECT sql_statement
-- FROM ili_generate_import_sql('testdata/synthetic/geometries/valid.xtf',
--     schema := 'regression_test',
--     modeldir := 'testdata/synthetic/geometries')
-- WHERE sql_statement ILIKE '%EPSG:2056%';
-- Expected: "lage_geom" GEOMETRY('EPSG:2056'), SELECT includes CAST
