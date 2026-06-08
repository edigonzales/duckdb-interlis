# Error Handling

This document describes how errors from the INTERLIS validation and reading pipeline are surfaced to DuckDB users.

## Error Architecture

Errors flow through three layers:

```
Java (ilivalidator / ili2c)
  → NativeError (structured JSON)
    → C extension (status codes)
      → DuckDB (error messages)
```

## Status Codes

Every native call returns a status code and, on failure, a structured JSON error payload:

| Code | Name | Meaning |
|---|---|---|
| 0 | `OK` | Success |
| 1 | `INVALID_ARGUMENT` | Missing or invalid parameter (e.g., file not found, unsupported mapping mode) |
| 2 | `IO_ERROR` | File read/write error |
| 3 | `MODEL_ERROR` | INTERLIS model compilation failure |
| 4 | `PARSE_ERROR` | XTF file parsing error (corrupt or malformed file) |
| 5 | `VALIDATION_ERROR` | Validation logic error (not validation messages from data) |
| 6 | `UNSUPPORTED` | Requested operation not supported |
| 100 | `INTERNAL_ERROR` | Unexpected internal error (Java exception, native library crash) |

## Error Visibility in DuckDB

### Table Functions

When a table function (e.g., `ili_validate`, `read_xtf_class`) encounters an error, DuckDB reports it immediately:

```sql
SELECT * FROM ili_validate('/nonexistent.xtf');
-- Error: near "SELECT": IO_ERROR: File not found: /nonexistent.xtf
```

The function does not return a partial result set — it either succeeds completely or reports an error.

### Scalar Functions

Scalar functions return `NULL` on error and set a DuckDB error:

```sql
SELECT ili_native_version();
-- If native library is not loaded: NULL + error message
```

## Common Error Scenarios

### "Native library not found"

```
Error: Native library not found. Set DUCKDB_ILI_NATIVE_LIB or run scripts/build-native.sh
```

The extension cannot locate the GraalVM native library. Verify:
- The extension binary contains an embedded native library (production builds)
- Or set `DUCKDB_ILI_NATIVE_LIB` to the library path (development)

### "Model not found"

```
Error: MODEL_ERROR: INTERLIS model compilation failed: ...
```

The specified INTERLIS model cannot be found or compiled. Check:
- The `modeldir` parameter points to a valid repository or directory
- The model `.ili` files exist in the specified location
- Model dependencies are resolvable

### "XTF read error"

```
Error: PARSE_ERROR: XTF read error for /path/file.xtf: ...
```

The XTF file is corrupt or malformed. Common causes:
- Truncated file (incomplete write/copy)
- Invalid XML/INTERLIS structure
- Encoding issues

### "ABI handshake failed"

```
Error: ABI handshake failed: ...
```

The GraalVM native library version is incompatible with the extension. This can happen if:
- Extension and native library were built from different versions
- The native library was manually replaced with an incompatible version

Delete the cache and re-extract:
```bash
rm -rf ~/.duckdb/extensions/*/interlis*
```

## Debug Mode

Set `DUCKDB_ILI_DEBUG=1` to see detailed diagnostics on stderr, including:

- Library path resolution
- ABI handshake details
- Cache hit/miss status
- Payload sizes
- Compilation and validation durations

```bash
DUCKDB_ILI_DEBUG=1 duckdb -unsigned
```

The debug output never contains sensitive data (file contents, model source, validation results).

## Design Principles

1. **No silent empty results** — technical errors always produce DuckDB errors, never empty result sets
2. **No partial results** — if an error occurs, no partial data is returned as success
3. **Original cause visible** — Java exceptions are propagated with their original message, not generic "something went wrong" text
4. **No `ERROR:` prefixes in success payloads** — the string `ERROR:` in a validation result is always a genuine data issue, never an infrastructure failure
5. **Error payloads are never discarded** — even in error paths, structured error information is captured and reported
