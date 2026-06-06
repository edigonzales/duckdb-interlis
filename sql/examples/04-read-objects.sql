-- Generic XTF object stream reader
-- Run with: duckdb -unsigned -cmd "LOAD 'interlis.duckdb_extension'" < sql/examples/04-read-objects.sql
--   or:    scripts/dev-duckdb.sh < sql/examples/04-read-objects.sql

SELECT '=== read_xtf_objects: all objects with class info ===' AS example;
SELECT xtf_bid, xtf_topic, xtf_class, xtf_tid, operation
FROM read_xtf_objects('testdata/synthetic/simple/valid.xtf',
    modeldir := 'testdata/synthetic/simple');

SELECT '=== read_xtf_objects: count by class ===' AS example;
SELECT xtf_class, count(*) AS cnt
FROM read_xtf_objects('testdata/synthetic/simple/valid.xtf',
    modeldir := 'testdata/synthetic/simple')
GROUP BY xtf_class
ORDER BY cnt DESC;

SELECT '=== read_xtf_objects: extract JSON attributes ===' AS example;
SELECT xtf_tid, xtf_class,
    json_extract_string(attributes_json, '$.Name') AS name,
    attributes_json
FROM read_xtf_objects('testdata/synthetic/simple/valid.xtf',
    modeldir := 'testdata/synthetic/simple');

SELECT '=== read_xtf_objects: structures XTF ===' AS example;
SELECT xtf_class, xtf_tid,
    json_array_length(attributes_json) AS attr_count,
    LEFT(attributes_json, 120) AS attributes_preview
FROM read_xtf_objects('testdata/synthetic/structures/valid.xtf',
    modeldir := 'testdata/synthetic/structures');

SELECT '=== read_xtf_objects: mit optionalem models-Filter ===' AS example;
SELECT xtf_class, xtf_tid, operation
FROM read_xtf_objects('testdata/synthetic/simple/valid.xtf',
    modeldir := 'testdata/synthetic/simple',
    models := 'SO_AGI_Simple_20260605');

SELECT '=== read_xtf_objects: geometries XTF ===' AS example;
SELECT xtf_class, xtf_tid,
    CASE WHEN geom_json != '' THEN 'has geometry' ELSE 'no geometry' END AS has_geom,
    LEFT(geom_json, 80) AS geom_preview
FROM read_xtf_objects('testdata/synthetic/geometries/valid.xtf',
    modeldir := 'testdata/synthetic/geometries');
