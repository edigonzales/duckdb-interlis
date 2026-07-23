-- Inspect INTERLIS model metadata
-- Run with: duckdb -unsigned -cmd "LOAD 'interlis.duckdb_extension'" < sql/examples/03-inspect.sql
--   or:    scripts/dev-duckdb.sh < sql/examples/03-inspect.sql

SELECT '=== ili_models: list all models ===' AS example;
SELECT name, version, language, ili_version
FROM ili_models(NULL, model_sources := 'testdata/synthetic/simple');

SELECT '=== ili_models: filter by model name ===' AS example;
SELECT name, version, language, ili_version
FROM ili_models('SO_AGI_Simple_20260605',
    model_sources := 'testdata/synthetic/simple');

SELECT '=== ili_models: structures model ===' AS example;
SELECT name, version, language, ili_version
FROM ili_models(NULL, model_sources := 'testdata/synthetic/structures');

SELECT '=== ili_topics: list all topics ===' AS example;
SELECT model_name, topic_name, kind
FROM ili_topics(NULL, model_sources := 'testdata/synthetic/simple');

SELECT '=== ili_topics: filter by model ===' AS example;
SELECT model_name, topic_name, kind
FROM ili_topics('SO_AGI_Simple_20260605',
    model_sources := 'testdata/synthetic/simple');

SELECT '=== ili_classes: list all classes (simple model) ===' AS example;
SELECT model_name, topic_name, class_name, kind, is_abstract
FROM ili_classes(NULL, model_sources := 'testdata/synthetic/simple');

SELECT '=== ili_classes: filter by model ===' AS example;
SELECT topic_name, class_name, is_abstract, is_extended
FROM ili_classes('SO_AGI_Simple_20260605',
    model_sources := 'testdata/synthetic/simple');

SELECT '=== ili_classes: structures model ===' AS example;
SELECT model_name, topic_name, class_name, kind
FROM ili_classes(NULL, model_sources := 'testdata/synthetic/structures');

SELECT '=== ili_classes: geometries model ===' AS example;
SELECT model_name, topic_name, class_name, kind
FROM ili_classes(NULL, model_sources := 'testdata/synthetic/geometries');

SELECT '=== ili_attributes: attributes of Gemeinde ===' AS example;
SELECT attr_name, type_name, kind, is_mandatory, card_min, card_max
FROM ili_attributes('SO_AGI_Simple_20260605',
    model_sources := 'testdata/synthetic/simple',
    class := 'Gemeinde');

SELECT '=== ili_attributes: attributes of Abbaustelle ===' AS example;
SELECT attr_name, type_name, kind, is_mandatory, card_min, card_max
FROM ili_attributes('SO_AGI_Simple_20260605',
    model_sources := 'testdata/synthetic/simple',
    class := 'Abbaustelle');

SELECT '=== ili_attributes: filter mandatory only ===' AS example;
SELECT class_name, attr_name, type_name
FROM ili_attributes(NULL, model_sources := 'testdata/synthetic/simple')
WHERE is_mandatory = 'true';

SELECT '=== ili_enumerations: list all enum values ===' AS example;
SELECT model_name, enum_name, element
FROM ili_enumerations(NULL, model_sources := 'testdata/synthetic/simple');

SELECT '=== ili_enumerations: filter by model ===' AS example;
SELECT enum_name, element
FROM ili_enumerations('SO_AGI_Simple_20260605',
    model_sources := 'testdata/synthetic/simple');

SELECT '=== Direct .ili file and mixed source separators ===' AS example;
SELECT model_name, topic_name, class_name, kind
FROM ili_classes(
    'SO_AGI_Simple_20260605',
    model_sources := 'testdata/synthetic/simple/SO_AGI_Simple_20260605.ili,testdata/synthetic/structures;testdata/synthetic/keywords'
);
