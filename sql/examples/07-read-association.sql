-- Read INTERLIS associations from XTF
-- Run with: duckdb -unsigned -cmd "LOAD 'interlis.duckdb_extension'" < sql/examples/07-read-association.sql
--   or:    scripts/dev-duckdb.sh < sql/examples/07-read-association.sql

SELECT '=== Classes: Person ===' AS example;
SELECT * FROM read_xtf_class('testdata/synthetic/associations/valid.xtf',
    class := 'SO_AGI_Associations_20260605.Topic.Person',
    modeldir := 'testdata/synthetic/associations');

SELECT '=== Classes: Grundstueck ===' AS example;
SELECT * FROM read_xtf_class('testdata/synthetic/associations/valid.xtf',
    class := 'SO_AGI_Associations_20260605.Topic.Grundstueck',
    modeldir := 'testdata/synthetic/associations');

SELECT '=== Association: Besitz (raw) ===' AS example;
SELECT * FROM read_xtf_association(
    'testdata/synthetic/associations/valid.xtf',
    association := 'SO_AGI_Associations_20260605.Topic.Besitz',
    modeldir := 'testdata/synthetic/associations');

SELECT '=== JOIN: Person + Grundstueck + Besitz ===' AS example;
WITH
  person AS (
    SELECT * FROM read_xtf_class('testdata/synthetic/associations/valid.xtf',
        class := 'SO_AGI_Associations_20260605.Topic.Person',
        modeldir := 'testdata/synthetic/associations')
  ),
  grundstueck AS (
    SELECT * FROM read_xtf_class('testdata/synthetic/associations/valid.xtf',
        class := 'SO_AGI_Associations_20260605.Topic.Grundstueck',
        modeldir := 'testdata/synthetic/associations')
  ),
  besitz AS (
    SELECT * FROM read_xtf_association('testdata/synthetic/associations/valid.xtf',
        association := 'SO_AGI_Associations_20260605.Topic.Besitz',
        modeldir := 'testdata/synthetic/associations')
  )
SELECT person.name AS person_name,
       grundstueck.nummer AS grundstueck_nummer,
       besitz.anteil AS anteil_prozent
FROM besitz
JOIN person ON besitz.besitzer_ref = person.xtf_tid
JOIN grundstueck ON besitz.grundstueck_ref = grundstueck.xtf_tid
ORDER BY person_name, grundstueck_nummer;

SELECT '=== Association schema inspection ===' AS example;
DESCRIBE SELECT * FROM read_xtf_association(
    'testdata/synthetic/associations/valid.xtf',
    association := 'SO_AGI_Associations_20260605.Topic.Besitz',
    modeldir := 'testdata/synthetic/associations'
);
