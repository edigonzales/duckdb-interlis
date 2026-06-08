# Performance

## Performance Characteristics

### Model Compilation

INTERLIS models are compiled once and cached. The cache key includes:
- Model directory paths (absolute)
- Model file fingerprints (based on file listing)

Subsequent calls with the same model directory reuse the compiled model without recompilation.

The cache uses LRU eviction with a bounded size. Check cache metrics with `DUCKDB_ILI_DEBUG=1`:

```
[ili-debug] ModelCache: hit=5 miss=2 evict=0 size=2
```

### Validation Performance

Validation time depends primarily on:

1. **XTF file size** — larger files take longer
2. **Validation profile** — `FAST` is significantly faster than `FULL`
3. **Model complexity** — models with many constraints and AREA checks are slower

### XTF Reading

Reading an XTF file into tabular form is generally faster than validation because it skips constraint checking.

### Import SQL Generation

SQL generation is fast once the model is compiled. The generated SQL uses `read_xtf_class` and `read_xtf_association` to read directly from XTF files.

## Memory Usage

Memory usage scales with:

- **XTF file content** — all objects are held in memory
- **Number of classes** — each class gets its own table definition
- **Validation messages** — limited by `max_messages`

Approximate guidance for small to medium files (tested up to ~50 MB XTF):

| XTF Size | Typical Memory |
|---|---|
| < 1 MB | < 100 MB |
| 1-10 MB | 100-500 MB |
| 10-50 MB | 500 MB - 2 GB |

Larger files may exceed available heap memory.

## Caching

### Model Cache (Java Side)

Compiled INTERLIS models (`TransferDescription` objects) are cached in a `ConcurrentHashMap` with LRU eviction. The cache is bounded (default maximum size applies).

Cache performance can be monitored via `DUCKDB_ILI_DEBUG=1`.

### Native Library Cache (C Side)

The GraalVM native library is extracted once and cached at `~/.duckdb/extensions/{abi_version}/{platform}/`. The cache key includes version, ABI version, platform, and SHA-256 hash, ensuring that different builds do not conflict.

## Debug Metrics

Set `DUCKDB_ILI_DEBUG=1` to get diagnostic output on stderr:

```bash
DUCKDB_ILI_DEBUG=1 duckdb -unsigned
```

```sql
SELECT count(*) FROM ili_validate('/data/file.xtf');
```

Debug output includes:

```
[ili-debug] Native library cache HIT: /Users/.../.duckdb/extensions/v1/.../libduckdb_ili_native.dylib
[ili-debug] Native library initialized: ABI v1, capabilities=0x0000000000000fff, ext=0.1.0-dev
[ili-debug] Validating: /data/file.xtf (size=2456789 bytes, profile=FULL)
[ili-debug] Validation result payload: 45678 bytes
[ili-debug] Validation parsed: 125 rows (3 errors, 12 warnings, 110 info)
[ili-debug] Validation completed: 2340 ms, 125 messages (file=2456789 bytes)
```

## Optimization Tips

1. **Use local model directories** — avoid network latency from remote repositories
2. **Choose the right validation profile** — use `FAST` or `STRUCTURAL` when `FULL` checks are unnecessary
3. **Limit validation messages** — use `max_messages` to cap output
4. **Pre-compile models** — the first call with a new model directory incurs compilation cost
5. **Split large XTF files** — process multiple small files rather than one large file
6. **Set `ILI_DEFAULT_MODELDIR`** — point to a local directory to avoid `https://models.interlis.ch` on every cold start
