# Known Limitations

## File Size

This extension is designed for **small and medium** INTERLIS/XTF files. It has not been optimized for large files (hundreds of megabytes or more).

## Materialization

All results are **fully materialized in memory** before being returned to DuckDB. There is no streaming or incremental result delivery. The current data flow is:

```
Java StringBuilder
  → Java UTF-8 byte[]
    → unmanaged Native buffer
      → C string copies
        → DuckDB strings
```

This means memory usage is proportional to the total result size, not the batch size.

## Memory Usage

Large XTF files can cause **high memory consumption** because:
- The entire XTF file is parsed in one pass
- All objects are held in memory during reading
- Validation results are fully materialized
- Generated import SQL is built as a single string

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

## Batching and Streaming

Streaming / incremental reading is **not yet implemented**. Phase 14 of the implementation spec addresses batching as a future optimization. See the implementation specification for details.
