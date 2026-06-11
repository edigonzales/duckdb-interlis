# Changelog

All notable changes to the duckdb-interlis extension.

## [0.1.0-dev] — Unreleased

### Added
- **`ili_geometry_attributes()` SQL table function** — lists geometry attribute metadata (21 columns: geometry kind, dimension, coordinate domain, CRS, cardinality, arcs support, etc.)
- **Native GEOMETRY column support (v2 typed path)** — `read_xtf_class` returns native DuckDB `GEOMETRY` columns via `ILI_CAP_TYPED_CLASS_SCAN`; hex-WKB wire protocol; v1 VARCHAR WKT fallback when capability absent
- **CRS mapping** — `ILI_GEOMETRY_CRS_MAP` and `ILI_GEOMETRY_CRS_FILE` env vars for explicit coordinate reference system configuration; used by `ili_generate_import_sql` (generates `GEOMETRY('EPSG:xxxx')` columns) and `ili_geometry_attributes` (populates CRS columns)
- **Validation profiles** — `profile` parameter on `ili_validate`: `full` (default), `structural`, `fast`
- **`max_messages` parameter** on `ili_validate` — limits detail rows without affecting validity status
- **`ili_generate_import_sql` modes** — `create` (default, CREATE IF NOT EXISTS), `replace` (DROP + CREATE), `append` (INSERT only)
- **`topic__class` table naming** — avoids collisions when same class name appears in different topics

### Changed
- **BREAKING**: `ili_import_xtf` renamed to `ili_generate_import_sql`
- **BREAKING**: Geometry output changed from hex-WKB to WKT (VARCHAR); later augmented with native GEOMETRY type via v2 typed path
- **BREAKING**: Table names use `topic__class` convention (was just `class`)
- `geom_json` in `read_xtf_objects` now returns structured JSON with geometry tag types per attribute (was simple presence flag)
- CRS resolver wired in `IliModelService.getGeometryAttributes()` (was `NoopGeometryCrsResolver` — CRS columns were always NULL)

### Geometry Support
- All 8 INTERLIS geometry types supported: COORD/POINT, MULTICOORD/MULTIPOINT, POLYLINE/LINESTRING, MULTIPOLYLINE/MULTILINESTRING, SURFACE/POLYGON, MULTISURFACE/MULTIPOLYGON, AREA/POLYGON, MULTIAREA/MULTIPOLYGON
- 3D/Z coordinate detection with `preserveZ` enforcement
- ARC circular arc linearization via iox-ili with configurable stroke tolerance
- Custom line forms, clipped polylines, and line attributes explicitly rejected
- Exterior + interior ring support for SURFACE/AREA types

### Internal
- Java cache: thread-safe `ConcurrentHashMap`, file fingerprint in cache keys, no indefinite caching of failed compilations
- Logger: thread-safe, no permanent System.err redirection
- Native library: atomic extraction with hash verification, symlink rejection, parallel-safe
- Error handling: NativeError JSON protocol with proper status codes, no more `"ERROR:"` prefixes in payloads
- ABI handshake with version and capability negotiation
- Thread-safe initialization with exactly-once guarantee
