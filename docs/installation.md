# Installation

The native extension targets DuckDB 1.5.3. Download the platform-specific
`interlis.duckdb_extension` artifact from the project release or extension
repository, then start DuckDB with unsigned extensions enabled:

```sh
duckdb -unsigned
```

```sql
LOAD '/path/to/interlis.duckdb_extension';
SELECT interlis_version();
```

For a repository install, use the DuckDB product-version path configured by the
publisher and then `LOAD interlis`. For a local source installation, follow
[getting-started.md](getting-started.md), which builds the extension and runs a
complete native SQL example. The native extension has no additional Java,
GraalVM, or separately installed runtime library.

Model source paths passed to SQL functions must be local `.ili` files or local
non-recursive directories. See [model-sources.md](model-sources.md).
