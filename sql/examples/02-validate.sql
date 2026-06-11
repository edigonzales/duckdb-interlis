-- Validate XTF files
-- Run with: duckdb -unsigned -cmd "LOAD 'interlis.duckdb_extension'" < sql/examples/02-validate.sql
--   or:    scripts/dev-duckdb.sh < sql/examples/02-validate.sql

SELECT '=== Validate: valid XTF (summary) ===' AS example;
SELECT json_extract(result, '$.valid') AS valid,
       json_extract(result, '$.errorCount') AS errors,
       json_extract(result, '$.warningCount') AS warnings
FROM (
    SELECT ili_validate_summary_json(
        'testdata/synthetic/simple/valid.xtf',
        'testdata/synthetic/simple'
    ) AS result
);

SELECT '=== Validate: invalid XTF (summary) ===' AS example;
SELECT json_extract(result, '$.valid') AS valid,
       json_extract(result, '$.errorCount') AS errors,
       json_extract(result, '$.warningCount') AS warnings,
       json_extract(result, '$.infoCount') AS infos
FROM (
    SELECT ili_validate_summary_json(
        'testdata/synthetic/simple/invalid.xtf',
        'testdata/synthetic/simple'
    ) AS result
);

SELECT '=== Validate: structures XTF (summary) ===' AS example;
SELECT json_extract(result, '$.valid') AS valid,
       json_extract(result, '$.errorCount') AS errors
FROM (
    SELECT ili_validate_summary_json(
        'testdata/synthetic/structures/valid.xtf',
        'testdata/synthetic/structures'
    ) AS result
);

SELECT '=== Validate: ERROR messages only ===' AS example;
SELECT severity, code, message, line, xtf_tid
FROM ili_validate('testdata/synthetic/simple/invalid.xtf',
    modeldir := 'testdata/synthetic/simple')
WHERE severity = 'ERROR'
ORDER BY line;

SELECT '=== Validate: grouped by class_name ===' AS example;
SELECT class_name, count(*) AS message_count
FROM ili_validate('testdata/synthetic/simple/invalid.xtf',
    modeldir := 'testdata/synthetic/simple')
WHERE class_name IS NOT NULL AND class_name <> ''
GROUP BY class_name
ORDER BY message_count DESC;

SELECT '=== Validate: filter by severity and attribute ===' AS example;
SELECT severity, message, attribute_name, line
FROM ili_validate('testdata/synthetic/simple/invalid.xtf',
    modeldir := 'testdata/synthetic/simple')
WHERE severity IN ('ERROR', 'WARNING')
  AND attribute_name IS NOT NULL
ORDER BY severity, line;

SELECT '=== Validate: with profile=structural ===' AS example;
SELECT severity, message, line
FROM ili_validate('testdata/synthetic/simple/invalid.xtf',
    modeldir := 'testdata/synthetic/simple',
    profile := 'structural')
ORDER BY line;

SELECT '=== Validate: with max_messages limit ===' AS example;
SELECT severity, message, line
FROM ili_validate('testdata/synthetic/simple/invalid.xtf',
    modeldir := 'testdata/synthetic/simple',
    max_messages := 5);
