-- Phase 12: ili_generate_import_sql smoke test (typed)
-- Generates typed DDL with BIGINT for NUMERIC, VARCHAR for TEXT/Enum/Geom/Struct
-- Run with: duckdb -unsigned -cmd "LOAD 'interlis.duckdb_extension'" < sql/import.sql

-- ================================================================================
-- Part 1: Verify typed SQL generation for simple model
-- ================================================================================
SELECT * FROM ili_generate_import_sql(
    'testdata/synthetic/simple/valid.xtf',
    schema := 'ili_smoke',
    modeldir := 'testdata/synthetic/simple'
);

-- ================================================================================
-- Part 2: Execute typed import for simple model
-- ================================================================================
CREATE SCHEMA IF NOT EXISTS ili_smoke;

CREATE TABLE IF NOT EXISTS ili_smoke.topic__gemeinde (
    xtf_bid VARCHAR, xtf_tid VARCHAR, xtf_class VARCHAR,
    name VARCHAR, bfs_nr BIGINT, unsupported_json VARCHAR
);

CREATE TABLE IF NOT EXISTS ili_smoke.topic__abbaustelle (
    xtf_bid VARCHAR, xtf_tid VARCHAR, xtf_class VARCHAR,
    name VARCHAR, status VARCHAR, unsupported_json VARCHAR
);

INSERT INTO ili_smoke.topic__gemeinde (xtf_bid, xtf_tid, xtf_class, name, bfs_nr, unsupported_json)
    SELECT xtf_bid, xtf_tid, xtf_class, name, CAST(bfs_nr AS BIGINT), unsupported_json
    FROM read_xtf_class('testdata/synthetic/simple/valid.xtf',
        class := 'SO_AGI_Simple_20260605.Topic.Gemeinde',
        modeldir := 'testdata/synthetic/simple');

INSERT INTO ili_smoke.topic__abbaustelle (xtf_bid, xtf_tid, xtf_class, name, status, unsupported_json)
    SELECT xtf_bid, xtf_tid, xtf_class, name, status, unsupported_json
    FROM read_xtf_class('testdata/synthetic/simple/valid.xtf',
        class := 'SO_AGI_Simple_20260605.Topic.Abbaustelle',
        modeldir := 'testdata/synthetic/simple');

-- Verify counts
SELECT count(*) AS gemeinde_count FROM ili_smoke.topic__gemeinde;
SELECT count(*) AS abbaustelle_count FROM ili_smoke.topic__abbaustelle;

-- Verify types
SELECT typeof(bfs_nr) AS bfs_nr_type FROM ili_smoke.topic__gemeinde LIMIT 1;

-- Verify typed arithmetic
SELECT bfs_nr + 1 AS bfs_plus_1 FROM ili_smoke.topic__gemeinde;

-- ================================================================================
-- Part 3: Execute typed import for associations model
-- ================================================================================
CREATE SCHEMA IF NOT EXISTS ili_smoke_assoc;

CREATE TABLE IF NOT EXISTS ili_smoke_assoc.topic__person (
    xtf_bid VARCHAR, xtf_tid VARCHAR, xtf_class VARCHAR,
    name VARCHAR, unsupported_json VARCHAR
);

CREATE TABLE IF NOT EXISTS ili_smoke_assoc.topic__grundstueck (
    xtf_bid VARCHAR, xtf_tid VARCHAR, xtf_class VARCHAR,
    nummer VARCHAR, flaeche BIGINT, unsupported_json VARCHAR
);

CREATE TABLE IF NOT EXISTS ili_smoke_assoc.topic__besitz (
    xtf_bid VARCHAR, xtf_tid VARCHAR, xtf_class VARCHAR,
    besitzer_ref VARCHAR, grundstueck_ref VARCHAR, anteil BIGINT, unsupported_json VARCHAR
);

INSERT INTO ili_smoke_assoc.topic__person (xtf_bid, xtf_tid, xtf_class, name, unsupported_json)
    SELECT xtf_bid, xtf_tid, xtf_class, name, unsupported_json
    FROM read_xtf_class('testdata/synthetic/associations/valid.xtf',
        class := 'SO_AGI_Associations_20260605.Topic.Person',
        modeldir := 'testdata/synthetic/associations');

INSERT INTO ili_smoke_assoc.topic__grundstueck (xtf_bid, xtf_tid, xtf_class, nummer, flaeche, unsupported_json)
    SELECT xtf_bid, xtf_tid, xtf_class, nummer, CAST(flaeche AS BIGINT), unsupported_json
    FROM read_xtf_class('testdata/synthetic/associations/valid.xtf',
        class := 'SO_AGI_Associations_20260605.Topic.Grundstueck',
        modeldir := 'testdata/synthetic/associations');

INSERT INTO ili_smoke_assoc.topic__besitz (xtf_bid, xtf_tid, xtf_class, besitzer_ref, grundstueck_ref, anteil, unsupported_json)
    SELECT xtf_bid, xtf_tid, xtf_class, besitzer_ref, grundstueck_ref, CAST(anteil AS BIGINT), unsupported_json
    FROM read_xtf_association('testdata/synthetic/associations/valid.xtf',
        association := 'SO_AGI_Associations_20260605.Topic.Besitz',
        modeldir := 'testdata/synthetic/associations');

-- Verify join with typed arithmetic
SELECT p.name, g.nummer, g.flaeche, b.anteil, g.flaeche * b.anteil / 100 AS flaeche_anteilig
FROM ili_smoke_assoc.topic__besitz b
JOIN ili_smoke_assoc.topic__person p ON b.besitzer_ref = p.xtf_tid
JOIN ili_smoke_assoc.topic__grundstueck g ON b.grundstueck_ref = g.xtf_tid
ORDER BY 1, 2;
