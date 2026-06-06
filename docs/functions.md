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

**Description:** Returns JSON with version info of the GraalVM native library: `nativeVersion`, `coreVersion`, `graalvmVersion`, `platform`, `nativeLibName`.

**Examples:**

```sql
-- 1. Show full native version JSON
SELECT ili_native_version();
-- => {"nativeVersion":"0.1.0","core_version":"0.1.0","graalvm_version":"...","platform":"darwin-aarch64","nativeLibName":"libduckdb_ili_native.dylib"}
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
| 2 | `modeldir` | positional | VARCHAR | Ja | Verzeichnis mit ILI-Modelldateien oder semikolon-getrennte URLs zu Modell-Repositories |

**Examples:**

```sql
-- 1. Validate a valid XTF file
SELECT json_extract(result, '$.valid') AS valid,
       json_extract(result, '$.errorCount') AS errors,
       json_extract(result, '$.warningCount') AS warnings
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
ili_validate(path VARCHAR, modeldir => VARCHAR) → TABLE(...)
```

**Description:** Validates an XTF file and returns one row per validation message.

**Parameters:**

| # | Name | Kind | Type | Erforderlich | Beschreibung |
|---|---|---|---|---|---|
| 1 | `path` | positional | VARCHAR | Ja | Pfad zur XTF-Datei |
| 2 | `modeldir` | named | VARCHAR | Ja | Verzeichnis mit ILI-Modelldateien oder semikolon-getrennte URLs |

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
| 1 | `modeldir` | positional | VARCHAR | Ja | Verzeichnis mit ILI-Dateien |
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
| 1 | `modeldir` | positional | VARCHAR | Ja | Verzeichnis mit ILI-Dateien |
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

```sql
-- 2. Nach Modellnamen filtern
SELECT model_name, topic_name
FROM ili_topics('testdata/synthetic/simple',
    model := 'SO_AGI_Simple_20260605');
```

```sql
-- 2. Filter topics by model
SELECT model_name, topic_name
FROM ili_topics('testdata/synthetic/simple',
    model := 'SO_AGI_Simple_20260605');
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
| 1 | `modeldir` | positional | VARCHAR | Ja | Verzeichnis mit ILI-Dateien |
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
| 1 | `modeldir` | positional | VARCHAR | Ja | Verzeichnis mit ILI-Dateien |
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
| 1 | `modeldir` | positional | VARCHAR | Ja | Verzeichnis mit ILI-Dateien |
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
| 2 | `modeldir` | named | VARCHAR | Ja | Verzeichnis mit ILI-Modelldateien oder semikolon-getrennte URLs |
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

**Description:** Reads an XTF file and returns rows for a specific INTERLIS class. Columns are dynamically determined from the class schema. Scalar attributes appear as individual columns of type VARCHAR. STRUCTURE attributes appear as `*_json` columns. BAG OF STRUCTURE attributes appear as `*_json` columns (JSON array). Geometry attributes appear as `*_wkb` columns.

**Parameters:**

| # | Name | Kind | Type | Erforderlich | Beschreibung |
|---|---|---|---|---|---|
| 1 | `input` | positional | VARCHAR | Ja | Pfad zur XTF-Datei |
| 2 | `class` | named | VARCHAR | Ja | Voll qualifizierter Klassenname, z.B. `Model.Topic.Class` |
| 3 | `modeldir` | named | VARCHAR | Ja | Verzeichnis mit ILI-Modelldateien oder semikolon-getrennte URLs |
| 4 | `nested` | named | VARCHAR | Nein (Default: `"json"`) | Nesting-Modus: `'json'` (Default) oder `'flat'` |

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
-- 2. Read a class with STRUCTURE and BAG (JSON extraction)
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
-- 3. Unnest BAG OF STRUCTURE elements
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
-- 4. Read a class with geometry (WKB column)
SELECT xtf_tid, Name, Lage_wkb AS geom, ST_GeomFromWKB(Lage_wkb) AS point
FROM read_xtf_class('testdata/synthetic/geometries/valid.xtf',
    class := 'SO_AGI_Geometries_20260605.Topic.PunktObjekt',
    modeldir := 'testdata/synthetic/geometries');
```

```sql
-- 5. nested-Modus explizit setzen (entspricht Default "json")
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

**Description:** Introspects INTERLIS STRUCTURE definitions used by a class. This function has **no positional parameters** — both parameters are named.

**Parameters:**

| # | Name | Kind | Type | Erforderlich | Beschreibung |
|---|---|---|---|---|---|
| 1 | `class` | named | VARCHAR | Ja | Voll qualifizierter Klassenname |
| 2 | `modeldir` | named | VARCHAR | Ja | Verzeichnis mit ILI-Modelldateien |

**Return columns:**

| Column | Type |
|---|---|
| `structure_name` | VARCHAR |
| `attr_name` | VARCHAR |
| `attr_type` | VARCHAR |
| `card_min` | VARCHAR |
| `card_max` | VARCHAR |

**Examples:**

```sql
-- 1. List all structures used by a class
SELECT structure_name, attr_name, attr_type, card_min, card_max
FROM read_xtf_structures(
    class := 'SO_AGI_Structures_20260605.Topic.Betrieb',
    modeldir := 'testdata/synthetic/structures'
);
```

```sql
-- 2. Filter to a specific structure
SELECT attr_name, attr_type, card_min, card_max
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

**Description:** Reads an INTERLIS association from an XTF file. Columns are dynamically determined from the association schema. Role references appear as `*_ref` columns.

**Parameters:**

| # | Name | Kind | Type | Erforderlich | Beschreibung |
|---|---|---|---|---|---|
| 1 | `input` | positional | VARCHAR | Ja | Pfad zur XTF-Datei |
| 2 | `association` | named | VARCHAR | Ja | Voll qualifizierter Assoziationsname |
| 3 | `modeldir` | named | VARCHAR | Ja | Verzeichnis mit ILI-Modelldateien |

**Return columns:** Dynamic, plus always:
- `xtf_bid` | VARCHAR
- `xtf_tid` | VARCHAR
- `xtf_class` | VARCHAR
- `unsupported_json` | VARCHAR

**Examples:**

```sql
-- 1. Read an association table
SELECT * FROM read_xtf_association(
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
SELECT * FROM read_xtf_association(
    'testdata/synthetic/associations/valid.xtf',
    association := 'SO_AGI_Associations_20260605.Topic.Besitz',
    modeldir := 'testdata/synthetic/associations'
) LIMIT 0;
```

---

### `ili_import_xtf()`

**Signature:**

```sql
ili_import_xtf(input VARCHAR, schema => VARCHAR, modeldir => VARCHAR, mapping => VARCHAR) → TABLE(sql_statement VARCHAR)
```

**Description:** Generates typed SQL DDL (`CREATE TABLE`) and DML (`INSERT INTO`) statements for importing an XTF file into a DuckDB schema. Numeric types are mapped to `BIGINT`, text and geometry to `VARCHAR`.

**Parameters:**

| # | Name | Kind | Type | Erforderlich | Beschreibung |
|---|---|---|---|---|---|
| 1 | `input` | positional | VARCHAR | Ja | Pfad zur XTF-Datei |
| 2 | `schema` | named | VARCHAR | Ja | Name des Ziel-Schemas in DuckDB |
| 3 | `modeldir` | named | VARCHAR | Ja | Verzeichnis mit ILI-Modelldateien |
| 4 | `mapping` | named | VARCHAR | Nein (Default: `"relational"`) | Mapping-Modus |

**Return columns:**

| Column | Type |
|---|---|
| `sql_statement` | VARCHAR |

**Examples:**

```sql
-- 1. Generate typed DDL and INSERT statements
SELECT sql_statement
FROM ili_import_xtf('testdata/synthetic/simple/valid.xtf',
    schema := 'my_schema',
    modeldir := 'testdata/synthetic/simple');
```

```sql
-- 2. Mit explizitem mapping-Parameter
SELECT sql_statement
FROM ili_import_xtf('testdata/synthetic/simple/valid.xtf',
    schema := 'my_schema',
    modeldir := 'testdata/synthetic/simple',
    mapping := 'relational');
```

```sql
-- 3. Generate and execute SQL (full import pipeline)
CREATE SCHEMA IF NOT EXISTS my_schema;

-- Generate and execute each statement
SELECT FORMAT('Executed: {}', sql_statement)
FROM ili_import_xtf('testdata/synthetic/simple/valid.xtf',
    schema := 'my_schema',
    modeldir := 'testdata/synthetic/simple');

-- Verify
SELECT count(*) FROM my_schema.gemeinde;
SELECT count(*) FROM my_schema.abbaustelle;
```

