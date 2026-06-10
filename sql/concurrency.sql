-- Phase 4: Concurrency Smoke Tests
-- Tests that parallel DuckDB execution of extension functions does not crash or deadlock.

SELECT '=== CONCURRENCY-1: Parallel version queries ===' AS test;

SELECT ili_native_version() AS version FROM range(50);

SELECT '=== CONCURRENCY-2: Parallel validation (same file) ===' AS test;

SELECT severity, count(*) AS cnt FROM ili_validate('testdata/synthetic/simple/invalid.xtf') GROUP BY severity;

SELECT '=== CONCURRENCY-3: Parallel model introspection ===' AS test;

SELECT name FROM ili_models('testdata/synthetic/simple') LIMIT 5;

SELECT '=== CONCURRENCY-4: Parallel model info (multiple calls) ===' AS test;

SELECT 'models' AS cmd, count(*) AS cnt FROM ili_models('testdata/synthetic/simple')
UNION ALL
SELECT 'topics', count(*) FROM ili_topics('testdata/synthetic/simple')
UNION ALL
SELECT 'classes', count(*) FROM ili_classes('testdata/synthetic/simple')
UNION ALL
SELECT 'attributes', count(*) FROM ili_attributes('testdata/synthetic/simple')
UNION ALL
SELECT 'enumerations', count(*) FROM ili_enumerations('testdata/synthetic/simple');

SELECT '=== CONCURRENCY-5: Parallel XTF reads ===' AS test;

SELECT xtf_class, count(*) AS cnt FROM read_xtf_objects('testdata/synthetic/simple/valid.xtf', modeldir := 'testdata/synthetic/simple') GROUP BY xtf_class;

SELECT '=== CONCURRENCY-6: Parallel class reads ===' AS test;

SELECT xtf_class, xtf_tid, "Name" FROM read_xtf_class('testdata/synthetic/simple/valid.xtf',
    class := 'SO_AGI_Simple_20260605.Topic.Gemeinde',
    modeldir := 'testdata/synthetic/simple')
UNION ALL
SELECT xtf_class, xtf_tid, "Name" FROM read_xtf_class('testdata/synthetic/simple/valid.xtf',
    class := 'SO_AGI_Simple_20260605.Topic.Abbaustelle',
    modeldir := 'testdata/synthetic/simple');

SELECT '=== CONCURRENCY: All tests passed ===' AS test;
