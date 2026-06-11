-- Read specific INTERLIS classes from XTF
-- Run with: duckdb -unsigned -cmd "LOAD 'interlis.duckdb_extension'" < sql/examples/05-read-class.sql
--   or:    scripts/dev-duckdb.sh < sql/examples/05-read-class.sql

SELECT '=== Simple class: Gemeinde ===' AS example;
SELECT xtf_tid, Name, bfs_nr
FROM read_xtf_class('testdata/synthetic/simple/valid.xtf',
    class := 'SO_AGI_Simple_20260605.Topic.Gemeinde',
    modeldir := 'testdata/synthetic/simple');

SELECT '=== Simple class: Abbaustelle (with enum) ===' AS example;
SELECT xtf_tid, Name, Status
FROM read_xtf_class('testdata/synthetic/simple/valid.xtf',
    class := 'SO_AGI_Simple_20260605.Topic.Abbaustelle',
    modeldir := 'testdata/synthetic/simple');

SELECT '=== Structures: Betrieb (raw JSON columns) ===' AS example;
SELECT Name, Adresse_json, Kontakte_json
FROM read_xtf_class('testdata/synthetic/structures/valid.xtf',
    class := 'SO_AGI_Structures_20260605.Topic.Betrieb',
    modeldir := 'testdata/synthetic/structures');

SELECT '=== Structures: JSON extraction from structure ===' AS example;
SELECT Name,
    json_extract_string(Adresse_json, '$.Strasse') AS Strasse,
    json_extract_string(Adresse_json, '$.Hausnummer') AS Hausnummer,
    json_extract_string(Adresse_json, '$.PLZ') AS PLZ,
    json_extract_string(Adresse_json, '$.Ort') AS Ort
FROM read_xtf_class('testdata/synthetic/structures/valid.xtf',
    class := 'SO_AGI_Structures_20260605.Topic.Betrieb',
    modeldir := 'testdata/synthetic/structures');

SELECT '=== Structures: unnest BAG OF STRUCTURE ===' AS example;
SELECT Name, KontaktTyp, Telefon, Email
FROM (
    SELECT Name,
        json_extract_string(unnested, '$.Typ') AS KontaktTyp,
        json_extract_string(unnested, '$.Telefon') AS Telefon,
        json_extract_string(unnested, '$.Email') AS Email
    FROM (
        SELECT Name,
            unnest(json_transform(Kontakte_json, '["VARCHAR"]')) AS unnested
        FROM read_xtf_class('testdata/synthetic/structures/valid.xtf',
            class := 'SO_AGI_Structures_20260605.Topic.Betrieb',
            modeldir := 'testdata/synthetic/structures')
    )
);

SELECT '=== Structures: missing/empty detection ===' AS example;
SELECT Name,
    Adresse_json = '' AS adresse_missing,
    Kontakte_json = '[]' AS kontakte_empty
FROM read_xtf_class('testdata/synthetic/structures/valid.xtf',
    class := 'SO_AGI_Structures_20260605.Topic.Betrieb',
    modeldir := 'testdata/synthetic/structures');

SELECT '=== Structures: nested-Parameter explizit (Default: json) ===' AS example;
SELECT Name, Adresse_json
FROM read_xtf_class('testdata/synthetic/structures/valid.xtf',
    class := 'SO_AGI_Structures_20260605.Topic.Betrieb',
    modeldir := 'testdata/synthetic/structures',
    nested := 'json');

SELECT '=== Geometry: class with COORD ===' AS example;
-- v2 typed path: native GEOMETRY type (requires spatial extension for functions)
-- v1 fallback: VARCHAR WKT (cast with ::GEOMETRY)
INSTALL spatial;
LOAD spatial;
SELECT xtf_tid, Name,
       ST_GeometryType(Lage_geom) AS geometry_type,
       ST_AsText(Lage_geom) AS wkt
FROM read_xtf_class('testdata/synthetic/geometries/valid.xtf',
    class := 'SO_AGI_Geometries_20260605.Topic.PunktObjekt',
    modeldir := 'testdata/synthetic/geometries');

SELECT '=== Geometry: class with SURFACE ===' AS example;
SELECT xtf_tid, Name,
       ST_GeometryType(Flaeche_geom) AS geometry_type,
       ST_Area(Flaeche_geom) AS area,
       ST_IsValid(Flaeche_geom) AS is_valid
FROM read_xtf_class('testdata/synthetic/geometries/valid.xtf',
    class := 'SO_AGI_Geometries_20260605.Topic.FlaechenObjekt',
    modeldir := 'testdata/synthetic/geometries');
