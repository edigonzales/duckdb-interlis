# Troubleshooting

## Extension will not load

Use DuckDB 1.5.3 and start it with `-unsigned` for the current unsigned
artifact. Load the absolute extension path and confirm it exists:

```sql
LOAD '/absolute/path/interlis.duckdb_extension';
SELECT interlis_version();
```

## Model compilation fails

Check that every entry in `model_sources` is a regular local `.ili` file or a
directory containing `.ili` files. Directories are not recursive, empty
directories fail, and remote URLs are intentionally rejected. Prefer an
explicit file list when a directory contains unrelated models.

## XTF scan fails

Use the exact fully qualified class name returned by `ili_classes`. Verify that
the XTF file and model sources describe the same model. For geometry conversion
diagnostics, temporarily use `geometry_errors := 'null'` to keep the row and
inspect `_unsupported_json`.

## XTF rewrite fails

`xtf_set` requires a distinct output path, a matching TID, and exactly one
primitive target. It rejects roles, geometries, collections, transient values,
wildcards, and ambiguous matches. Use `expected` for conflict detection and
`overwrite := true` only when replacing an existing output is intentional.
