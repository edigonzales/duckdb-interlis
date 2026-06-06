-- Import XTF into DuckDB schema (typed DDL generation)
-- Run with: duckdb -unsigned -cmd "LOAD 'interlis.duckdb_extension'" < sql/examples/08-import.sql
--   or:    scripts/dev-duckdb.sh < sql/examples/08-import.sql

SELECT '=== ili_import_xtf: generate typed SQL (simple model) ===' AS example;
SELECT sql_statement
FROM ili_import_xtf('testdata/synthetic/simple/valid.xtf',
    schema := 'ili_example',
    modeldir := 'testdata/synthetic/simple');

SELECT '=== ili_import_xtf: generate typed SQL (associations model) ===' AS example;
SELECT sql_statement
FROM ili_import_xtf('testdata/synthetic/associations/valid.xtf',
    schema := 'ili_example_assoc',
    modeldir := 'testdata/synthetic/associations');

SELECT '=== ili_import_xtf: mit explizitem mapping-Parameter (Default: relational) ===' AS example;
SELECT sql_statement
FROM ili_import_xtf('testdata/synthetic/simple/valid.xtf',
    schema := 'ili_example',
    modeldir := 'testdata/synthetic/simple',
    mapping := 'relational');

SELECT '=== Manual typed import: simple model ===' AS example;
CREATE SCHEMA IF NOT EXISTS ili_example;

CREATE TABLE IF NOT EXISTS ili_example.gemeinde (
    xtf_bid VARCHAR, xtf_tid VARCHAR, xtf_class VARCHAR,
    name VARCHAR, bfs_nr BIGINT, unsupported_json VARCHAR
);

CREATE TABLE IF NOT EXISTS ili_example.abbaustelle (
    xtf_bid VARCHAR, xtf_tid VARCHAR, xtf_class VARCHAR,
    name VARCHAR, status VARCHAR, unsupported_json VARCHAR
);

INSERT INTO ili_example.gemeinde (xtf_bid, xtf_tid, xtf_class, name, bfs_nr, unsupported_json)
    SELECT xtf_bid, xtf_tid, xtf_class, name, CAST(bfs_nr AS BIGINT), unsupported_json
    FROM read_xtf_class('testdata/synthetic/simple/valid.xtf',
        class := 'SO_AGI_Simple_20260605.Topic.Gemeinde',
        modeldir := 'testdata/synthetic/simple');

INSERT INTO ili_example.abbaustelle (xtf_bid, xtf_tid, xtf_class, name, status, unsupported_json)
    SELECT xtf_bid, xtf_tid, xtf_class, name, status, unsupported_json
    FROM read_xtf_class('testdata/synthetic/simple/valid.xtf',
        class := 'SO_AGI_Simple_20260605.Topic.Abbaustelle',
        modeldir := 'testdata/synthetic/simple');

SELECT '=== Verify: counts ===' AS example;
SELECT count(*) AS gemeinde_count FROM ili_example.gemeinde;
SELECT count(*) AS abbaustelle_count FROM ili_example.abbaustelle;

SELECT '=== Verify: typed arithmetic ===' AS example;
SELECT typeof(bfs_nr) AS bfs_nr_type FROM ili_example.gemeinde LIMIT 1;
SELECT bfs_nr + 1 AS bfs_plus_1 FROM ili_example.gemeinde;

SELECT '=== Manual typed import: associations model ===' AS example;
CREATE SCHEMA IF NOT EXISTS ili_example_assoc;

CREATE TABLE IF NOT EXISTS ili_example_assoc.person (
    xtf_bid VARCHAR, xtf_tid VARCHAR, xtf_class VARCHAR,
    name VARCHAR, unsupported_json VARCHAR
);

CREATE TABLE IF NOT EXISTS ili_example_assoc.grundstueck (
    xtf_bid VARCHAR, xtf_tid VARCHAR, xtf_class VARCHAR,
    nummer VARCHAR, flaeche BIGINT, unsupported_json VARCHAR
);

CREATE TABLE IF NOT EXISTS ili_example_assoc.besitz (
    xtf_bid VARCHAR, xtf_tid VARCHAR, xtf_class VARCHAR,
    besitzer_ref VARCHAR, grundstueck_ref VARCHAR, anteil BIGINT, unsupported_json VARCHAR
);

INSERT INTO ili_example_assoc.person (xtf_bid, xtf_tid, xtf_class, name, unsupported_json)
    SELECT xtf_bid, xtf_tid, xtf_class, name, unsupported_json
    FROM read_xtf_class('testdata/synthetic/associations/valid.xtf',
        class := 'SO_AGI_Associations_20260605.Topic.Person',
        modeldir := 'testdata/synthetic/associations');

INSERT INTO ili_example_assoc.grundstueck (xtf_bid, xtf_tid, xtf_class, nummer, flaeche, unsupported_json)
    SELECT xtf_bid, xtf_tid, xtf_class, nummer, CAST(flaeche AS BIGINT), unsupported_json
    FROM read_xtf_class('testdata/synthetic/associations/valid.xtf',
        class := 'SO_AGI_Associations_20260605.Topic.Grundstueck',
        modeldir := 'testdata/synthetic/associations');

INSERT INTO ili_example_assoc.besitz (xtf_bid, xtf_tid, xtf_class, besitzer_ref, grundstueck_ref, anteil, unsupported_json)
    SELECT xtf_bid, xtf_tid, xtf_class, besitzer_ref, grundstueck_ref, CAST(anteil AS BIGINT), unsupported_json
    FROM read_xtf_association('testdata/synthetic/associations/valid.xtf',
        association := 'SO_AGI_Associations_20260605.Topic.Besitz',
        modeldir := 'testdata/synthetic/associations');

SELECT '=== Verify: typed JOIN with arithmetic ===' AS example;
SELECT p.name, g.nummer, g.flaeche, b.anteil,
    g.flaeche * b.anteil / 100 AS flaeche_anteilig
FROM ili_example_assoc.besitz b
JOIN ili_example_assoc.person p ON b.besitzer_ref = p.xtf_tid
JOIN ili_example_assoc.grundstueck g ON b.grundstueck_ref = g.xtf_tid
ORDER BY 1, 2;
