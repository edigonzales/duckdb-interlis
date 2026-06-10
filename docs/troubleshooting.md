# Troubleshooting

## Extension Won't Load

### Symptom: `LOAD interlis` fails with "unsigned" error

```
Error: Extension "interlis" is not signed
```

**Solution:** Start DuckDB with `-unsigned` flag:

```bash
duckdb -unsigned
```

Or, if already running:

```sql
SET allow_unsigned_extensions = true;
LOAD interlis;
```

### Symptom: Extension loads but `ili_extension_version()` returns NULL

The native library may not be found.

**Check:** Run with debug mode:

```bash
DUCKDB_ILI_DEBUG=1 duckdb -unsigned -c "SELECT ili_extension_version();"
```

Look for `[ili-debug]` lines on stderr to see the library resolution path.

**Common causes:**
- Extension binary does not contain an embedded native library (development build without `scripts/embed_native_lib.py`)
- Cache directory cannot be created (permissions issue)
- Disk full (cannot write extracted library)

**Solution for development:**

```bash
export DUCKDB_ILI_NATIVE_LIB=$(pwd)/java/ili-native/build/native/libduckdb_ili_native.dylib
duckdb -unsigned
```

## "Native library not found" / ABI Handshake Failed

### Symptom

```
Error: Native library not found.
Error: ABI handshake failed: ...
```

**Solution:** Clear the native library cache and retry:

```bash
rm -rf ~/.duckdb/extensions/*/interlis*
```

This forces re-extraction of the embedded native library on next load.

## Model Compilation Fails

### Symptom: `MODEL_ERROR` when validating or reading XTF

```
Error: MODEL_ERROR: INTERLIS model compilation failed for modelDir=...
```

**Check the `modeldir` parameter:**
- Is the path correct and accessible?
- If using a URL, is the network reachable?
- Do the `.ili` files exist in the specified directory?

**Solution:** Use explicit local model directories:

```sql
SELECT * FROM ili_validate('/data/file.xtf',
    modeldir := '/absolute/path/to/models/');
```

### Symptom: "Model not found" for a known model

The model directory might contain the files but the model name doesn't match. INTERLIS models declare their name inside the `.ili` file — check the `MODEL` declaration in the file.

## Validation Returns Unexpected Results

### Symptom: Validation passes but data looks wrong

Try increasing the validation level:

```sql
-- Run with FULL profile to catch issues that FAST/STRUCTURAL miss
SELECT * FROM ili_validate('/data/file.xtf', profile := 'FULL');
```

### Symptom: Too many validation messages

Limit output and focus on errors:

```sql
SELECT * FROM ili_validate('/data/file.xtf', max_messages := 100)
WHERE severity = 'ERROR';
```

### Symptom: Validation seems slow

Use a lighter profile for quick feedback:

```sql
SELECT * FROM ili_validate('/data/file.xtf', profile := 'FAST');
```

## XTF Reading Issues

### Symptom: Truncated or corrupt XTF produces partial results

The extension should reject corrupt files with an error, not return partial data. If you see partial results without an error, this is a bug — please report it.

### Symptom: Geometry values are NULL

Geometry attributes are returned as WKT strings in the `*_geom` column. If the column is NULL, the object may not have geometry data for that attribute.

## Memory Issues

### Symptom: "Out of memory" error on large files

The extension materializes all results in memory. For large files:
- Process smaller files
- Use `max_messages` to limit validation output
- Consider splitting large XTF files

### Symptom: DuckDB crashes without error message

This may indicate a memory issue in the native library. Enable debug mode:

```bash
DUCKDB_ILI_DEBUG=1 duckdb -unsigned
```

## Debug Mode

For most issues, start with debug mode to see what's happening:

```bash
DUCKDB_ILI_DEBUG=1 duckdb -unsigned
```

Then run your query. Debug output on stderr shows:

- Library resolution path and cache status
- ABI handshake details
- Validation/file sizes and durations
- Row counts

Debug output never contains sensitive data (file contents, model source, validation results).

## Getting Help

If troubleshooting doesn't resolve the issue:

1. Run with `DUCKDB_ILI_DEBUG=1` and collect the stderr output
2. Note your DuckDB version (`SELECT version();`)
3. Note your extension version (`SELECT ili_extension_version();`)
4. Note your native library version (`SELECT ili_native_version();`)
5. File an issue at [https://github.com/SOAG/duckdb-interlis/issues](https://github.com/SOAG/duckdb-interlis/issues)
