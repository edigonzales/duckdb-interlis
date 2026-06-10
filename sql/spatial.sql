-- Spatial integration test for duckdb-interlis geometry support.
-- Prerequisites: duckdb-interlis extension loaded.
-- The spatial extension is only needed for ST_GeometryType, ST_IsValid, etc.
--
-- Geometry columns are returned as WKT (Well-Known Text) VARCHAR strings
-- with the _geom suffix. Cast to GEOMETRY with ::GEOMETRY, or use
-- ST_GeomFromText() if the spatial extension is loaded.
--
-- Note: DuckDB GEOMETRY is a built-in type since v1.5. The spatial extension
-- is required for spatial functions (ST_Area, ST_Intersection, etc.).

INSTALL spatial;
LOAD spatial;

-- -----------------------------------------------------------------------
-- Test 1: POINT (COORD)
-- -----------------------------------------------------------------------
SELECT
    'POINT' AS test_case,
    ST_GeometryType(Lage_geom::GEOMETRY) = 'POINT' AS correct_type,
    ST_IsValid(Lage_geom::GEOMETRY) AS is_valid,
    abs(ST_X(Lage_geom::GEOMETRY) - 2605000.0) < 0.001 AS x_ok,
    abs(ST_Y(Lage_geom::GEOMETRY) - 1203000.0) < 0.001 AS y_ok
FROM read_xtf_class('testdata/synthetic/geometries/valid.xtf',
    class := 'SO_AGI_Geometries_20260605.Topic.PunktObjekt',
    modeldir := 'testdata/synthetic/geometries');

-- -----------------------------------------------------------------------
-- Test 2: MULTIPOINT (MULTICOORD)
-- -----------------------------------------------------------------------
SELECT
    'MULTIPOINT' AS test_case,
    ST_GeometryType(Lagen_geom::GEOMETRY) = 'MULTIPOINT' AS correct_type,
    ST_IsValid(Lagen_geom::GEOMETRY) AS is_valid,
    ST_NumGeometries(Lagen_geom::GEOMETRY) = 3 AS count_ok
FROM read_xtf_class('testdata/synthetic/geometries/valid.xtf',
    class := 'SO_AGI_Geometries_20260605.Topic.MultiPunktObjekt',
    modeldir := 'testdata/synthetic/geometries');

-- -----------------------------------------------------------------------
-- Test 3: LINESTRING (POLYLINE)
-- -----------------------------------------------------------------------
SELECT
    'LINESTRING' AS test_case,
    ST_GeometryType(Verlauf_geom::GEOMETRY) = 'LINESTRING' AS correct_type,
    ST_IsValid(Verlauf_geom::GEOMETRY) AS is_valid,
    ST_NPoints(Verlauf_geom::GEOMETRY) = 3 AS point_count_ok
FROM read_xtf_class('testdata/synthetic/geometries/valid.xtf',
    class := 'SO_AGI_Geometries_20260605.Topic.LinienObjekt',
    modeldir := 'testdata/synthetic/geometries');

-- -----------------------------------------------------------------------
-- Test 4: MULTILINESTRING (MULTIPOLYLINE)
-- -----------------------------------------------------------------------
SELECT
    'MULTILINESTRING' AS test_case,
    ST_GeometryType(Verlaeufe_geom::GEOMETRY) = 'MULTILINESTRING' AS correct_type,
    ST_IsValid(Verlaeufe_geom::GEOMETRY) AS is_valid,
    ST_NumGeometries(Verlaeufe_geom::GEOMETRY) = 2 AS count_ok
FROM read_xtf_class('testdata/synthetic/geometries/valid.xtf',
    class := 'SO_AGI_Geometries_20260605.Topic.MultiLinienObjekt',
    modeldir := 'testdata/synthetic/geometries');

-- -----------------------------------------------------------------------
-- Test 5: POLYGON (SURFACE)
-- -----------------------------------------------------------------------
-- Note: INTERLIS SURFACE in ili2.4 XTF is wrapped in <geom:multisurface>.
-- The encoder therefore produces a MULTIPOLYGON even for a single surface.
SELECT
    'POLYGON' AS test_case,
    ST_GeometryType(Flaeche_geom::GEOMETRY) = 'MULTIPOLYGON' AS correct_type,
    ST_IsValid(Flaeche_geom::GEOMETRY) AS is_valid,
    ST_NumGeometries(Flaeche_geom::GEOMETRY) = 1 AS single_geom
FROM read_xtf_class('testdata/synthetic/geometries/valid.xtf',
    class := 'SO_AGI_Geometries_20260605.Topic.FlaechenObjekt',
    modeldir := 'testdata/synthetic/geometries');

-- -----------------------------------------------------------------------
-- Test 6: MULTIPOLYGON (MULTISURFACE)
-- -----------------------------------------------------------------------
SELECT
    'MULTIPOLYGON' AS test_case,
    ST_GeometryType(Flaechen_geom::GEOMETRY) = 'MULTIPOLYGON' AS correct_type,
    ST_IsValid(Flaechen_geom::GEOMETRY) AS is_valid,
    ST_NumGeometries(Flaechen_geom::GEOMETRY) = 2 AS count_ok
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
    Lage_geom IS NOT NULL AS not_null
FROM read_xtf_class('testdata/synthetic/geometries/valid.xtf',
    class := 'SO_AGI_Geometries_20260605.Topic.PunktObjekt',
    modeldir := 'testdata/synthetic/geometries');

-- -----------------------------------------------------------------------
-- Test 8: Direct geometry without explicit cast
-- -----------------------------------------------------------------------
-- DuckDB can infer GEOMETRY type from WKT literals. With the spatial
-- extension loaded, functions like ST_Area work directly.
SELECT
    'WKT_CAST' AS test_case,
    ST_GeometryType(Lage_geom::GEOMETRY) IS NOT NULL AS cast_ok
FROM read_xtf_class('testdata/synthetic/geometries/valid.xtf',
    class := 'SO_AGI_Geometries_20260605.Topic.PunktObjekt',
    modeldir := 'testdata/synthetic/geometries');
