-- Smoke test for DuckDB ILI extension
-- Run with: duckdb -unsigned < sql/smoke.sql
-- Or: scripts/smoke-test.sh

LOAD '/Users/stefan/sources/duckdb-interlis/duckdb-extension/build/ili.duckdb_extension';

SELECT ili_extension_version();
