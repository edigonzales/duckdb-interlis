-- Regression test suite for known bugs (Phase 0 baseline)
-- Run with: duckdb -unsigned -cmd "LOAD '<ext>'" < sql/regression.sql
-- Each section documents a known bug that will be fixed in a later phase.

-- ============================================================================
-- Phase 1: Memory Ownership
-- ============================================================================

-- REGRESSION-1: Error message details lost on native call failure
-- Currently: generic "Validation call failed" instead of actual error
-- After Phase 1: actual Java error message visible
SELECT '--- REGRESSION-1: Lost error messages ---' AS test;
SELECT ili_validate_summary_json(
    '/nonexistent/file.xtf',
    '/nonexistent/dir'
);

-- REGRESSION-2: Missing input path returns lost error payload
-- Currently: "Validation call failed" (the file-not-found message from Java is lost)
-- After Phase 1: actual "File not found" message visible
SELECT '--- REGRESSION-2: Lost file-not-found message ---' AS test;
SELECT severity, message
FROM ili_validate('/nonexistent/file.xtf', modeldir := '/nonexistent/dir');

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
-- Phase 6: Validator
-- ============================================================================

-- REGRESSION-4: No validation profile parameter
-- Currently: CONSTRAINT + AREA disabled, no way to enable via SQL
-- After Phase 6: profile parameter available
SELECT '--- REGRESSION-4: No validation profile ---' AS test;
SELECT severity, message
FROM ili_validate('testdata/synthetic/simple/valid.xtf',
    modeldir := 'testdata/synthetic/simple');

-- REGRESSION-5: Validation messages with commas
-- Currently: CSV parsing with split(",") breaks on embedded commas
-- After Phase 6: proper CSV parser handles comma-containing messages
-- Note: requires test data with comma in validation message

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

-- REGRESSION-8: Table names use short class names
-- Currently: table names are just the class name (e.g., "gemeinde")
-- After Phase 10: topic__class or model__topic__class naming
SELECT '--- REGRESSION-8: Short class name tables ---' AS test;
SELECT sql_statement
FROM ili_import_xtf('testdata/synthetic/simple/valid.xtf',
    schema := 'regression_test',
    modeldir := 'testdata/synthetic/simple')
WHERE sql_statement LIKE '%CREATE TABLE%';

-- REGRESSION-9: mapping parameter is ignored
-- Currently: mapping always "relational" regardless of parameter
-- After Phase 10: mapping parameter honored or UNSUPPORTED returned
SELECT '--- REGRESSION-9: mapping parameter ignored ---' AS test;
SELECT sql_statement
FROM ili_import_xtf('testdata/synthetic/simple/valid.xtf',
    schema := 'regression_test',
    modeldir := 'testdata/synthetic/simple',
    mapping := 'unsupported_mode');

-- REGRESSION-10: No transaction wrapping
-- Currently: SQL has no BEGIN/COMMIT
-- After Phase 10: BEGIN TRANSACTION / COMMIT wrapping
SELECT '--- REGRESSION-10: No transaction ---' AS test;
SELECT sql_statement
FROM ili_import_xtf('testdata/synthetic/simple/valid.xtf',
    schema := 'regression_test',
    modeldir := 'testdata/synthetic/simple')
WHERE sql_statement ILIKE '%BEGIN%' OR sql_statement ILIKE '%COMMIT%';
-- Currently returns 0 rows; after Phase 10 should return BEGIN/COMMIT rows

-- ============================================================================
-- Phase 2: Error Contract
-- ============================================================================

-- REGRESSION-11: Model info returns empty result on error instead of error
-- Currently: invalid modeldir returns empty result, not error
-- After Phase 2: clear error message
SELECT '--- REGRESSION-11: Silent empty result on error ---' AS test;
SELECT * FROM ili_models('/nonexistent/directory');
-- Currently: returns 0 rows silently; after Phase 2 should error

-- REGRESSION-12: "ERROR:" prefix in result data
-- Currently: some native functions return status 0 with "ERROR:" strings
-- After Phase 2: errors always use error status code, never ERROR: prefix in data
SELECT '--- REGRESSION-12: ERROR prefix in data ---' AS test;
-- Hard to test without triggering a Java exception; documented limitation.
