# DuckDB-Interlis Functions

Complete reference for all 14 SQL functions provided by the `interlis` extension (3 scalar, 11 table).

---

## Scalar Functions

Return a single value per call.

---

### `ili_extension_version()`

**Signature:**

```sql
ili_extension_version() → VARCHAR
```

**Description:** Returns the DuckDB extension version string.

**Examples:**

```sql
-- 1. Show extension version
SELECT ili_extension_version();
-- => 0.1.0-dev
```

```sql
-- 2. Conditional check (useful in scripts)
SELECT CASE
    WHEN ili_extension_version() IS NOT NULL THEN 'Extension loaded'
    ELSE 'Extension NOT loaded'
END AS status;
```

---

### `ili_native_version()`

**Signature:**

```sql
ili_native_version() → VARCHAR
```

**Description:** Returns JSON with version info of the GraalVM native library: `native_version`, `core_version`, `graalvm_version`, `platform`, `native_lib`.

**Examples:**

```sql
-- 1. Show full native version JSON
SELECT ili_native_version();
-- => {"native_version":"0.1.0","core_version":"0.1.0","graalvm_version":"...","platform":"darwin-aarch64","native_lib":"libduckdb_ili_native.dylib"}
```

```sql
-- 2. Extract individual fields with json_extract
SELECT
    json_extract_string(ili_native_version(), '$.native_version') AS native_version,
    json_extract_string(ili_native_version(), '$.core_version') AS core_version,
    json_extract_string(ili_native_version(), '$.platform') AS platform;
```

---

### `ili_validate_summary_json()`

**Signature:**

```sql
ili_validate_summary_json(path VARCHAR, modeldir VARCHAR) → VARCHAR
```

**Description:** Validates an XTF file and returns a JSON summary with `valid` (boolean), `errorCount`, `warningCount`, `infoCount`, and `messages` (array).

**Parameters:**

| # | Name | Kind | Type | Erforderlich | Beschreibung |
|---|---|---|---|---|---|
| 1 | `path` | positional | VARCHAR | Ja | Pfad zur XTF-Datei |
| 2 | `modeldir` | positional | VARCHAR | Nein | Verzeichnis mit ILI-Modelldateien oder semikolon-getrennte URLs zu Modell-Repositories. Default: Verzeichnis der XTF-Datei + `https://models.interlis.ch`, überschreibbar via `ILI_DEFAULT_MODELDIR` |

**Examples:**

```sql
-- 1. Validate a valid XTF file
SELECT json_extract(result, '$.valid') AS valid,
       json_extract(result, '$.errorCount') AS errors
FROM (
    SELECT ili_validate_summary_json(
        'testdata/synthetic/simple/valid.xtf',
        'testdata/synthetic/simple'
    ) AS result
);
```

```sql
-- 2. Validate an invalid XTF file
SELECT json_extract(result, '$.valid') AS valid,
       json_extract(result, '$.errorCount') AS errors
FROM (
    SELECT ili_validate_summary_json(
        'testdata/synthetic/simple/invalid.xtf',
        'testdata/synthetic/simple'
    ) AS result
);
```

```sql
-- 3. Validate using remote model repositories
SELECT json_extract(result, '$.valid') AS valid,
       json_extract(result, '$.errorCount') AS errors,
       json_extract(result, '$.warningCount') AS warnings
FROM (
    SELECT ili_validate_summary_json(
        'testdata/external/ch.so.afu.abbaustellen/ch.so.afu.abbaustellen.xtf',
        'https://models.interlis.ch;https://geo.so.ch/models'
    ) AS result
);
```

---

## Table Functions

Return result sets usable in `FROM` clauses.

---

### `ili_validate()`

**Signature:**

```sql
ili_validate(path VARCHAR, [modeldir => VARCHAR], [profile => VARCHAR], [max_messages => INTEGER]) → TABLE(...)
```

**Description:** Validates an XTF file and returns one row per validation message. The total error/warning/info counts always reflect the complete validation result regardless of `max_messages`.

**Parameters:**

| # | Name | Kind | Type | Required | Description |
|---|---|---|---|---|---|
| 1 | `path` | positional | VARCHAR | Yes | Path to the XTF file |
| 2 | `modeldir` | named | VARCHAR | No | Directory with ILI model files or semicolon-separated URLs. Default: directory of the XTF file + `https://models.interlis.ch`, overridable via `ILI_DEFAULT_MODELDIR` |
| 3 | `profile` | named | VARCHAR | No | Validation profile: `full` (default), `structural`, or `fast`. Controls which checks are performed. |
| 4 | `max_messages` | named | INTEGER | No | Maximum number of detail message rows returned. Does NOT affect validity or the total error/warning/info counts. Use `-1` or omit for unlimited. |

**NULL semantics:** Missing integer values (e.g., `line`, `column`) are SQL `NULL`. Missing string values (e.g., `xtf_tid`, `xtf_bid`) are SQL `NULL`. Empty strings remain empty strings. `0` is a genuine value, distinct from `NULL`.

**Error behavior:** Technical validator errors (e.g., missing model, corrupted file) produce a DuckDB query error. Validation messages from a valid/invalid XTF produce normal data rows — the query succeeds even if the file is semantically invalid.

**Return columns:**

| Column | Type |
|---|---|
| `severity` | VARCHAR |
| `code` | VARCHAR |
| `message` | VARCHAR |
| `filename` | VARCHAR |
| `line` | INTEGER |
| `column` | INTEGER |
| `xtf_tid` | VARCHAR |
| `xtf_bid` | VARCHAR |
| `model` | VARCHAR |
| `topic` | VARCHAR |
| `class_name` | VARCHAR |
| `attribute_name` | VARCHAR |
| `raw` | VARCHAR |

**Examples:**

```sql
-- 1. List all ERROR messages from an invalid XTF
SELECT severity, code, message, line, xtf_tid
FROM ili_validate('testdata/synthetic/simple/invalid.xtf',
    modeldir := 'testdata/synthetic/simple')
WHERE severity = 'ERROR';
```

```sql
-- 2. Count messages per class_name
SELECT class_name, count(*) AS cnt
FROM ili_validate('testdata/synthetic/simple/invalid.xtf',
    modeldir := 'testdata/synthetic/simple')
WHERE class_name IS NOT NULL AND class_name <> ''
GROUP BY class_name
ORDER BY cnt DESC;
```

```sql
-- 3. Filter by severity and attribute_name
SELECT severity, message, attribute_name, line
FROM ili_validate('testdata/synthetic/simple/invalid.xtf',
    modeldir := 'testdata/synthetic/simple')
WHERE severity IN ('ERROR', 'WARNING')
  AND attribute_name IS NOT NULL
ORDER BY severity, line;
```

---

### `ili_models()`

**Signature:**

```sql
ili_models(modeldir VARCHAR, model => VARCHAR) → TABLE(...)
```

**Description:** Lists INTERLIS models found in a model directory.

**Parameters:**

| # | Name | Kind | Type | Erforderlich | Beschreibung |
|---|---|---|---|---|---|
| 1 | `modeldir` | positional | VARCHAR | Nein | Verzeichnis mit ILI-Dateien. Default: `https://models.interlis.ch`, überschreibbar via `ILI_DEFAULT_MODELDIR` |
| 2 | `model` | named | VARCHAR | Nein | Optional: nach Modellnamen filtern |
| 3 | `class` | named | VARCHAR | Nein | Optional: nach Klassennamen filtern |

**Return columns:**

| Column | Type |
|---|---|
| `name` | VARCHAR |
| `version` | VARCHAR |
| `issuer` | VARCHAR |
| `language` | VARCHAR |
| `ili_version` | VARCHAR |

**Examples:**

```sql
-- 1. Alle Modelle ohne Filter
SELECT name, version, language, ili_version
FROM ili_models('testdata/synthetic/simple');
```

```sql
-- 2. Nach Modellnamen filtern
SELECT name, version, ili_version
FROM ili_models('testdata/synthetic/simple',
    model := 'SO_AGI_Simple_20260605');
```

```sql
-- 3. Nach Klasse filtern
SELECT name, version, language
FROM ili_models('testdata/synthetic/simple',
    class := 'Gemeinde');
```

---

### `ili_topics()`

**Signature:**

```sql
ili_topics(modeldir VARCHAR, model => VARCHAR) → TABLE(...)
```

**Description:** Lists topics within INTERLIS models.

**Parameters:**

| # | Name | Kind | Type | Erforderlich | Beschreibung |
|---|---|---|---|---|---|
| 1 | `modeldir` | positional | VARCHAR | Nein | Verzeichnis mit ILI-Dateien. Default: `https://models.interlis.ch`, überschreibbar via `ILI_DEFAULT_MODELDIR` |
| 2 | `model` | named | VARCHAR | Nein | Optional: nach Modellnamen filtern |
| 3 | `class` | named | VARCHAR | Nein | Optional: nach Klassennamen filtern |

**Return columns:**
| Column | Type |
|---|---|
| `model_name` | VARCHAR |
| `topic_name` | VARCHAR |
| `kind` | VARCHAR |

**Examples:**

```sql
-- 1. Alle Topics ohne Filter
SELECT model_name, topic_name, kind
FROM ili_topics('testdata/synthetic/simple');
```

```sql
-- 2. Nach Modellnamen filtern
SELECT model_name, topic_name
FROM ili_topics('testdata/synthetic/simple',
    model := 'SO_AGI_Simple_20260605');
```

```sql
-- 3. Topics aus einem anderen Modell
SELECT model_name, topic_name, kind
FROM ili_topics('testdata/synthetic/structures');
```

---

### `ili_classes()`

**Signature:**

```sql
ili_classes(modeldir VARCHAR, model => VARCHAR) → TABLE(...)
```

**Description:** Lists classes within INTERLIS models/topics.

**Parameters:**

| # | Name | Kind | Type | Erforderlich | Beschreibung |
|---|---|---|---|---|---|
| 1 | `modeldir` | positional | VARCHAR | Nein | Verzeichnis mit ILI-Dateien. Default: `https://models.interlis.ch`, überschreibbar via `ILI_DEFAULT_MODELDIR` |
| 2 | `model` | named | VARCHAR | Nein | Optional: nach Modellnamen filtern |
| 3 | `class` | named | VARCHAR | Nein | Optional: nach Klassennamen filtern |

**Return columns:**
| Column | Type |
|---|---|
| `model_name` | VARCHAR |
| `topic_name` | VARCHAR |
| `class_name` | VARCHAR |
| `kind` | VARCHAR |
| `is_abstract` | VARCHAR |
| `is_extended` | VARCHAR |
| `base_class` | VARCHAR |

**Examples:**

```sql
-- 1. Alle Klassen ohne Filter
SELECT model_name, topic_name, class_name, kind, is_abstract
FROM ili_classes('testdata/synthetic/simple');
```

```sql
-- 2. Nach Modellnamen filtern
SELECT topic_name, class_name, is_abstract, is_extended
FROM ili_classes('testdata/synthetic/simple',
    model := 'SO_AGI_Simple_20260605');
```

```sql
-- 3. Klassen aus einem anderen Modell
SELECT model_name, topic_name, class_name, kind
FROM ili_classes('testdata/synthetic/structures');
```

---

### `ili_attributes()`

**Signature:**

```sql
ili_attributes(modeldir VARCHAR, model => VARCHAR, class => VARCHAR) → TABLE(...)
```

**Description:** Lists attributes of classes in INTERLIS models.

**Parameters:**

| # | Name | Kind | Type | Erforderlich | Beschreibung |
|---|---|---|---|---|---|
| 1 | `modeldir` | positional | VARCHAR | Nein | Verzeichnis mit ILI-Dateien. Default: `https://models.interlis.ch`, überschreibbar via `ILI_DEFAULT_MODELDIR` |
| 2 | `model` | named | VARCHAR | Nein | Optional: nach Modellnamen filtern |
| 3 | `class` | named | VARCHAR | Nein | Optional: nach Klassennamen filtern |

**Return columns:**
| Column | Type |
|---|---|
| `model_name` | VARCHAR |
| `topic_name` | VARCHAR |
| `class_name` | VARCHAR |
| `attr_name` | VARCHAR |
| `type_name` | VARCHAR |
| `kind` | VARCHAR |
| `is_mandatory` | VARCHAR |
| `card_min` | VARCHAR |
| `card_max` | VARCHAR |

**Examples:**

```sql
-- 1. Attribute einer bestimmten Klasse
SELECT attr_name, type_name, kind, is_mandatory, card_min, card_max
FROM ili_attributes('testdata/synthetic/simple',
    class := 'Gemeinde');
```

```sql
-- 2. Attribute nach Modell filtern
SELECT class_name, attr_name, type_name, is_mandatory
FROM ili_attributes('testdata/synthetic/simple',
    model := 'SO_AGI_Simple_20260605');
```

```sql
-- 3. Nur Pflichtattribute (Mandatory)
SELECT class_name, attr_name, type_name
FROM ili_attributes('testdata/synthetic/simple')
WHERE is_mandatory = 'true';
```

---

### `ili_enumerations()`

**Signature:**

```sql
ili_enumerations(modeldir VARCHAR, model => VARCHAR, class => VARCHAR) → TABLE(...)
```

**Description:** Lists enumeration (enum) values defined in INTERLIS models.

**Parameters:**

| # | Name | Kind | Type | Erforderlich | Beschreibung |
|---|---|---|---|---|---|
| 1 | `modeldir` | positional | VARCHAR | Nein | Verzeichnis mit ILI-Dateien. Default: `https://models.interlis.ch`, überschreibbar via `ILI_DEFAULT_MODELDIR` |
| 2 | `model` | named | VARCHAR | Nein | Optional: nach Modellnamen filtern |
| 3 | `class` | named | VARCHAR | Nein | Optional: nach Klassennamen filtern |

**Return columns:**
| Column | Type |
|---|---|
| `model_name` | VARCHAR |
| `topic_name` | VARCHAR |
| `enum_name` | VARCHAR |
| `element` | VARCHAR |
| `element_line` | VARCHAR |

**Examples:**

```sql
-- 1. Alle Enum-Werte
SELECT model_name, enum_name, element
FROM ili_enumerations('testdata/synthetic/simple');
```

```sql
-- 2. Enum-Werte eines Modells
SELECT enum_name, element
FROM ili_enumerations('testdata/synthetic/simple',
    model := 'SO_AGI_Simple_20260605');
```

---

### `ili_geometry_attributes()`

**Signature:**

```sql
ili_geometry_attributes(modeldir VARCHAR, model => VARCHAR, class => VARCHAR) → TABLE(...)
```

**Description:** Lists geometry attributes found in INTERLIS models. Returns metadata for each geometry attribute including type, dimension, coordinate domain, CRS, cardinality, and encoding info. This is a pure introspection function — no XTF input needed.

**Parameters:**

| # | Name | Kind | Type | Erforderlich | Beschreibung |
|---|---|---|---|---|---|
| 1 | `modeldir` | positional | VARCHAR | Nein | Verzeichnis mit ILI-Dateien. Default: `https://models.interlis.ch`, überschreibbar via `ILI_DEFAULT_MODELDIR` |
| 2 | `model` | named | VARCHAR | Nein | Optional: nach Modellnamen filtern |
| 3 | `class` | named | VARCHAR | Nein | Optional: nach Klassennamen filtern |

**Return columns:**

| Column | Type | Beschreibung |
|---|---|---|
| `model_name` | VARCHAR | Modellname |
| `topic_name` | VARCHAR | Topic-Name |
| `class_name` | VARCHAR | Klassenname |
| `class_fqn` | VARCHAR | Voll qualifizierter Klassenname (`Model.Topic.Class`) |
| `attribute_name` | VARCHAR | Attributname |
| `attribute_fqn` | VARCHAR | Voll qualifizierter Attributname |
| `geometry_kind` | VARCHAR | Geometrietyp: `POINT`, `MULTIPOINT`, `LINESTRING`, `MULTILINESTRING`, `POLYGON`, `MULTIPOLYGON`, `AREA`, `MULTIAREA` |
| `dimension` | INTEGER | Koordinatendimension: 2 (XY) oder 3 (XYZ) |
| `coordinate_domain` | VARCHAR | INTERLIS-Koordinatendomäne (Kurzname) |
| `coordinate_domain_fqn` | VARCHAR | Voll qualifizierter Name der Koordinatendomäne |
| `crs_auth_name` | VARCHAR | CRS-Authority (z.B. `EPSG`), oder NULL |
| `crs_code` | VARCHAR | CRS-Code (z.B. `2056`), oder NULL |
| `srid` | INTEGER | SRID, oder NULL |
| `is_mandatory` | VARCHAR | `true` wenn Pflichtattribut, sonst `false` |
| `card_min` | VARCHAR | Minimale Kardinalität |
| `card_max` | VARCHAR | Maximale Kardinalität |
| `supports_arcs` | VARCHAR | `true` wenn Bögen (ARC) unterstützt, sonst `false` |
| `is_area_type` | VARCHAR | `true` wenn AREA/MULTIAREA-Typ, sonst `false` |
| `is_multi_type` | VARCHAR | `true` wenn Multi-Typ (MULTICOORD, MULTIPOLYLINE etc.), sonst `false` |
| `transport_encoding` | VARCHAR | Encoding des Geometrie-Transports (`WKT`) |
| `duckdb_spatial_function` | VARCHAR | Empfohlene DuckDB-Funktion (`ST_GeomFromText`) |

**Examples:**

```sql
-- 1. List all geometry attributes in a model
SELECT class_name, attribute_name, geometry_kind, dimension
FROM ili_geometry_attributes('testdata/synthetic/geometries');
```

```sql
-- 2. Filter by model and class
SELECT attribute_name, geometry_kind, dimension,
       is_mandatory, card_min, card_max
FROM ili_geometry_attributes('testdata/synthetic/geometries',
    model := 'SO_AGI_Geometries_20260605',
    class := 'PunktObjekt');
```

```sql
-- 3. Find all 3D geometry attributes
SELECT class_name, attribute_name, geometry_kind, dimension
FROM ili_geometry_attributes('testdata/synthetic/geometries')
WHERE dimension = 3;
```

---

### `read_xtf_objects()`

**Signature:**

```sql
read_xtf_objects(input VARCHAR, modeldir => VARCHAR, models => VARCHAR) → TABLE(...)
```

**Description:** Generic XTF object stream reader. Reads all objects from an XTF file without resolving class schemas. Each row is one XTF event with attributes, references, and geometry as JSON.

**Parameters:**

| # | Name | Kind | Type | Erforderlich | Beschreibung |
|---|---|---|---|---|---|
| 1 | `input` | positional | VARCHAR | Ja | Pfad zur XTF-Datei |
| 2 | `modeldir` | named | VARCHAR | Nein | Verzeichnis mit ILI-Modelldateien oder semikolon-getrennte URLs. Default: Verzeichnis der XTF-Datei + `https://models.interlis.ch`, überschreibbar via `ILI_DEFAULT_MODELDIR` |
| 3 | `models` | named | VARCHAR | Nein | Optional: nach Modellnamen filtern |

**Return columns:**

| Column | Type |
|---|---|
| `xtf_bid` | VARCHAR |
| `xtf_topic` | VARCHAR |
| `xtf_class` | VARCHAR |
| `xtf_tid` | VARCHAR |
| `operation` | VARCHAR |
| `attributes_json` | VARCHAR |
| `refs_json` | VARCHAR |
| `geom_json` | VARCHAR |
| `raw_event_json` | VARCHAR |

**Examples:**

```sql
-- 1. List all objects with their classes
SELECT xtf_bid, xtf_topic, xtf_class, xtf_tid, operation
FROM read_xtf_objects('testdata/synthetic/simple/valid.xtf',
    modeldir := 'testdata/synthetic/simple');
```

```sql
-- 2. Count objects by class
SELECT xtf_class, count(*) AS cnt
FROM read_xtf_objects('testdata/synthetic/simple/valid.xtf',
    modeldir := 'testdata/synthetic/simple')
GROUP BY xtf_class
ORDER BY cnt DESC;
```

```sql
-- 3. Extract attribute values from the JSON payload
SELECT xtf_tid, xtf_class,
    json_extract_string(attributes_json, '$.Name') AS name
FROM read_xtf_objects('testdata/synthetic/simple/valid.xtf',
    modeldir := 'testdata/synthetic/simple');
```

```sql
-- 4. Mit optionalem models-Filter
SELECT xtf_class, xtf_tid, operation
FROM read_xtf_objects('testdata/synthetic/simple/valid.xtf',
    modeldir := 'testdata/synthetic/simple',
    models := 'SO_AGI_Simple_20260605');
```

---

### `read_xtf_class()`

**Signature:**

```sql
read_xtf_class(input VARCHAR, class => VARCHAR, modeldir => VARCHAR, nested => VARCHAR) → TABLE(...)
```

 **Description:** Reads an XTF file and returns rows for a specific INTERLIS class. Columns are dynamically determined from the class schema. Scalar attributes are exposed as model-aware DuckDB types (`VARCHAR`, `BIGINT`, `DOUBLE`, `BOOLEAN`, `DATE`, `TIME`, `TIMESTAMP`). STRUCTURE attributes appear as `*_json` columns. BAG OF STRUCTURE attributes appear as `*_json` columns (JSON array). Geometry attributes appear as `*_geom` columns: native `GEOMETRY` type (v2 typed path) with hex-WKB internally, or `VARCHAR` WKT (v1 fallback). Use `::GEOMETRY` cast only with the v1 fallback path. Missing optional values are returned as SQL `NULL`.

**Parameters:**

| # | Name | Kind | Type | Erforderlich | Beschreibung |
|---|---|---|---|---|---|
| 1 | `input` | positional | VARCHAR | Ja | Pfad zur XTF-Datei |
| 2 | `class` | named | VARCHAR | Ja | Voll qualifizierter Klassenname, z.B. `Model.Topic.Class` |
| 3 | `modeldir` | named | VARCHAR | Nein | Verzeichnis mit ILI-Modelldateien oder semikolon-getrennte URLs. Default: Verzeichnis der XTF-Datei + `https://models.interlis.ch`, überschreibbar via `ILI_DEFAULT_MODELDIR` |
| 4 | `nested` | named | VARCHAR | Nein (Default: `"json"`) | Nesting-Modus: `'json'` (Default, Struktur-Spalten mit `_json`-Suffix) oder `'duckdb'` (ohne Suffix) |

**Return columns:** Dynamic, plus always:
- `xtf_bid` | VARCHAR
- `xtf_tid` | VARCHAR
- `xtf_class` | VARCHAR
- `unsupported_json` | VARCHAR

**Examples:**

```sql
-- 1. Read a simple class (no structures, no geometry)
SELECT xtf_tid, Name, bfs_nr
FROM read_xtf_class('testdata/synthetic/simple/valid.xtf',
    class := 'SO_AGI_Simple_20260605.Topic.Gemeinde',
    modeldir := 'testdata/synthetic/simple');
```

```sql
-- 2. Read a class with typed scalar columns
SELECT xtf_tid, Aktiv, Anzahl, Genauigkeit, Stichtag, Uhrzeit, Zeitstempel
FROM read_xtf_class('testdata/synthetic/typedscalars/valid.xtf',
    class := 'SO_AGI_TypedScalars_20260611.Topic.Messung',
    modeldir := 'testdata/synthetic/typedscalars');
```

```sql
-- 3. Read a class with STRUCTURE and BAG (JSON extraction)
SELECT Name,
    json_extract_string(Adresse_json, '$.Strasse') AS Strasse,
    json_extract_string(Adresse_json, '$.PLZ') AS PLZ,
    json_extract_string(Adresse_json, '$.Ort') AS Ort,
    json_array_length(Kontakte_json) AS Anzahl_Kontakte
FROM read_xtf_class('testdata/synthetic/structures/valid.xtf',
    class := 'SO_AGI_Structures_20260605.Topic.Betrieb',
    modeldir := 'testdata/synthetic/structures');
```

```sql
-- 4. Unnest BAG OF STRUCTURE elements
SELECT Name,
    json_extract_string(unnested, '$.Typ') AS KontaktTyp,
    json_extract_string(unnested, '$.Telefon') AS Telefon,
    json_extract_string(unnested, '$.Email') AS Email
FROM (
    SELECT Name, unnest(json_transform(Kontakte_json, '["VARCHAR"]')) AS unnested
    FROM read_xtf_class('testdata/synthetic/structures/valid.xtf',
        class := 'SO_AGI_Structures_20260605.Topic.Betrieb',
        modeldir := 'testdata/synthetic/structures')
);
```

```sql
-- 5. Read a class with geometry
-- v2 typed path: native GEOMETRY columns (no cast needed)
-- v1 fallback path: WKT VARCHAR columns, cast with ::GEOMETRY
SELECT xtf_tid, Name, Lage_geom,
       ST_GeometryType(Lage_geom) AS geometry_type
FROM read_xtf_class('testdata/synthetic/geometries/valid.xtf',
    class := 'SO_AGI_Geometries_20260605.Topic.PunktObjekt',
    modeldir := 'testdata/synthetic/geometries');
```

```sql
-- 6. nested-Modus explizit setzen (entspricht Default "json")
SELECT Name, Adresse_json
FROM read_xtf_class('testdata/synthetic/structures/valid.xtf',
    class := 'SO_AGI_Structures_20260605.Topic.Betrieb',
    modeldir := 'testdata/synthetic/structures',
    nested := 'json');
```

---

### `read_xtf_structures()`

**Signature:**

```sql
read_xtf_structures(class => VARCHAR, modeldir => VARCHAR) → TABLE(...)
```

**Description:** Introspects all recursively reachable INTERLIS STRUCTURE definitions used by a class. This function has **no positional parameters** — both parameters are named.

**Parameters:**

| # | Name | Kind | Type | Erforderlich | Beschreibung |
|---|---|---|---|---|---|
| 1 | `class` | named | VARCHAR | Ja | Voll qualifizierter Klassenname |
| 2 | `modeldir` | named | VARCHAR | Nein | Verzeichnis mit ILI-Modelldateien. Default: `https://models.interlis.ch`, überschreibbar via `ILI_DEFAULT_MODELDIR` |

**Return columns:**

| Column | Type |
|---|---|
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

**Examples:**

```sql
-- 1. List all structures used by a class
SELECT structure_name, attribute_name, interlis_type, logical_type, kind, card_min, card_max
FROM read_xtf_structures(
    class := 'SO_AGI_Structures_20260605.Topic.Betrieb',
    modeldir := 'testdata/synthetic/structures'
);
```

```sql
-- 2. Filter to a specific structure
SELECT attribute_name, logical_type, kind, enum_values_json
FROM read_xtf_structures(
    class := 'SO_AGI_Structures_20260605.Topic.Betrieb',
    modeldir := 'testdata/synthetic/structures'
)
WHERE structure_name = 'Adresse';
```

---

### `read_xtf_association()`

**Signature:**

```sql
read_xtf_association(input VARCHAR, association => VARCHAR, modeldir => VARCHAR) → TABLE(...)
```

**Description:** Reads an INTERLIS association from an XTF file. Columns are dynamically determined from the association schema. Role references appear as `*_ref` columns. Scalar attributes are exposed as model-aware DuckDB types (`VARCHAR`, `BIGINT`, `DOUBLE`, `BOOLEAN`, `DATE`, `TIME`, `TIMESTAMP`). Missing optional values are returned as SQL `NULL`.

**Parameters:**

| # | Name | Kind | Type | Erforderlich | Beschreibung |
|---|---|---|---|---|---|
| 1 | `input` | positional | VARCHAR | Ja | Pfad zur XTF-Datei |
| 2 | `association` | named | VARCHAR | Ja | Voll qualifizierter Assoziationsname |
| 3 | `modeldir` | named | VARCHAR | Nein | Verzeichnis mit ILI-Modelldateien. Default: Verzeichnis der XTF-Datei + `https://models.interlis.ch`, überschreibbar via `ILI_DEFAULT_MODELDIR` |

**Return columns:** Dynamic, plus always:
- `xtf_bid` | VARCHAR
- `xtf_tid` | VARCHAR
- `xtf_class` | VARCHAR
- `unsupported_json` | VARCHAR

**Examples:**

```sql
-- 1. Read an association table
SELECT xtf_tid, besitzer_ref, grundstueck_ref, Anteil
FROM read_xtf_association(
    'testdata/synthetic/associations/valid.xtf',
    association := 'SO_AGI_Associations_20260605.Topic.Besitz',
    modeldir := 'testdata/synthetic/associations'
);
```

```sql
-- 2. JOIN association with its two classes
WITH
  person AS (
    SELECT * FROM read_xtf_class('testdata/synthetic/associations/valid.xtf',
        class := 'SO_AGI_Associations_20260605.Topic.Person',
        modeldir := 'testdata/synthetic/associations')
  ),
  grundstueck AS (
    SELECT * FROM read_xtf_class('testdata/synthetic/associations/valid.xtf',
        class := 'SO_AGI_Associations_20260605.Topic.Grundstueck',
        modeldir := 'testdata/synthetic/associations')
  ),
  besitz AS (
    SELECT * FROM read_xtf_association('testdata/synthetic/associations/valid.xtf',
        association := 'SO_AGI_Associations_20260605.Topic.Besitz',
        modeldir := 'testdata/synthetic/associations')
  )
SELECT person.name, grundstueck.nummer, besitz.anteil
FROM besitz
JOIN person ON besitz.besitzer_ref = person.xtf_tid
JOIN grundstueck ON besitz.grundstueck_ref = grundstueck.xtf_tid;
```

```sql
-- 3. Inspect association schema (column names)
DESCRIBE SELECT * FROM read_xtf_association(
    'testdata/synthetic/associations/valid.xtf',
    association := 'SO_AGI_Associations_20260605.Topic.Besitz',
    modeldir := 'testdata/synthetic/associations'
);
```

---

### `ili_generate_import_sql()`

**Signature:**

```sql
ili_generate_import_sql(input VARCHAR, schema => VARCHAR, modeldir => VARCHAR, mapping => VARCHAR, mode => VARCHAR) → TABLE(sql_statement VARCHAR)
```

**Description:** Generates typed SQL DDL (`CREATE TABLE`) and DML (`INSERT INTO`) statements for importing an XTF file into a DuckDB schema. The output is wrapped in `BEGIN TRANSACTION` / `COMMIT`. Type mapping: integers without decimals → `BIGINT`, numerics with decimals → `DOUBLE`, text → `VARCHAR`, geometry → `GEOMETRY` (or `GEOMETRY('EPSG:xxxx')` with CRS mapping via `ILI_GEOMETRY_CRS_MAP`/`ILI_GEOMETRY_CRS_FILE`), booleans → `BOOLEAN`, dates → `DATE`, timestamps → `TIMESTAMP`, dates+times → `TIMESTAMP`, times → `TIME`.

**Breaking change:** Renamed from `ili_import_xtf` in Phase 10. Table names now use `topic__class` naming (e.g., `topic__gemeinde` instead of `gemeinde`) to avoid collisions across topics.

**Parameters:**

| # | Name | Kind | Type | Erforderlich | Beschreibung |
|---|---|---|---|---|---|
| 1 | `input` | positional | VARCHAR | Ja | Pfad zur XTF-Datei |
| 2 | `schema` | named | VARCHAR | Ja | Name des Ziel-Schemas in DuckDB |
| 3 | `modeldir` | named | VARCHAR | Nein | Verzeichnis mit ILI-Modelldateien. Default: Verzeichnis der XTF-Datei + `https://models.interlis.ch` |
| 4 | `mapping` | named | VARCHAR | Nein (Default: `"relational"`) | Mapping-Modus. Nur `"relational"` wird derzeit unterstützt. |
| 5 | `mode` | named | VARCHAR | Nein (Default: `"create"`) | Import-Modus: `"create"` (CREATE TABLE IF NOT EXISTS), `"replace"` (DROP + CREATE), `"append"` (nur INSERT) |

**Return columns:**

| Column | Type |
|---|---|
| `sql_statement` | VARCHAR |

**Examples:**

```sql
-- 1. Generate typed DDL and INSERT statements (default mode: create)
SELECT sql_statement
FROM ili_generate_import_sql('testdata/synthetic/simple/valid.xtf',
    schema := 'my_schema',
    modeldir := 'testdata/synthetic/simple');
```

```sql
-- 2. Mit mode=replace (DROP + CREATE + INSERT)
SELECT sql_statement
FROM ili_generate_import_sql('testdata/synthetic/simple/valid.xtf',
    schema := 'my_schema',
    modeldir := 'testdata/synthetic/simple',
    mode := 'replace');
```

```sql
-- 3. Mit mode=append (nur INSERT, keine DDL)
SELECT sql_statement
FROM ili_generate_import_sql('testdata/synthetic/simple/valid.xtf',
    schema := 'my_schema',
    modeldir := 'testdata/synthetic/simple',
    mode := 'append');
```
