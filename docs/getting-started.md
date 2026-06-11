# Getting Started with duckdb-interlis

This guide walks you through installing the extension and running your first INTERLIS queries in DuckDB — no build tools required.

## What is duckdb-interlis?

`duckdb-interlis` is a DuckDB extension that brings the Swiss [INTERLIS](https://www.interlis.ch) geodata standard directly into your SQL workflow. With it you can:

- **Validate** INTERLIS transfer files (`.xtf`) against their data models
- **Read** XTF data as SQL tables with proper column types
- **Inspect** INTERLIS model definitions — classes, attributes, geometry types, enumerations
- **Generate** `CREATE TABLE` / `INSERT` SQL for importing XTF into a DuckDB schema
- **Work with geometries** natively via DuckDB's `GEOMETRY` type and the `spatial` extension

## Quick Install

### Prerequisites

- **DuckDB** 1.5.3 or later
- One of: Linux x86_64, Linux ARM64, macOS ARM64, Windows x86_64

No Java, GraalVM, or other runtimes needed — the extension is self-contained.

### Install from Repository

Start DuckDB with the `-unsigned` flag (the extension is unsigned):

```bash
duckdb -unsigned
```

Then install and load:

```sql
INSTALL interlis FROM 'https://duckdb-ext.interlis.guru';
LOAD interlis;
```

If DuckDB is already running without `-unsigned`:

```sql
SET allow_unsigned_extensions = true;
INSTALL interlis FROM 'https://duckdb-ext.interlis.guru';
LOAD interlis;
```

### Manual Install from Release

Download `interlis.duckdb_extension` for your platform from [GitHub Releases](https://github.com/SOAG/duckdb-interlis/releases):

| Platform | File |
|---|---|
| Linux x86_64 | `interlis-linux-x86_64/interlis.duckdb_extension` |
| Linux ARM64 | `interlis-linux-aarch64/interlis.duckdb_extension` |
| macOS ARM64 | `interlis-osx-aarch64/interlis.duckdb_extension` |
| Windows x86_64 | `interlis-windows-x86_64/interlis.duckdb_extension` |

```bash
duckdb -unsigned
```

```sql
LOAD '/path/to/interlis.duckdb_extension';
```

## Verify Installation

```sql
SELECT ili_extension_version();
-- => 0.1.0-dev

SELECT ili_native_version();
-- => {"nativeVersion":"0.1.0","coreVersion":"0.1.0",...}
```

If both return results, you're ready to go.

## First Steps

### 1. Validate an XTF File

```sql
-- Quick summary: is the file valid?
SELECT json_extract(result, '$.valid') AS valid,
       json_extract(result, '$.errorCount') AS errors
FROM (
    SELECT ili_validate_summary_json('my_data.xtf', '/path/to/models') AS result
);
```

```sql
-- Detailed messages (e.g. only errors):
SELECT severity, message, line, xtf_tid, class_name
FROM ili_validate('my_data.xtf', modeldir := '/path/to/models')
WHERE severity = 'ERROR';
```

### 2. Inspect the Data Model

```sql
-- What models are available?
SELECT name, version FROM ili_models('/path/to/models');

-- What classes exist in a model?
SELECT topic_name, class_name, kind
FROM ili_classes('/path/to/models', model := 'MyModel');

-- What attributes does a class have?
SELECT attr_name, type_name, kind, is_mandatory
FROM ili_attributes('/path/to/models', class := 'Gemeinde');

-- What geometry types are defined?
SELECT class_name, attribute_name, geometry_kind, dimension
FROM ili_geometry_attributes('/path/to/models');
```

### 3. Read XTF Data as a Table

```sql
-- Read a specific class with typed columns:
SELECT xtf_tid, Name, bfs_nr
FROM read_xtf_class('my_data.xtf',
    class := 'MyModel.Topic.Gemeinde',
    modeldir := '/path/to/models');
```

Geometry attributes appear as `_geom` columns (`GEOMETRY` type). Use DuckDB's `spatial` extension for spatial analysis:

```sql
INSTALL spatial;
LOAD spatial;
SELECT Name, ST_Area(Flaeche_geom) AS area_m2
FROM read_xtf_class('my_data.xtf',
    class := 'MyModel.Topic.FlaechenObjekt',
    modeldir := '/path/to/models');
```

### 4. Import into a Schema

```sql
-- Generate CREATE TABLE + INSERT statements:
SELECT sql_statement
FROM ili_generate_import_sql('my_data.xtf',
    schema := 'my_schema',
    modeldir := '/path/to/models');
```

Three import modes available:
- `'create'` (default): `CREATE TABLE IF NOT EXISTS` + `INSERT`
- `'replace'`: `DROP TABLE` + `CREATE TABLE` + `INSERT`
- `'append'`: `INSERT` only (tables must exist)

## Using Remote Model Repositories

Instead of a local directory, you can point `modeldir` to INTERLIS model repositories:

```sql
SELECT * FROM ili_validate('data.xtf',
    modeldir := 'https://models.interlis.ch;https://geo.so.ch/models');
```

Set a default via environment variable to avoid repeating:

```bash
export ILI_DEFAULT_MODELDIR='https://models.interlis.ch;https://geo.so.ch/models'
```

Then omit `modeldir` in queries — it defaults automatically.

## Next Steps

- **[functions.md](functions.md)** — Complete reference for all 15 SQL functions
- **[sql/examples/](../sql/examples/)** — Runnable example scripts covering every function
- **[limitations.md](limitations.md)** — Known limitations and edge cases
- **[validation-profiles.md](validation-profiles.md)** — Validation profiles: full, structural, fast

## Troubleshooting

| Problem | Solution |
|---|---|
| Extension won't load | Start DuckDB with `-unsigned`, or `SET allow_unsigned_extensions = true` |
| "Failed to initialize native library" | Check network access if using remote model repos; extension may need to download on first use |
| Model compilation fails | Verify `modeldir` path exists and contains `.ili` files; remote repos must be reachable |
| Empty results / no rows | Check that class names are fully qualified (`Model.Topic.Class`); try `ili_classes()` to list available names |

More in **[troubleshooting.md](troubleshooting.md)**.
