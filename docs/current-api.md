# DuckDB-Interlis Current API Reference

> **Phase 0 Baseline Document.** Generated 2026-06-11.
> Documents all SQL functions as they currently exist in version 0.1.0-dev.
> For each function: signature, parameters, return columns, NULL behaviour, error behaviour, known limitations.

## Overview

| # | Function | Kind | Returns |
|---|----------|------|---------|
| 1 | `ili_extension_version()` | Scalar | VARCHAR |
| 2 | `ili_native_version()` | Scalar | VARCHAR (JSON) |
| 3 | `validate_xtf_summary_json(path, model_sources)` | Scalar | VARCHAR (JSON) |
| 4 | `validate_xtf(path, model_sources =>, profile =>, max_messages =>)` | Table | 13 columns |
| 5 | `ili_models(model, model_sources =>)` | Table | 5 columns |
| 6 | `ili_topics(model, model_sources =>)` | Table | 3 columns |
| 7 | `ili_classes(model, model_sources =>, class =>)` | Table | 7 columns |
| 8 | `ili_attributes(model, model_sources =>, class =>)` | Table | 9 columns |
| 9 | `ili_enumerations(model, model_sources =>)` | Table | 5 columns |
| 10 | `ili_geometry_attributes(model, model_sources =>, class =>)` | Table | 21 columns |
| 11 | `read_xtf_objects(path, model_sources =>, models =>)` | Table | 11 columns |
| 12 | `read_xtf_class(path, class =>, model_sources =>, nested =>)` | Table | Dynamic |
| 13 | `read_xtf_structures(class =>, model_sources =>)` | Table | 12 columns |
| 14 | `read_xtf_association(path, association =>, model_sources =>)` | Table | Dynamic |
| 15 | `ili_generate_import_sql(path, schema =>, model_sources =>, mapping =>, mode =>)` | Table | 1 column |

---

## Scalar Functions

### `ili_extension_version()`

**Signature:** `ili_extension_version() → VARCHAR`

**Description:** Returns the C extension version string, statically compiled. Does not call the native library.

**Parameters:** None.

**NULL behaviour:** Never returns NULL.

**Error behaviour:** Cannot fail (static string).

**Known limitations:** None.

---

### `ili_native_version()`

**Signature:** `ili_native_version() → VARCHAR`

**Description:** Returns JSON with version information from the GraalVM native library.

**JSON structure:**
```json
{
  "native_version": "0.1.0",
  "core_version": "0.1.0",
  "graalvm_version": "GraalVM 25.0.3",
  "platform": "macos-aarch64",
  "native_lib": "libduckdb_ili_native.dylib"
}
```

**Parameters:** None.

**NULL behaviour:** Returns NULL if the native library cannot be initialised or the native call fails.

**Error behaviour:** On native library init failure, calls `duckdb_scalar_function_set_error` with `g_error_buf` content and returns NULL. On native call failure, the NativeError JSON payload is extracted and displayed as the DuckDB error message. Phase 2 fix: error payloads are no longer discarded.

**Known limitations:**
- Error payload from the native library is lost on failure (Phase 1 fix).
- Platform string is hardcoded in Java (`macos-aarch64`), not detected at runtime.

---

### `validate_xtf_summary_json(path, model_sources)`

**Signature:** `validate_xtf_summary_json(path VARCHAR, model_sources VARCHAR) → VARCHAR`

**Description:** Validates an XTF file and returns a JSON summary with `valid` (boolean), `errorCount`, `warningCount`, `infoCount`, and optional `messages` array.

**Parameters:**

| # | Name | Kind | Required | Description |
|---|------|------|----------|-------------|
| 1 | `path` | positional | Yes | Path to XTF file |
| 2 | `model_sources` | positional | No | Comma- or semicolon-separated `.ili` files, directories, or repository URLs; a direct file's parent resolves `IMPORTS` |

**NULL behaviour:**
- Returns NULL if `path` is NULL.
- `model_sources` NULL is treated as empty string.

**Error behaviour:**
- If `path` is NULL → `duckdb_scalar_function_set_error("Path must not be NULL")`, returns NULL.
- If native library init fails → `duckdb_scalar_function_set_error(g_error_buf)`, returns NULL.
- If native call fails → DuckDB error with extracted error message from NativeError JSON payload (Phase 2 fix).

**Known limitations:**
- JSON request is built with manual `snprintf` — paths containing `"`, `\`, or Unicode may produce invalid JSON (Phase 3 fix).
- Fixed 8192-byte request buffer — long paths are silently truncated (Phase 3 fix).
- CONSTRAINT and AREA validation are both **disabled** by default, making the function name misleading (Phase 6 fix).
- No `profile` or `maxMessages` parameter exposed at SQL level (Phase 6 fix).

---

## Table Functions

### `validate_xtf(path, model_sources =>)`

**Signature:** `validate_xtf(path VARCHAR, model_sources => VARCHAR, profile => VARCHAR, max_messages => INTEGER) → TABLE(...)`

**Description:** Validates an XTF file and returns one row per validation message.

**Parameters:**

| # | Name | Kind | Required | Description |
|---|------|------|----------|-------------|
| 1 | `path` | positional | Yes | Path to XTF file |
| 2 | `model_sources` | named | No | Comma- or semicolon-separated `.ili` files, directories, or repository URLs; a direct file's parent resolves `IMPORTS` |
| 3 | `profile` | named | No | Validation profile: `full` (default), `structural`, or `fast` |
| 4 | `max_messages` | named | No | Maximum detail rows returned. Use -1 or omit for unlimited. Does NOT affect validity. |

**Return columns:**

| Column | Type | Nullable | Description |
|--------|------|----------|-------------|
| `severity` | VARCHAR | No | ERROR, WARNING, or INFO |
| `code` | VARCHAR | No | Always empty string currently |
| `message` | VARCHAR | No | Validation message text |
| `filename` | VARCHAR | No | Source file |
| `line` | INTEGER | Yes | Line number (NULL if unknown) |
| `column` | INTEGER | Yes | Always NULL currently |
| `xtf_tid` | VARCHAR | Yes | Transfer ID (NULL if unknown) |
| `xtf_bid` | VARCHAR | Yes | Basket ID (NULL if unknown) |
| `model` | VARCHAR | Yes | Model name (NULL if unknown) |
| `topic` | VARCHAR | Yes | Topic name (NULL if unknown) |
| `class_name` | VARCHAR | Yes | Class name (NULL if unknown) |
| `attribute_name` | VARCHAR | Yes | Attribute name (NULL if unknown) |
| `raw` | VARCHAR | No | Raw CSV line |

**NULL behaviour:** Missing integer values (e.g., `line`, `column`) are SQL `NULL`. Missing string values (e.g., `xtf_tid`, `xtf_bid`) are SQL `NULL`. Empty strings remain empty strings. `0` is a genuine value, distinct from `NULL`.

**Error behaviour:**
- Native library init failure → `duckdb_init_set_error(g_error_buf)`, empty result.
- Missing `path` → `duckdb_init_set_error("Missing path path")`, empty result.
- Native call failure → DuckDB error with extracted NativeError message (Phase 2 fix).
- File not found → returned as an ERROR-level validation message, not as a DuckDB error.

**Known limitations:**
- CONSTRAINT + AREA validation are both **disabled** in all profiles (Phase 6 fix).
- Validation log CSV is parsed with `split(",", -1)` which breaks on quoted commas (Phase 6 fix).
- Entries with `code` set are discarded by the parser — only the first 2 fields per CSV line are used as `fields[0]` and `fields[1]`, the actual ilivalidator `Type` field is in column 2.
- Temp CSV file is created for each validation call — potential for name collision under parallel use.

---

### `ili_models(model, model_sources =>)`

**Signature:** `ili_models(model VARCHAR, model_sources => VARCHAR) → TABLE(...)`

**Description:** Returns metadata for one INTERLIS model. `NULL` as model lists all models found in local sources.

**Parameters:**

| # | Name | Kind | Required | Description |
|---|------|------|----------|-------------|
| 1 | `model` | positional | Yes | Exact model name; `NULL` means no model filter |
| 2 | `model_sources` | named | No | Comma- or semicolon-separated `.ili` files, directories, or repository URLs |

**Return columns:**

| Column | Type |
|--------|------|
| `name` | VARCHAR |
| `version` | VARCHAR |
| `issuer` | VARCHAR |
| `language` | VARCHAR |
| `ili_version` | VARCHAR |

**NULL behaviour:** Empty strings for missing metadata fields. NULL and empty string are not distinguished.

`model_sources` accepts comma- or semicolon-separated local directories, direct
`.ili` files, and HTTP(S) model repositories. If omitted, the
`ILI_DEFAULT_MODELDIR` environment variable or `https://models.interlis.ch` is
used. A `NULL` model selects all models available from local sources.

**Error behaviour:**
- Native library init failure → `duckdb_init_set_error(g_error_buf)`.
- Native call failure → DuckDB error with extracted NativeError message (Phase 2 fix).
- Model compilation failure → throws RuntimeException, caught by NativeEntryPoints, converted to NativeError with status MODEL_ERROR (Phase 2 fix).
**Known limitations:**
- Model compilation failures now throw RuntimeException instead of returning empty result (Phase 2 fixed).
- Error string from Java now uses NativeError JSON with proper status codes — no more `"ERROR:"` prefixes (Phase 2 fixed).
- Thread-safe shared Java model cache with bounded LRU eviction.
- Cache keys include normalized source lists, selected model names, and local `.ili` file fingerprints.

---

### `ili_topics(model, model_sources =>)`

**Signature:** `ili_topics(model VARCHAR, model_sources => VARCHAR) → TABLE(...)`

**Description:** Lists topics within INTERLIS models.

**Return columns:**

| Column | Type |
|--------|------|
| `model_name` | VARCHAR |
| `topic_name` | VARCHAR |
| `kind` | VARCHAR |

**Parameters, NULL behaviour, error behaviour, known limitations:** Same as `ili_models`; `NULL` model lists topics from local sources.

---

### `ili_classes(model, model_sources =>, class =>)`

**Signature:** `ili_classes(model VARCHAR, model_sources => VARCHAR, class => VARCHAR) → TABLE(...)`

**Description:** Lists classes within INTERLIS models/topics.

**Return columns:**

| Column | Type |
|--------|------|
| `model_name` | VARCHAR |
| `topic_name` | VARCHAR |
| `class_name` | VARCHAR |
| `kind` | VARCHAR |
| `is_abstract` | VARCHAR |
| `is_extended` | VARCHAR |
| `base_class` | VARCHAR |

**Parameters, NULL behaviour, error behaviour, known limitations:** Same as `ili_models`; `class` optionally filters by short, topic-qualified, or fully qualified class name.

---

### `ili_attributes(model, model_sources =>, class =>)`

**Signature:** `ili_attributes(model VARCHAR, model_sources => VARCHAR, class => VARCHAR) → TABLE(...)`

**Description:** Lists attributes of classes in INTERLIS models.

**Return columns:**

| Column | Type |
|--------|------|
| `model_name` | VARCHAR |
| `topic_name` | VARCHAR |
| `class_name` | VARCHAR |
| `attr_name` | VARCHAR |
| `type_name` | VARCHAR |
| `kind` | VARCHAR |
| `is_mandatory` | VARCHAR |
| `card_min` | VARCHAR |
| `card_max` | VARCHAR |

**Parameters, NULL behaviour, error behaviour, known limitations:** Same as `ili_models`; `class` optionally filters by short, topic-qualified, or fully qualified class name.

---

### `ili_enumerations(model, model_sources =>)`

**Signature:** `ili_enumerations(model VARCHAR, model_sources => VARCHAR) → TABLE(...)`

**Description:** Lists enumeration values defined in INTERLIS models.

**Return columns:**

| Column | Type |
|--------|------|
| `model_name` | VARCHAR |
| `topic_name` | VARCHAR |
| `enum_name` | VARCHAR |
| `element` | VARCHAR |
| `element_line` | VARCHAR |

**Parameters, NULL behaviour, error behaviour, known limitations:** Same as `ili_models`; `NULL` model lists enumeration values from local sources.

---

### `ili_geometry_attributes(model, model_sources =>, class =>)`

**Signature:** `ili_geometry_attributes(model VARCHAR, model_sources => VARCHAR, class => VARCHAR) → TABLE(...)`

**Description:** Lists geometry attributes found in INTERLIS models. Returns metadata including geometry kind, dimension, coordinate domain, CRS, cardinality, and encoding info. No XTF path needed — pure model introspection.

**Parameters:**

| # | Name | Kind | Required | Description |
|---|------|------|----------|-------------|
| 1 | `model` | positional | Yes | Exact model name; `NULL` means no model filter |
| 2 | `model_sources` | named | No | Comma- or semicolon-separated `.ili` files, directories, or repository URLs |
| 3 | `class` | named | No | Optional short, topic-qualified, or fully qualified class name |

**Return columns:**

| Column | Type |
|--------|------|
| `model_name` | VARCHAR |
| `topic_name` | VARCHAR |
| `class_name` | VARCHAR |
| `class_fqn` | VARCHAR |
| `attribute_name` | VARCHAR |
| `attribute_fqn` | VARCHAR |
| `geometry_kind` | VARCHAR |
| `dimension` | VARCHAR |
| `coordinate_domain` | VARCHAR |
| `coordinate_domain_fqn` | VARCHAR |
| `crs_auth_name` | VARCHAR |
| `crs_code` | VARCHAR |
| `srid` | VARCHAR |
| `is_mandatory` | VARCHAR |
| `card_min` | VARCHAR |
| `card_max` | VARCHAR |
| `supports_arcs` | VARCHAR |
| `is_area_type` | VARCHAR |
| `is_multi_type` | VARCHAR |
| `transport_encoding` | VARCHAR |
| `duckdb_spatial_function` | VARCHAR |

**NULL behaviour:** Empty strings for missing metadata. `crs_auth_name`, `crs_code`, `srid` are NULL when no CRS mapping is configured.

**Error behaviour:**
- Native library init failure → `duckdb_init_set_error(g_error_buf)`.
- Model compilation failure → DuckDB error with NativeError message.

**Known limitations:**
- `transport_encoding` and `duckdb_spatial_function` are hardcoded (`WKT`/`ST_GeomFromText`) and don't yet reflect the v2 hex-WKB typed path.

---

### `read_xtf_objects(path, model_sources =>, models =>)`

**Signature:** `read_xtf_objects(path VARCHAR, model_sources => VARCHAR, models => VARCHAR) → TABLE(...)`

**Description:** Generic XTF object stream reader. Reads all objects from an XTF file without resolving class schemas. Attributes, references, and geometry returned as JSON.

**Parameters:**

| # | Name | Kind | Required | Description |
|---|------|------|----------|-------------|
| 1 | `path` | positional | Yes | Path to XTF file |
| 2 | `model_sources` | named | No | Comma- or semicolon-separated `.ili` files, directories, or repository URLs |
| 3 | `models` | named | No | Exact model-name filter; multiple names are separated by comma or semicolon |

**Return columns:**

| Column | Type |
|--------|------|
| `xtf_bid` | VARCHAR |
| `xtf_topic` | VARCHAR |
| `xtf_class` | VARCHAR |
| `xtf_class_fqn` | VARCHAR |
| `xtf_tid` | VARCHAR |
| `operation` | VARCHAR |
| `xtf_model` | VARCHAR |
| `attributes_json` | VARCHAR |
| `refs_json` | VARCHAR |
| `geom_json` | VARCHAR |
| `raw_event_json` | VARCHAR |

**NULL behaviour:** Empty strings for missing values. Missing attributes are absent from JSON objects (not `null`). Missing geometry returns `"{}"`.

**Error behaviour:**
- Native library init failure → `duckdb_init_set_error(g_error_buf)`.
- Missing `path` → `duckdb_init_set_error("Missing path path")`.
- Native call failure → DuckDB error with extracted NativeError message (Phase 2 fix).
- Java-side XTF read exceptions → throw RuntimeException, caught by NativeEntryPoints and converted to NativeError (Phase 2 fix). No more partial results.
- Model compilation failure → throws RuntimeException, propagated as model error (Phase 2 fix).

**Known limitations:**
- Read errors now throw RuntimeException — no more partial results returned as success (Phase 2 fixed).
- Class short names can collide across topics/models (Phase 7 fix).
- Operation column only populated for INSERT (3). Values for DELETE (1) and UPDATE (2) are not mapped.

---

### `read_xtf_class(path, class =>, model_sources =>, nested =>)`

**Signature:** `read_xtf_class(path VARCHAR, class => VARCHAR, model_sources => VARCHAR, nested => VARCHAR) → TABLE(...)`

**Description:** Reads an XTF file and returns rows for a specific INTERLIS class. Columns are dynamically determined from the class schema. Scalar attributes are exposed as model-aware DuckDB types (`VARCHAR`, `BIGINT`, `DOUBLE`, `BOOLEAN`, `DATE`, `TIME`, `TIMESTAMP`). Geometry attributes use native `GEOMETRY` on the typed v2 path and `VARCHAR` WKT on the fallback path.

**Parameters:**

| # | Name | Kind | Required | Description |
|---|------|------|----------|-------------|
| 1 | `path` | positional | Yes | Path to XTF file |
| 2 | `class` | named | Yes | Fully qualified class name (Model.Topic.Class) |
| 3 | `model_sources` | named | No | Comma- or semicolon-separated `.ili` files, directories, or repository URLs; a direct file's parent resolves `IMPORTS` |
| 4 | `nested` | named | No | Nesting mode: `'json'` (default) |

**Return columns (dynamic, always include):**

| Column | Type | Description |
|--------|------|-------------|
| `xtf_bid` | VARCHAR | Basket ID |
| `xtf_tid` | VARCHAR | Transfer ID (object OID) |
| `xtf_class` | VARCHAR | Short class name |
| `unsupported_json` | VARCHAR | JSON with attributes not in the schema |

Plus: one column per scalar attribute, `*_geom` for geometry attributes, `*_json` for STRUCTURE/BAG attributes, `*_ref` for role references.

**NULL behaviour:** Missing optional values are returned as SQL `NULL`. Empty strings remain empty strings. STRUCTURE/BAG columns return SQL `NULL` when absent, and BAG columns return `'[]'` only when the attribute is present but empty.

**Error behaviour:**
- Native library init failure → `duckdb_init_set_error(g_error_buf)`.
- Missing `path` or `class` → `duckdb_init_set_error("Missing path or class")`.
- Native call failure → DuckDB error with extracted NativeError message (Phase 2 fix).
- Java-side read exceptions → throw RuntimeException, no more partial results (Phase 2 fix).
- Zero rows returned → loaded as empty result set (not an error).
- Schema call failure in bind phase → fallback columns used (`xtf_bid`, `xtf_tid`, `xtf_class`) **without any warning**.

**Known limitations:**
- Column names use unqualified attribute names — potential collision in complex schemas.
- The fallback path without `ILI_CAP_TYPED_CLASS_SCAN` still exposes geometry as WKT `VARCHAR`.
- Table-function result columns remain nullable even for `MANDATORY` INTERLIS attributes because DuckDB 1.5.3 does not expose NOT NULL binding metadata in the C API.

---

### `read_xtf_structures(class =>, model_sources =>)`

**Signature:** `read_xtf_structures(class => VARCHAR, model_sources => VARCHAR) → TABLE(...)`

**Description:** Introspects all recursively reachable INTERLIS STRUCTURE definitions used by a class. No positional parameters — both are named.

**Parameters:**

| # | Name | Kind | Required | Description |
|---|------|------|----------|-------------|
| 1 | `class` | named | Yes | Fully qualified class name |
| 2 | `model_sources` | named | No | Comma- or semicolon-separated `.ili` files, directories, or repository URLs; defaults to `ILI_DEFAULT_MODELDIR` or `https://models.interlis.ch` |

**Return columns:**

| Column | Type |
|--------|------|
| `root_class_fqn` | VARCHAR |
| `structure_fqn` | VARCHAR |
| `structure_name` | VARCHAR |
| `attribute_fqn` | VARCHAR |
| `attribute_name` | VARCHAR |
| `interlis_type` | VARCHAR |
| `logical_type` | VARCHAR |
| `kind` | VARCHAR |
| `is_mandatory` | BOOLEAN |
| `card_min` | INTEGER |
| `card_max` | BIGINT |
| `enum_values_json` | VARCHAR |

**NULL behaviour:** `card_max` is SQL `NULL` for unbounded multiplicities. `enum_values_json` is SQL `NULL` for non-enum attributes.

**Error behaviour:**
- Native library init failure → `duckdb_init_set_error(g_error_buf)`.
- Missing `class` → `duckdb_init_set_error("Missing class")`.
- Native call failure → DuckDB error with extracted NativeError message (Phase 2 fix).

**Known limitations:** No XTF path is needed — the function is purely model-based and only uses the model directory.

---

### `read_xtf_association(path, association =>, model_sources =>)`

**Signature:** `read_xtf_association(path VARCHAR, association => VARCHAR, model_sources => VARCHAR) → TABLE(...)`

**Description:** Reads an INTERLIS association from an XTF file. Columns are dynamically determined from the association schema. Scalar attributes are exposed as model-aware DuckDB types (`VARCHAR`, `BIGINT`, `DOUBLE`, `BOOLEAN`, `DATE`, `TIME`, `TIMESTAMP`); role references remain `VARCHAR`.

**Parameters:**

| # | Name | Kind | Required | Description |
|---|------|------|----------|-------------|
| 1 | `path` | positional | Yes | Path to XTF file |
| 2 | `association` | named | Yes | Fully qualified association name (Model.Topic.Assoc) |
| 3 | `model_sources` | named | No | Comma- or semicolon-separated `.ili` files, directories, or repository URLs; a direct file's parent resolves `IMPORTS` |

**Return columns:** Dynamic, always includes `xtf_bid`, `xtf_tid`, `xtf_class`, `unsupported_json`.

**NULL behaviour:** Same as `read_xtf_class`.

**Error behaviour:** Same as `read_xtf_class`.

**Known limitations:**
- Role references stay `VARCHAR` because they are transfer IDs, not foreign-key typed columns.
- The typed association path requires `ILI_CAP_TYPED_ASSOC_SCAN`; without it, the fallback path exposes all association columns as `VARCHAR`.

---

### `ili_generate_import_sql(path, schema =>, model_sources =>, mapping =>, mode =>)`

**Signature:** `ili_generate_import_sql(path VARCHAR, schema => VARCHAR, model_sources => VARCHAR, mapping => VARCHAR, mode => VARCHAR) → TABLE(sql_statement VARCHAR)`

**Description:** Generates typed SQL DDL (`CREATE TABLE`) and DML (`INSERT INTO`) statements for importing an XTF file into a DuckDB schema. Output is wrapped in `BEGIN TRANSACTION`/`COMMIT`. Table names use `topic__class` convention.

**Breaking change (Phase 10):** Renamed from `ili_import_xtf`. Table names now use `topic__class` naming (e.g., `topic__gemeinde`). Migration: rename function calls and update table references.

**Parameters:**

| # | Name | Kind | Required | Description |
|---|------|------|----------|-------------|
| 1 | `path` | positional | Yes | Path to XTF file |
| 2 | `schema` | named | Yes | Target schema name |
| 3 | `model_sources` | named | No | Comma- or semicolon-separated `.ili` files, directories, or repository URLs; a direct file's parent resolves `IMPORTS` |
| 4 | `mapping` | named | No | Mapping mode. Only `"relational"` is supported (default). Unsupported values return `INVALID_ARGUMENT`. |
| 5 | `mode` | named | No | Import mode: `"create"` (default), `"replace"`, or `"append"`. |

**Return columns:**

| Column | Type |
|--------|------|
| `sql_statement` | VARCHAR |

**NULL behaviour:** N/A (single column of SQL text).

**Error behaviour:**
- Native library init failure → `duckdb_init_set_error(g_error_buf)`.
- Missing `path` or `schema` → `duckdb_init_set_error("Missing path or schema")`.
- Native call failure → DuckDB error with extracted NativeError message.
- Unsupported mapping value → `INVALID_ARGUMENT` with error message.
- Table name collision → DuckDB error with details.

**Known limitations:**
- Only `"relational"` mapping is supported.
- `DECIMAL(p,s)` precision not yet derived from INTERLIS `NUMERIC` bounds; decimal types use `DOUBLE`.
- Generated INSERT statements reference `read_xtf_class`/`read_xtf_association` which currently use TSV encoding.
- Geometry columns are mapped to `GEOMETRY` (bare); CRS-typed `GEOMETRY('EPSG:xxxx')` is generated only when `ILI_GEOMETRY_CRS_MAP` or `ILI_GEOMETRY_CRS_FILE` is configured.
- The generated DDL expects the v2 typed path (`ILI_CAP_TYPED_CLASS_SCAN`) for native GEOMETRY columns; with v1 VARCHAR fallback, manual `::GEOMETRY` casts would be needed in INSERT statements.

---

## Cross-Cutting Concerns

### Request Construction (C Side)

All requests are built with manual `snprintf` into fixed-size buffers (4096 or 8192 bytes). Long paths or model directories **may be silently truncated**. JSON special characters (`"`, `\`, newlines) in user path are **not escaped**.

### JSON Parsing (Java Side)

The Java side parses JSON with `String.indexOf` and `String.substring`. It does **not** handle:
- Escaped quotes within string values
- Nested JSON objects
- Unicode escapes
- Whitespace variations
- Boolean/number/null values (everything is treated as string)

### Memory Ownership (Phase 4)

| Allocation | Allocator | Required Deallocator | Status |
|------------|-----------|---------------------|--------|
| Native result strings | `UnmanagedMemory.malloc()` (GraalVM) | `g_native_free()` inside consolidated helper | **FIXED** Phase 4 |
| C-side `strdup` strings | `malloc()` (C) | `free()` (C) | OK |
| DuckDB string vectors | DuckDB internal | DuckDB manages | OK |

### Thread Safety (Phase 4)

- **Initialization** is threadsafe: Mutex-protected `ensure_native_ready()` ensures exactly-one init with persistent error storage. Failed initialization is never retried.
- **Java call serialization**: All Java calls are serialized by a global mutex (`g_java_lock`). INTERLIS library thread-safety is not guaranteed, so correctness is prioritized over parallelism.
- **Multiple DuckDB queries** can load the extension concurrently, but Java processing occurs sequentially.
- **Limitation**: Parallel query throughput is limited by the Java serialization lock. This will be optimized in a later phase once INTERLIS library thread-safety is analysed.

### Version Information

| Component | Version | Defined In |
|-----------|---------|------------|
| Extension version | `0.1.0-dev` | `interlis_extension.c:71` |
| Native version | `0.1.0` | `NativeVersion.java` |
| Core version | `0.1.0` | `CoreVersion.java` |
| DuckDB C ABI | `v1.2.0` | `extension_config.cmake` |
| GraalVM | `25.0.3` | `NativeVersion.java` |
| Platform | `macos-aarch64` | Hardcoded in `NativeVersion.java` |
