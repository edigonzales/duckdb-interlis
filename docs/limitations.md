# Known Limitations

> **Current state (pre-Milestone B):** The extension materializes complete result sets in memory before returning them to DuckDB. Java calls are globally serialized through a single mutex. Streaming / incremental delivery is planned for Milestone B.

## Current Materialization Behavior

| Operation | Materialization |
|---|---|
| XTF reading (`read_xtf_class`, `read_xtf_association`, `read_xtf_objects`) | Full object graph materialized |
| Validation (`ili_validate`) | Full validation result (all messages + counters) |
| Model metadata (`ili_models`, `ili_topics`, `ili_classes`) | Full TransferDescription result |
| Import SQL generation (`ili_generate_import_sql`) | Full DDL/DML as a single string |
| Java calls | Globally serialized via `g_java_lock` |

## Suitability

The extension is suitable for **small and medium** INTERLIS/XTF files in production. For large files, streaming support will be added in Milestone B.

## Parallelism

Concurrent calls are **intentionally serialized** through a single Java mutex. This is because the underlying INTERLIS libraries (ili2c, ilivalidator) are not guaranteed to be thread-safe. Multiple DuckDB connections can be active, but only one Java call executes at a time.

## INTERLIS Construct Coverage

Not all INTERLIS constructs are mapped relationally. Currently supported:

- Classes with scalar attributes
- STRUCTURE attributes (as JSON)
- BAG OF structures (as JSON arrays)
- Geometry attributes (as WKB hex strings)
- Role references (as TID strings)
- Associations with role references

Not yet mapped:
- VIEW definitions
- Extended classes (with base class flattening)
- Complex constraint expressions (evaluated but not stored)
- Transfer metadata (HEADER section)

## Network Access

Remote model repositories (URLs starting with `http://` or `https://`) require network access. Model compilation will fail if the repository is unreachable. Use local model directories for offline operation:

```sql
SELECT * FROM ili_validate('/data/file.xtf', modeldir := '/local/models/');
```

## Integer Precision

INTERLIS numeric types with decimal places are mapped to DuckDB `DOUBLE`, which may lose precision for very large or very precise decimal values. Types without decimal places are mapped to `BIGINT`.

## Identifier Quoting

Table and column names use the `topic__class` pattern with lowercase sanitization. Very long names may be truncated or cause collisions. Always verify generated SQL before executing in production.

## Geometry Format and Spatial Integration

Geometry attributes are returned as **uppercase hexadecimal WKB strings** (HEX-WKB) in `VARCHAR` columns with the `_wkb` suffix. This is not binary WKB (BLOB). To convert these columns into DuckDB `GEOMETRY` values, you **must** install and load the `spatial` extension and use `ST_GeomFromHEXWKB(...)`:

```sql
INSTALL spatial;
LOAD spatial;
SELECT ST_GeomFromHEXWKB(Lage_wkb) AS geom FROM read_xtf_class(...);
```

Using `ST_GeomFromWKB(...)` on a `_wkb` column will fail because `ST_GeomFromWKB` expects binary data, not a hex string.

## Supported Geometry Types

The following OGC geometry types are supported via INTERLIS mapping:

| INTERLIS Type | OGC Equivalent | Status |
|---|---|---|
| COORD | POINT | ✅ Supported |
| MULTICOORD | MULTIPOINT | ✅ Supported |
| POLYLINE | LINESTRING | ✅ Supported |
| MULTIPOLYLINE | MULTILINESTRING | ✅ Supported |
| SURFACE | POLYGON | ✅ Supported (exterior + interior rings) |
| MULTISURFACE | MULTIPOLYGON | ✅ Supported |
| AREA | POLYGON | ✅ Converted via `surface2hexwkb` |
| MULTIAREA | MULTIPOLYGON | ✅ Converted via `multisurface2hexwkb` |

## 3D / Z Coordinate Handling

**Verified behaviour:** The underlying `iox-ili` library (JTS 1.14.0 `Iox2jtsext.coord2hexwkb`) produces **2D WKB** even when the INTERLIS model declares 3D coordinates (`C3`/`Z`). By default (`preserveZ=true`), the encoder throws `UnsupportedGeometryException` when a 3D attribute results in 2D WKB output, so the error is never silent. Set `preserveZ=false` to allow 2D output for 3D-declared attributes.

```sql
-- Will return a JSON error marker in the _wkb cell for 3D geometry:
-- {"_geometry_error":"Geometry conversion failed: ... 3D coordinate declared but WKB output is 2D ..."}
```

Future work may use JTS `WKBWriter(3)` for true 3D support.

## ARC (Circular Arc) Handling

INTERLIS `POLYLINE` with circular arcs (`ARC` segments) is dispatched to `Iox2jtsext.polyline2hexwkb` with `strokeTolerance=0`. The exact XTF XML structure required by `iox-ili` for ARC linearization is non-trivial to produce manually; automatic tests for ARC are therefore disabled. The encoder accepts both:
- **Successful linearization** by `iox-ili` (produces multiple vertices), or
- **Failure** with an explicit exception (not silent ignoring).

## MANDATORY / NOT NULL Constraints

The DuckDB 1.5.3 C API does not provide `duckdb_bind_set_column_not_null` or equivalent for table function result columns. Therefore, `MANDATORY` INTERLIS attributes produce `VARCHAR` columns that are **nullable** in DuckDB, even though the model declares them mandatory. Applications should enforce constraints downstream if needed.

Future DuckDB versions may add this capability.

## Coordinate Reference System (CRS)

There is **no automatic CRS detection or inference** from coordinate values. The extension does not embed an SRID into the WKB output, nor does it guess the coordinate system (e.g. EPSG:2056). CRS metadata can be provided explicitly via:
- Environment variable `ILI_GEOMETRY_CRS_MAP='DomainFqn=AUTH:CODE'`
- Or file via `ILI_GEOMETRY_CRS_FILE=/path/to/crs.properties`

Otherwise CRS columns in `ili_geometry_attributes(...)` remain `NULL`.

## Generic Reader `geom_json`

For `read_xtf_objects(...)` the `geom_json` column now contains a JSON object per row mapping geometry attribute names to their tag types (e.g. `{"Lage":{"tag":"geom:coord"}}`). This is richer than the previous `{"_has_geometry":true}` flag but does not contain the full WKB (which is only available in `read_xtf_class(...)` mode due to schema knowledge requirements).
