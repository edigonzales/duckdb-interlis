-- External validation: Abbaustellen (SO)
-- Model: SO_AFU_ABBAUSTELLEN_Publikation_20221103
-- Source: https://files.geo.so.ch/ch.so.afu.abbaustellen/
-- Model repo: https://geo.so.ch/models + https://models.interlis.ch
--
-- Run with: duckdb -unsigned -cmd "LOAD 'interlis.duckdb_extension'" < sql/validate-external.sql
-- Prerequisite: scripts/download-testdata.sh

-- Abbaustellen (simple XTF, ~37 KB)
SELECT '=== Abbaustellen: Summary ===' AS info;
SELECT json_extract(result, '$.valid') AS valid,
       json_extract(result, '$.errorCount') AS errors,
       json_extract(result, '$.warningCount') AS warnings
FROM (
    SELECT ili_validate_summary_json(
        'testdata/external/ch.so.afu.abbaustellen/ch.so.afu.abbaustellen.xtf',
        'https://models.interlis.ch;https://geo.so.ch/models'
    ) AS result
);

SELECT '=== Abbaustellen: Errors ===' AS info;
SELECT severity, message, line, xtf_tid
FROM ili_validate(
    'testdata/external/ch.so.afu.abbaustellen/ch.so.afu.abbaustellen.xtf',
    modeldir := 'https://models.interlis.ch;https://geo.so.ch/models'
)
WHERE severity = 'ERROR'
LIMIT 10;

SELECT '=== Abbaustellen: Object count per class ===' AS info;
SELECT class_name, count(*) AS cnt
FROM ili_validate(
    'testdata/external/ch.so.afu.abbaustellen/ch.so.afu.abbaustellen.xtf',
    modeldir := 'https://models.interlis.ch;https://geo.so.ch/models'
)
WHERE class_name IS NOT NULL AND class_name <> ''
GROUP BY class_name
ORDER BY cnt DESC;
