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

## Coordinate Reference System (CRS)

There is **no automatic CRS detection or inference** from coordinate values. The extension does not embed an SRID into the WKB output, nor does it guess the coordinate system (e.g. EPSG:2056). CRS metadata can be provided explicitly via the `ILI_GEOMETRY_CRS_MAP` environment variable or properties file; otherwise CRS columns remain `NULL`.

## 3D / Z Coordinate Handling

The 3D preservation of Z coordinates is not explicitly guaranteed in the current release. Whether Z values survive the `INTERLIS → WKB` conversion depends on the underlying `iox-ili` library behaviour. If your model declares 3D coordinates, verify the result with `ST_HasZ(ST_GeomFromHEXWKB(...))` until this is explicitly tested and documented.
