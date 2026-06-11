# SQL Examples

Runnable examples demonstrating every SQL function of the `interlis` extension.

## Recommended Order

The examples are numbered in a logical progression. Run them in order if you're new:

| # | File | What it shows | Needs XTF? |
|---|------|---------------|------------|
| 01 | `01-version.sql` | Verify extension loaded, display version info | No |
| 02 | `02-validate.sql` | Validate XTF files, filter validation messages | Yes |
| 03 | `03-inspect.sql` | Introspect models, topics, classes, attributes, enums | No |
| 04 | `04-read-objects.sql` | Generic XTF reader (JSON output, no schema) | Yes |
| 05 | `05-read-class.sql` | Typed class reader, structures, geometry | Yes |
| 06 | `06-read-structures.sql` | Inspect STRUCTURE definitions in models | No |
| 07 | `07-read-association.sql` | Read associations, JOIN with classes | Yes |
| 08 | `08-import.sql` | Generate DDL/DML import SQL, manual import | Yes |
| 09 | `09-geometry-attributes.sql` | Inspect geometry attribute metadata | No |

## Running Examples

### With dev script (extension auto-loaded):

```bash
scripts/dev-duckdb.sh < sql/examples/01-version.sql
```

### With DuckDB directly:

```bash
duckdb -unsigned -cmd "LOAD '/path/to/interlis.duckdb_extension'" < sql/examples/01-version.sql
```

## Test Data

All examples use synthetic test data in `testdata/synthetic/`:

| Directory | Model | Description |
|---|---|---|
| `simple/` | `SO_AGI_Simple_20260605` | Basic classes with scalars, enums |
| `structures/` | `SO_AGI_Structures_20260605` | Classes with STRUCTURE and BAG OF attributes |
| `geometries/` | `SO_AGI_Geometries_20260605` | Classes with POINT, SURFACE geometry types |
| `associations/` | `SO_AGI_Associations_20260605` | Classes linked via associations |

## Key Patterns

- **Named parameters**: All INTERLIS functions use DuckDB's `name := value` syntax for optional parameters
- **Fully qualified names**: Classes and associations use `Model.Topic.Name` convention
- **`_json` suffix**: Structure/BAG attributes returned as JSON
- **`_geom` suffix**: Geometry attributes returned as `GEOMETRY` (v2 typed path) or WKT `VARCHAR` (v1 fallback)
- **`_ref` suffix**: Association role references (foreign keys to `xtf_tid`)
