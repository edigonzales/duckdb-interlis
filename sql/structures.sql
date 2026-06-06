-- Structure and BAG OF STRUCTURE tests (Phase 9)
-- Run with: duckdb -unsigned -cmd "LOAD 'interlis.duckdb_extension'" < sql/structures.sql

SELECT '=== Schema ===' AS test;
SELECT column_name, data_type
FROM information_schema.columns
WHERE table_name = 'read_xtf_class'
LIMIT 0;
-- Manual schema verification: xtf_bid, xtf_tid, xtf_class, Name, Adresse_json, Kontakte_json, unsupported_json

SELECT '=== Alle Betriebe ===' AS test;
SELECT *
FROM read_xtf_class(
    'testdata/synthetic/structures/valid.xtf',
    class := 'SO_AGI_Structures_20260605.Topic.Betrieb',
    modeldir := 'testdata/synthetic/structures'
);

SELECT '=== Struktur-Werte (JSON-Extraktion) ===' AS test;
SELECT
    Name,
    CASE WHEN Adresse_json != ''
        THEN json_extract_string(Adresse_json, '$.Strasse')
        ELSE NULL END AS Strasse,
    CASE WHEN Adresse_json != ''
        THEN json_extract_string(Adresse_json, '$.PLZ')
        ELSE NULL END AS PLZ,
    CASE WHEN Adresse_json != ''
        THEN json_extract_string(Adresse_json, '$.Ort')
        ELSE NULL END AS Ort,
    CASE WHEN Kontakte_json != ''
        THEN json_array_length(Kontakte_json)
        ELSE NULL END AS Anzahl_Kontakte
FROM read_xtf_class(
    'testdata/synthetic/structures/valid.xtf',
    class := 'SO_AGI_Structures_20260605.Topic.Betrieb',
    modeldir := 'testdata/synthetic/structures'
);

SELECT '=== BAG-Elemente einzeln ===' AS test;
SELECT
    Name,
    json_extract_string(unnested, '$.Typ') AS Typ,
    json_extract_string(unnested, '$.Telefon') AS Telefon,
    json_extract_string(unnested, '$.Email') AS Email
FROM (
    SELECT Name, unnest(json_transform(Kontakte_json, '["VARCHAR"]')) AS unnested
    FROM read_xtf_class(
        'testdata/synthetic/structures/valid.xtf',
        class := 'SO_AGI_Structures_20260605.Topic.Betrieb',
        modeldir := 'testdata/synthetic/structures'
    )
);

SELECT '=== Missing/Empty Test ===' AS test;
SELECT
    Name,
    Adresse_json = '' AS adresse_missing,
    Kontakte_json = '[]' AS kontakte_empty
FROM read_xtf_class(
    'testdata/synthetic/structures/valid.xtf',
    class := 'SO_AGI_Structures_20260605.Topic.Betrieb',
    modeldir := 'testdata/synthetic/structures'
);

SELECT '=== unsupported_json (should be empty) ===' AS test;
SELECT
    Name,
    unsupported_json
FROM read_xtf_class(
    'testdata/synthetic/structures/valid.xtf',
    class := 'SO_AGI_Structures_20260605.Topic.Betrieb',
    modeldir := 'testdata/synthetic/structures'
);

SELECT '=== nested := json parameter ===' AS test;
SELECT Name, Adresse_json, Kontakte_json
FROM read_xtf_class(
    'testdata/synthetic/structures/valid.xtf',
    class := 'SO_AGI_Structures_20260605.Topic.Betrieb',
    modeldir := 'testdata/synthetic/structures',
    nested := 'json'
);
