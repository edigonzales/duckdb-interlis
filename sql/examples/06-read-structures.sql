-- Introspect STRUCTURE definitions used by a class
-- Run with: duckdb -unsigned -cmd "LOAD 'interlis.duckdb_extension'" < sql/examples/06-read-structures.sql
--   or:    scripts/dev-duckdb.sh < sql/examples/06-read-structures.sql

SELECT '=== read_xtf_structures: all structures for Betrieb ===' AS example;
SELECT structure_name, attribute_name, interlis_type, logical_type, kind, card_min, card_max
FROM read_xtf_structures(
    class := 'SO_AGI_Structures_20260605.Topic.Betrieb',
    modeldir := 'testdata/synthetic/structures'
);

SELECT '=== read_xtf_structures: filter to Adresse structure ===' AS example;
SELECT attribute_name, logical_type, kind
FROM read_xtf_structures(
    class := 'SO_AGI_Structures_20260605.Topic.Betrieb',
    modeldir := 'testdata/synthetic/structures'
)
WHERE structure_name = 'Adresse';

SELECT '=== read_xtf_structures: filter to Kontakt structure ===' AS example;
SELECT attribute_name, logical_type, kind, enum_values_json
FROM read_xtf_structures(
    class := 'SO_AGI_Structures_20260605.Topic.Betrieb',
    modeldir := 'testdata/synthetic/structures'
)
WHERE structure_name = 'Kontakt';
