-- Optional DuckDB Spatial integration for the native Interlis extension.
--
-- The Interlis extension does not need GEOS to emit supported geometries:
-- iox creates standard WKB and DuckDB stores it as GEOMETRY. DuckDB Spatial
-- is loaded here only to provide SQL functions such as ST_IsValid and
-- ST_GeometryType.
--
-- Run from the repository root after building/loading the extension:
--   scripts/dev-duckdb.sh < sql/spatial.sql

INSTALL spatial;
LOAD spatial;

-- POINT: native GEOMETRY can be consumed directly by Spatial.
WITH points AS (
    SELECT _tid, Lage
    FROM xtf_scan(
        'testdata/synthetic/geometries/valid.xtf',
        'SO_AGI_Geometries_20260605.Topic.PunktObjekt',
        ['testdata/synthetic/geometries/SO_AGI_Geometries_20260605.ili'])
    WHERE Lage IS NOT NULL
)
SELECT _tid,
       ST_GeometryType(Lage) AS geometry_type,
       ST_IsValid(Lage) AS is_valid,
       ST_AsText(Lage) AS wkt,
       ST_AsWKB(Lage) IS NOT NULL AS has_wkb
FROM points;

-- POLYLINE: use the returned geometry in a spatial operation.
WITH lines AS (
    SELECT Verlauf
    FROM xtf_scan(
        'testdata/synthetic/geometries/valid.xtf',
        'SO_AGI_Geometries_20260605.Topic.LinienObjekt',
        ['testdata/synthetic/geometries/SO_AGI_Geometries_20260605.ili'])
    WHERE Verlauf IS NOT NULL
)
SELECT ST_GeometryType(Verlauf) AS geometry_type,
       ST_IsValid(Verlauf) AS is_valid,
       ST_Distance(
           Verlauf,
           ST_GeomFromText('POINT (2605000 1203000)')) AS distance_to_origin
FROM lines;

-- MULTISURFACE: DuckDB Spatial validates and measures the WKB output.
WITH surfaces AS (
    SELECT Flaechen
    FROM xtf_scan(
        'testdata/synthetic/geometries/valid.xtf',
        'SO_AGI_Geometries_20260605.Topic.MultiFlaechenObjekt',
        ['testdata/synthetic/geometries/SO_AGI_Geometries_20260605.ili'])
    WHERE Flaechen IS NOT NULL
)
SELECT ST_GeometryType(Flaechen) AS geometry_type,
       ST_IsValid(Flaechen) AS is_valid,
       ST_NumGeometries(Flaechen) AS polygon_count,
       ST_Area(Flaechen) AS area
FROM surfaces;

-- 3D COORD: the Z ordinate is retained by the native WKB projection.
WITH points3d AS (
    SELECT Lage3d
    FROM xtf_scan(
        'testdata/synthetic/geometries/valid-3d.xtf',
        'SO_AGI_Geometries_20260605.Topic.Punkt3dObjekt',
        ['testdata/synthetic/geometries/SO_AGI_Geometries_20260605.ili'])
    WHERE Lage3d IS NOT NULL
)
SELECT ST_GeometryType(Lage3d) AS geometry_type,
       ST_Z(Lage3d) AS z_value,
       ST_IsValid(Lage3d) AS is_valid,
       ST_AsText(Lage3d) AS wkt,
       ST_AsWKB(Lage3d) IS NOT NULL AS has_wkb
FROM points3d;

-- ARC: native arcs are stroked to a valid LINESTRING for DuckDB Spatial.
WITH arcs AS (
    SELECT Verlauf
    FROM xtf_scan(
        'testdata/synthetic/geometries/valid-arcs.xtf',
        'SO_AGI_Geometries_20260605.Topic.LinienObjektArc',
        ['testdata/synthetic/geometries/SO_AGI_Geometries_20260605.ili'])
    WHERE Verlauf IS NOT NULL
)
SELECT ST_GeometryType(Verlauf) AS geometry_type,
       ST_NPoints(Verlauf) AS point_count,
       ST_IsValid(Verlauf) AS is_valid,
       ST_AsText(Verlauf) AS wkt,
       ST_AsWKB(Verlauf) IS NOT NULL AS has_wkb
FROM arcs;
