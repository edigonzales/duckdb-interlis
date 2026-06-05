-- Association and Reference tests (Phase 10)
-- Run with: duckdb -unsigned < sql/associations.sql

LOAD '/Users/stefan/sources/duckdb-interlis/duckdb-extension/build/interlis.duckdb_extension';

SELECT '=== read_xtf_class: Person ===' AS test;
SELECT * FROM read_xtf_class(
    'testdata/synthetic/associations/valid.xtf',
    class := 'SO_AGI_Associations_20260605.Topic.Person',
    modeldir := 'testdata/synthetic/associations'
);

SELECT '=== read_xtf_class: Grundstueck ===' AS test;
SELECT * FROM read_xtf_class(
    'testdata/synthetic/associations/valid.xtf',
    class := 'SO_AGI_Associations_20260605.Topic.Grundstueck',
    modeldir := 'testdata/synthetic/associations'
);

SELECT '=== read_xtf_association: Besitz ===' AS test;
SELECT * FROM read_xtf_association(
    'testdata/synthetic/associations/valid.xtf',
    association := 'SO_AGI_Associations_20260605.Topic.Besitz',
    modeldir := 'testdata/synthetic/associations'
);

SELECT '=== JOIN: Person + Grundstueck + Besitz ===' AS test;
WITH
p AS (
  SELECT * FROM read_xtf_class('testdata/synthetic/associations/valid.xtf', class := 'SO_AGI_Associations_20260605.Topic.Person', modeldir := 'testdata/synthetic/associations')
),
g AS (
  SELECT * FROM read_xtf_class('testdata/synthetic/associations/valid.xtf', class := 'SO_AGI_Associations_20260605.Topic.Grundstueck', modeldir := 'testdata/synthetic/associations')
),
b AS (
  SELECT * FROM read_xtf_association('testdata/synthetic/associations/valid.xtf', association := 'SO_AGI_Associations_20260605.Topic.Besitz', modeldir := 'testdata/synthetic/associations')
)
SELECT p.name, g.nummer, b.anteil
FROM b
JOIN p ON b.besitzer_ref = p.xtf_tid
JOIN g ON b.grundstueck_ref = g.xtf_tid;

SELECT '=== Association Schema ===' AS test;
SELECT * FROM read_xtf_association(
    'testdata/synthetic/associations/valid.xtf',
    association := 'SO_AGI_Associations_20260605.Topic.Besitz',
    modeldir := 'testdata/synthetic/associations'
) LIMIT 0;
