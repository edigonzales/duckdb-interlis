-- Phase 12: ili_import_xtf smoke test
-- Generates SQL for creating and populating tables from an XTF file

LOAD '${DUCKDB_ILI_EXTENSION}';

-- Verify ili_import_xtf generates correct SQL for simple model
.mode line
SELECT * FROM ili_import_xtf(
    'testdata/synthetic/simple/valid.xtf',
    schema := 'ili_smoke',
    modeldir := 'testdata/synthetic/simple'
);

-- Execute the generated SQL to materialize tables
CREATE SCHEMA IF NOT EXISTS ili_smoke;
CREATE TABLE IF NOT EXISTS ili_smoke.gemeinde (xtf_bid VARCHAR, xtf_tid VARCHAR, xtf_class VARCHAR, name VARCHAR, bfs_nr VARCHAR, unsupported_json VARCHAR);
CREATE TABLE IF NOT EXISTS ili_smoke.abbaustelle (xtf_bid VARCHAR, xtf_tid VARCHAR, xtf_class VARCHAR, name VARCHAR, status VARCHAR, unsupported_json VARCHAR);
INSERT INTO ili_smoke.gemeinde SELECT * FROM read_xtf_class(
    'testdata/synthetic/simple/valid.xtf',
    class := 'SO_AGI_Simple_20260605.Topic.Gemeinde',
    modeldir := 'testdata/synthetic/simple'
);
INSERT INTO ili_smoke.abbaustelle SELECT * FROM read_xtf_class(
    'testdata/synthetic/simple/valid.xtf',
    class := 'SO_AGI_Simple_20260605.Topic.Abbaustelle',
    modeldir := 'testdata/synthetic/simple'
);

-- Verify data
SELECT count(*) AS gemeinde_count FROM ili_smoke.gemeinde;
SELECT count(*) AS abbaustelle_count FROM ili_smoke.abbaustelle;

-- Verify specific data
SELECT name, bfs_nr FROM ili_smoke.gemeinde;
SELECT name, status FROM ili_smoke.abbaustelle;
