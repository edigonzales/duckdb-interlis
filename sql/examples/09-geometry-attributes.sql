-- Inspect INTERLIS geometry attribute metadata
-- Run with: duckdb -unsigned -cmd "LOAD 'interlis.duckdb_extension'" < sql/examples/09-geometry-attributes.sql
--   or:    scripts/dev-duckdb.sh < sql/examples/09-geometry-attributes.sql

SELECT '=== List all geometry attributes ===' AS example;
SELECT class_name, attribute_name, geometry_kind, dimension,
       is_mandatory, card_min, card_max
FROM ili_geometry_attributes('testdata/synthetic/geometries');

SELECT '=== Filter by class name ===' AS example;
SELECT attribute_name, geometry_kind, dimension,
       coordinate_domain, is_area_type, is_multi_type
FROM ili_geometry_attributes('testdata/synthetic/geometries',
    class := 'PunktObjekt');

SELECT '=== Filter by model name ===' AS example;
SELECT class_name, attribute_name, geometry_kind, dimension
FROM ili_geometry_attributes('testdata/synthetic/geometries',
    model := 'SO_AGI_Geometries_20260605');

SELECT '=== Find all 3D geometry attributes ===' AS example;
SELECT class_name, attribute_name, geometry_kind, dimension,
       coordinate_domain_fqn
FROM ili_geometry_attributes('testdata/synthetic/geometries')
WHERE dimension = 3;

SELECT '=== Area and Multi-type attributes ===' AS example;
SELECT class_name, attribute_name, geometry_kind,
       is_area_type, is_multi_type, supports_arcs
FROM ili_geometry_attributes('testdata/synthetic/geometries')
WHERE is_area_type = 'true' OR is_multi_type = 'true';

SELECT '=== Mandatory geometry attributes ===' AS example;
SELECT class_name, attribute_name, geometry_kind,
       card_min, card_max
FROM ili_geometry_attributes('testdata/synthetic/geometries')
WHERE is_mandatory = 'true';

SELECT '=== Transport encoding and DuckDB spatial function ===' AS example;
SELECT attribute_name, geometry_kind, transport_encoding, duckdb_spatial_function
FROM ili_geometry_attributes('testdata/synthetic/geometries');

SELECT '=== CRS metadata (requires ILI_GEOMETRY_CRS_MAP) ===' AS example;
SELECT class_name, attribute_name, geometry_kind,
       crs_auth_name, crs_code, srid
FROM ili_geometry_attributes('testdata/synthetic/geometries');
