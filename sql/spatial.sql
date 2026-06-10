-- Spatial integration test for duckdb-interlis geometry support.
-- Prerequisites: duckdb-interlis extension and spatial extension loaded.

INSTALL spatial;
LOAD spatial;

-- Make sure the interlis extension is loaded (caller responsibility).
-- All geometry columns are uppercase hexadecimal WKB strings (HEX-WKB) in VARCHAR.
-- ST_GeomFromWKB does NOT work on these columns; ST_GeomFromHEXWKB is required.

-- -----------------------------------------------------------------------
-- Test 1: POINT (COORD)
-- -----------------------------------------------------------------------
SELECT
    'POINT' AS test_case,
    ST_GeometryType(ST_GeomFromHEXWKB(Lage_wkb)) = 'POINT' AS correct_type,
    ST_IsValid(ST_GeomFromHEXWKB(Lage_wkb)) AS is_valid,
    abs(ST_X(ST_GeomFromHEXWKB(Lage_wkb)) - 2605000.0) < 0.001 AS x_ok,
    abs(ST_Y(ST_GeomFromHEXWKB(Lage_wkb)) - 1203000.0) < 0.001 AS y_ok
FROM read_xtf_class('testdata/synthetic/geometries/valid.xtf',
    class := 'SO_AGI_Geometries_20260605.Topic.PunktObjekt',
    modeldir := 'testdata/synthetic/geometries');

-- -----------------------------------------------------------------------
-- Test 2: MULTIPOINT (MULTICOORD)
-- -----------------------------------------------------------------------
SELECT
    'MULTIPOINT' AS test_case,
    ST_GeometryType(ST_GeomFromHEXWKB(Lagen_wkb)) = 'MULTIPOINT' AS correct_type,
    ST_IsValid(ST_GeomFromHEXWKB(Lagen_wkb)) AS is_valid,
    ST_NumGeometries(ST_GeomFromHEXWKB(Lagen_wkb)) = 3 AS count_ok
FROM read_xtf_class('testdata/synthetic/geometries/valid.xtf',
    class := 'SO_AGI_Geometries_20260605.Topic.MultiPunktObjekt',
    modeldir := 'testdata/synthetic/geometries');

-- -----------------------------------------------------------------------
-- Test 3: LINESTRING (POLYLINE)
-- -----------------------------------------------------------------------
SELECT
    'LINESTRING' AS test_case,
    ST_GeometryType(ST_GeomFromHEXWKB(Verlauf_wkb)) = 'LINESTRING' AS correct_type,
    ST_IsValid(ST_GeomFromHEXWKB(Verlauf_wkb)) AS is_valid,
    ST_NPoints(ST_GeomFromHEXWKB(Verlauf_wkb)) = 3 AS point_count_ok
FROM read_xtf_class('testdata/synthetic/geometries/valid.xtf',
    class := 'SO_AGI_Geometries_20260605.Topic.LinienObjekt',
    modeldir := 'testdata/synthetic/geometries');

-- -----------------------------------------------------------------------
-- Test 4: MULTILINESTRING (MULTIPOLYLINE)
-- -----------------------------------------------------------------------
SELECT
    'MULTILINESTRING' AS test_case,
    ST_GeometryType(ST_GeomFromHEXWKB(Verlaeufe_wkb)) = 'MULTILINESTRING' AS correct_type,
    ST_IsValid(ST_GeomFromHEXWKB(Verlaeufe_wkb)) AS is_valid,
    ST_NumGeometries(ST_GeomFromHEXWKB(Verlaeufe_wkb)) = 2 AS count_ok
FROM read_xtf_class('testdata/synthetic/geometries/valid.xtf',
    class := 'SO_AGI_Geometries_20260605.Topic.MultiLinienObjekt',
    modeldir := 'testdata/synthetic/geometries');

-- -----------------------------------------------------------------------
-- Test 5: POLYGON (SURFACE)
-- -----------------------------------------------------------------------
-- Note: INTERLIS SURFACE in ili2.4 XTF is wrapped in <geom:multisurface>.
-- Iox2jtsext.surface2hexwkb() therefore produces a MULTIPOLYGON even for a single surface.
SELECT
    'POLYGON' AS test_case,
    ST_GeometryType(ST_GeomFromHEXWKB(Flaeche_wkb)) = 'MULTIPOLYGON' AS correct_type,
    ST_IsValid(ST_GeomFromHEXWKB(Flaeche_wkb)) AS is_valid,
    ST_NumGeometries(ST_GeomFromHEXWKB(Flaeche_wkb)) = 1 AS single_geom
FROM read_xtf_class('testdata/synthetic/geometries/valid.xtf',
    class := 'SO_AGI_Geometries_20260605.Topic.FlaechenObjekt',
    modeldir := 'testdata/synthetic/geometries');

-- -----------------------------------------------------------------------
-- Test 6: MULTIPOLYGON (MULTISURFACE)
-- -----------------------------------------------------------------------
SELECT
    'MULTIPOLYGON' AS test_case,
    ST_GeometryType(ST_GeomFromHEXWKB(Flaechen_wkb)) = 'MULTIPOLYGON' AS correct_type,
    ST_IsValid(ST_GeomFromHEXWKB(Flaechen_wkb)) AS is_valid,
    ST_NumGeometries(ST_GeomFromHEXWKB(Flaechen_wkb)) = 2 AS count_ok
FROM read_xtf_class('testdata/synthetic/geometries/valid.xtf',
    class := 'SO_AGI_Geometries_20260605.Topic.MultiFlaechenObjekt',
    modeldir := 'testdata/synthetic/geometries');

-- -----------------------------------------------------------------------
-- Test 7: NULL geometry handling
-- -----------------------------------------------------------------------
-- We don't yet have a fixture with a genuinely missing geometry,
-- but we can at least assert that existing rows are NOT NULL.
SELECT
    'NOT_NULL' AS test_case,
    Lage_wkb IS NOT NULL AS not_null
FROM read_xtf_class('testdata/synthetic/geometries/valid.xtf',
    class := 'SO_AGI_Geometries_20260605.Topic.PunktObjekt',
    modeldir := 'testdata/synthetic/geometries');
