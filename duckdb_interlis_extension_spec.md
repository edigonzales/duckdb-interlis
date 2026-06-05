# Spezifikation: DuckDB-INTERLIS-Extension mit GraalVM Native Shared Library

## 1. Zweck

Dieses Repository soll eine DuckDB-Extension bereitstellen, die INTERLIS-/XTF-Funktionalität direkt aus DuckDB-SQL nutzbar macht. Die fachliche INTERLIS-Logik bleibt in Java und wird mit GraalVM Native Image als native shared library gebaut. Die DuckDB-Extension ruft diese native Library auf und stellt daraus SQL-Funktionen, Table Functions und später Import-/Export-Kommandos bereit.

Der Fokus liegt zuerst auf:

1. XTF-/ITF-Validierung als tabellarisch auswertbare DuckDB-Funktion.
2. Modellanalyse: Modelle, Topics, Klassen, Attribute, Strukturen, Rollen, Aufzählungen.
3. XTF-Lesen: zuerst generischer Objektstrom, danach konkrete Klassen als Tabellen.
4. Unterstützung komplexerer Modelle mit STRUCTURE, BAG OF STRUCTURE, REFERENCE, ASSOCIATION und Geometrien.
5. Gute Entwickelbarkeit auf macOS ARM64 mit schnellen Build-/Test-/SQL-Scripts.

Das Projekt soll nicht sofort eine perfekte ili2db-Kopie in DuckDB werden. Es soll in kleinen Phasen entstehen, wobei jede Phase einen stabilen, getesteten und manuell ausprobierbaren Funktionsumfang liefert.

## 2. Rahmenbedingungen

### 2.1 Technologie

- Java: GraalVM 25.0.1
- Build: Gradle Groovy DSL
- Entwicklungsplattform: macOS ARM64
- DuckDB: Zielversion 1.5.x, explizit versioniert/pinned
- Native Java-Bibliothek: GraalVM Native Image als shared library
- DuckDB-Extension: C oder C++ Extension, ebenfalls im gleichen Git-Repository
- Repository: ein Monorepo für Java-Library, native shared lib, DuckDB-Extension, Tests, Scripts und Dokumentation

### 2.2 Verwendete Java-INTERLIS-Werkzeuge

Folgende Werkzeuge/Bibliotheken sind als bekannte Basis zu verwenden:

- `iox-ili`: IOX-/XTF-Lesen und Schreiben, Event-Modell
- `ilivalidator`: INTERLIS-Validierung
- `ili2c`: Modellkompilierung, TransferDescription, Metamodell
- `ehisqlgen`: SQL-DDL-Generierung und Mapping-Ideen
- `ili2db`: Referenz für relationale Abbildung von INTERLIS nach SQL, insbesondere für Strukturen, Beziehungen, TID/BID, Geometrien und Mapping-Konventionen

Wichtig: `ili2db` muss im MVP nicht zwingend direkt als Runtime-Engine verwendet werden. Es darf zuerst als fachliche Referenz für Mapping-Regeln dienen. Eine spätere Phase darf prüfen, ob Teile von `ili2db`/`ehisqlgen` direkt genutzt werden können oder ob ein eigener DuckDB-spezifischer Importer sinnvoller ist.

### 2.3 Wichtige technische Leitplanken

- DuckDB-Extension-Binaries sind versionssensitiv. Das Projekt muss DuckDB 1.5.x pinnen und in `README.md` und Scripts sichtbar machen.
- Lokale Entwicklung darf unsigned Extensions laden. Die Scripts müssen DuckDB passend mit `-unsigned` oder äquivalenter Konfiguration starten.
- Die Java/GraalVM-Seite muss eine kleine, stabile C-ABI anbieten. Keine Java-Objekte dürfen über die C-Grenze leaken.
- Keine C++-Exceptions, Java-Exceptions oder GraalVM-Exceptions dürfen unkontrolliert bis DuckDB durchschlagen.
- Native Calls dürfen nicht pro Datenzeile erfolgen, sofern das vermeidbar ist. Dateiweise oder batchweise Verarbeitung ist vorzuziehen.
- Tests müssen ohne Netz funktionieren. Externe XTF-Dateien werden über Script heruntergeladen und in einem ignorierten Cache abgelegt.
- Der MVP ist macOS ARM64. Linux x64, Windows und CI-Matrix sind spätere Phasen.

## 3. Zielbild aus SQL-Sicht

Langfristig sollen etwa folgende SQL-Aufrufe möglich sein.

### 3.1 Versionen und Diagnose

```sql
LOAD ili;

SELECT ili_extension_version();
SELECT ili_native_version();
SELECT ili_runtime_info();
```

Beispielausgabe für `ili_runtime_info()`:

| key | value |
|---|---|
| duckdb_version | 1.5.x |
| extension_version | 0.1.0-dev |
| graalvm_version | 25.0.1 |
| native_lib | libduckdb_ili_native.dylib |
| platform | macos-aarch64 |

### 3.2 Validierung

```sql
SELECT *
FROM ili_validate(
  'testdata/external/ch.so.afu.abbaustellen.xtf',
  modeldir := 'https://models.interlis.ch;https://geo.so.ch/models;testdata/models'
);
```

Zielspalten:

| Spalte | Typ | Bemerkung |
|---|---|---|
| severity | VARCHAR | `ERROR`, `WARNING`, `INFO` |
| code | VARCHAR | optionaler Fehlercode, falls verfügbar |
| message | VARCHAR | Validatormeldung |
| filename | VARCHAR | betroffene Datei |
| line | INTEGER | Zeile, falls verfügbar |
| column | INTEGER | Spalte, falls verfügbar |
| xtf_tid | VARCHAR | Objekt-TID, falls bekannt |
| xtf_bid | VARCHAR | Basket-ID, falls bekannt |
| model | VARCHAR | Modellname, falls ableitbar |
| topic | VARCHAR | Topicname, falls ableitbar |
| class_name | VARCHAR | qualifizierter Klassenname, falls ableitbar |
| attribute_name | VARCHAR | Attribut/Rolle, falls ableitbar |
| raw | VARCHAR | vollständige Rohmeldung oder JSON für Debugging |

Zusammenfassung:

```sql
SELECT *
FROM ili_validate_summary('testdata/external/ch.so.afu.abbaustellen.xtf');
```

Zielspalten:

| Spalte | Typ |
|---|---|
| valid | BOOLEAN |
| error_count | INTEGER |
| warning_count | INTEGER |
| info_count | INTEGER |
| duration_ms | BIGINT |
| validator_version | VARCHAR |

### 3.3 Modellanalyse

```sql
SELECT * FROM ili_models('testdata/models/SO_AGI_Simple_20260605.ili');
SELECT * FROM ili_topics('SO_AGI_Simple_20260605', modeldir := 'testdata/models');
SELECT * FROM ili_classes('SO_AGI_Simple_20260605', modeldir := 'testdata/models');
SELECT * FROM ili_attributes('SO_AGI_Simple_20260605.Topic.Gebaeude', modeldir := 'testdata/models');
SELECT * FROM ili_enumerations('SO_AGI_Simple_20260605', modeldir := 'testdata/models');
```

Ziel: Der Coding-Agent soll die Modellanalyse vor dem komplexen XTF-Import implementieren. Damit kann `read_xtf_class()` später im Bind-Schritt ein DuckDB-Schema aus dem INTERLIS-Modell ableiten.

### 3.4 Generischer XTF-Objektstrom

```sql
SELECT *
FROM read_xtf_objects(
  'testdata/external/ch.so.afu.abbaustellen.xtf',
  modeldir := 'https://models.interlis.ch;https://geo.so.ch/models;testdata/models'
);
```

Zielspalten:

| Spalte | Typ | Bemerkung |
|---|---|---|
| xtf_bid | VARCHAR | Basket-ID |
| xtf_topic | VARCHAR | Topic |
| xtf_class | VARCHAR | qualifizierter Klassenname |
| xtf_tid | VARCHAR | TID, falls vorhanden |
| operation | VARCHAR | `INSERT`, `UPDATE`, `DELETE` oder leer |
| attributes_json | VARCHAR | flache/nestende Attributwerte als JSON |
| refs_json | VARCHAR | Rollen/Referenzen als JSON |
| geom_json | VARCHAR | Geometrien zunächst als JSON/WKT/WKB-Info; genaue Form offen |
| raw_event_json | VARCHAR | Debug-/Fallback-Repräsentation |

Diese Funktion ist bewusst generisch. Sie soll alle Objekte liefern, ohne eine perfekte relationale Projektion zu erzwingen.

### 3.5 Konkrete Klasse als Tabelle

```sql
SELECT *
FROM read_xtf_class(
  'testdata/external/ch.so.afu.abbaustellen.xtf',
  class := 'SO_AFU_Abbaustellen_20230426.Abbaustellen.Abbaustelle',
  modeldir := 'https://models.interlis.ch;https://geo.so.ch/models;testdata/models',
  nested := 'json'
);
```

Mapping-Regeln für die erste funktionierende Version:

| INTERLIS-Konstrukt | Erste Umsetzung | Spätere Umsetzung |
|---|---|---|
| einfache Attribute | DuckDB-Spalten | typisierte DuckDB-Spalten |
| `MANDATORY` | in Metadaten sichtbar, Spalte trotzdem nullable | optional NOT-NULL-Checks in Importphase |
| `STRUCTURE` | JSON-Spalte | `STRUCT` |
| `BAG OF STRUCTURE` | JSON-Spalte | `LIST<STRUCT>` oder eigene Struktur-Table-Function |
| `REFERENCE TO` | `rollenname_ref` als VARCHAR | plus Join-Metadaten |
| ASSOCIATION | separate Funktion | relationale Tabelle mit Rollen und Attributen |
| Geometrie | zunächst WKT oder WKB als BLOB/VARCHAR | DuckDB Spatial `GEOMETRY` optional |
| Vererbung | konkrete Klasse zuerst | Modi `concrete`, `super`, `polymorphic` |

### 3.6 Beziehungen und Associationen

```sql
SELECT *
FROM read_xtf_association(
  'testdata/external/ch.so.afu.abbaustellen.relational.xtf',
  association := 'Model.Topic.AssociationName',
  modeldir := 'https://models.interlis.ch;https://geo.so.ch/models;testdata/models'
);
```

Zielspalten für eine Association:

| Spalte | Typ |
|---|---|
| xtf_tid | VARCHAR |
| xtf_bid | VARCHAR |
| role1_ref | VARCHAR |
| role2_ref | VARCHAR |
| weitere_association_attribute | typisiert oder JSON |
| raw_json | VARCHAR |

### 3.7 Späterer Import in DuckDB-Schema

```sql
CALL ili_import_xtf(
  input := 'testdata/external/2402.ch.so.arp.nutzungsplanung.kommunal.relational.xtf',
  schema := 'ili_np',
  modeldir := 'https://models.interlis.ch;https://geo.so.ch/models;testdata/models',
  mapping := 'relational'
);

SELECT * FROM ili_np.<generated_table> LIMIT 10;
```

Diese Phase soll erst begonnen werden, wenn Validierung, Modellanalyse, generischer Objektstrom und einfache Klassentabellen stabil funktionieren.

## 4. Vorgeschlagene Repository-Struktur

```text
duckdb-ili/
├── README.md
├── LICENSE
├── settings.gradle
├── build.gradle
├── gradle.properties
├── gradle/
│   └── wrapper/
│
├── java/
│   ├── ili-core/
│   │   ├── build.gradle
│   │   └── src/
│   │       ├── main/java/ch/so/agi/duckdbili/core/
│   │       └── test/java/ch/so/agi/duckdbili/core/
│   │
│   └── ili-native/
│       ├── build.gradle
│       └── src/
│           ├── main/java/ch/so/agi/duckdbili/nativeapi/
│           ├── main/resources/META-INF/native-image/ch.so.agi/duckdb-ili-native/
│           └── test/java/ch/so/agi/duckdbili/nativeapi/
│
├── duckdb-extension/
│   ├── README.md
│   ├── CMakeLists.txt
│   ├── extension_config.cmake
│   ├── src/
│   │   ├── ili_extension.cpp
│   │   ├── ili_extension.hpp
│   │   ├── native_bridge.cpp
│   │   ├── native_bridge.hpp
│   │   ├── functions/
│   │   │   ├── version_functions.cpp
│   │   │   ├── validate_functions.cpp
│   │   │   ├── model_functions.cpp
│   │   │   └── xtf_functions.cpp
│   │   └── util/
│   │       ├── error_handling.cpp
│   │       └── json_or_tsv.cpp
│   └── test/
│       ├── sql/
│       └── expected/
│
├── scripts/
│   ├── env.example.sh
│   ├── doctor.sh
│   ├── build-java.sh
│   ├── build-native.sh
│   ├── build-extension.sh
│   ├── build-all.sh
│   ├── copy-native-to-extension.sh
│   ├── dev-duckdb.sh
│   ├── smoke-test.sh
│   ├── download-testdata.sh
│   └── clean-local.sh
│
├── sql/
│   ├── smoke.sql
│   ├── validate-simple.sql
│   ├── validate-external.sql
│   ├── read-objects.sql
│   ├── read-class.sql
│   └── read-associations.sql
│
├── testdata/
│   ├── synthetic/
│   │   ├── simple/
│   │   │   ├── SO_AGI_Simple_20260605.ili
│   │   │   ├── valid.xtf
│   │   │   └── invalid.xtf
│   │   ├── structures/
│   │   │   ├── SO_AGI_Structures_20260605.ili
│   │   │   ├── valid.xtf
│   │   │   └── invalid.xtf
│   │   └── associations/
│   │       ├── SO_AGI_Associations_20260605.ili
│   │       ├── valid.xtf
│   │       └── invalid.xtf
│   ├── external/
│   │   └── .gitkeep
│   └── README.md
│
├── docs/
│   ├── architecture.md
│   ├── c-abi.md
│   ├── duckdb-functions.md
│   ├── xtf-mapping.md
│   ├── testing.md
│   └── open-questions.md
│
└── .gitignore
```

### 4.1 Gradle-Multiprojekt

`settings.gradle`:

```groovy
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        maven { url = uri('https://jars.interlis.ch') }
        maven { url = uri('https://repo.osgeo.org/repository/release/') }
    }
}

rootProject.name = 'duckdb-ili'
include 'java:ili-core'
include 'java:ili-native'
```

Root `build.gradle` soll nur gemeinsame Konfiguration und Exec-Tasks enthalten. Die eigentliche Java-Logik liegt in `java/ili-core`, die GraalVM-C-API in `java/ili-native`.

## 5. Java/GraalVM Native API

### 5.1 Ziel

Die Native Library ist die fachliche Engine. Sie kapselt:

- Modellkompilierung mit `ili2c`
- Validierung mit `ilivalidator`
- XTF-Lesen mit `iox-ili`
- später Mapping-Hilfen aus `ili2db`/`ehisqlgen`

### 5.2 Paketstruktur

```text
ch.so.agi.duckdbili.core
├── validation/
│   ├── IliValidatorService.java
│   ├── ValidationRequest.java
│   ├── ValidationResult.java
│   └── ValidationMessage.java
├── model/
│   ├── IliModelService.java
│   ├── IliModelInfo.java
│   ├── IliClassInfo.java
│   ├── IliAttributeInfo.java
│   └── IliEnumInfo.java
├── xtf/
│   ├── XtfObjectReader.java
│   ├── XtfObjectRow.java
│   ├── XtfClassReader.java
│   └── XtfAssociationReader.java
└── util/
    ├── JsonSupport.java
    ├── TempFiles.java
    └── ErrorUtil.java

ch.so.agi.duckdbili.nativeapi
├── NativeEntryPoints.java
├── NativeMemory.java
├── NativeResult.java
└── NativeVersion.java
```

### 5.3 C-ABI Design

Für die erste Phase soll die C-ABI bewusst klein sein. Der Agent soll nicht versuchen, komplexe Java-Objekte über C zu transportieren.

Empfohlener Start:

```c
typedef struct ili_result {
    int code;                  // 0 = OK, != 0 = Fehler
    const char* payload;        // JSON/TSV/NDJSON, owned by native library
    const char* error_message;  // optional, owned by native library
} ili_result;

int ili_native_version(graal_isolatethread_t* thread, ili_result* out);
int ili_validate_xtf(graal_isolatethread_t* thread, const char* request_json, ili_result* out);
int ili_model_info(graal_isolatethread_t* thread, const char* request_json, ili_result* out);
int ili_read_xtf_objects(graal_isolatethread_t* thread, const char* request_json, ili_result* out);
void ili_free_result(graal_isolatethread_t* thread, ili_result* result);
```

`request_json` enthält alle Parameter, z.B.:

```json
{
  "input": "testdata/synthetic/simple/valid.xtf",
  "modeldir": "testdata/synthetic/simple;https://models.interlis.ch",
  "config": null,
  "language": "de",
  "max_messages": 100000
}
```

Für Phase 1 darf `payload` JSON sein. Für Phase 3+ soll geprüft werden, ob NDJSON oder ein Callback-basiertes Streaming besser ist.

### 5.4 Spätere Streaming-ABI

Für grosse XTF-Dateien ist ein vollständiger JSON-String im Speicher nicht ideal. Deshalb ist als spätere Erweiterung eine Callback-API vorzusehen:

```c
typedef int (*ili_row_callback)(void* user_data, const char** values, int value_count);

int ili_validate_xtf_stream(
    graal_isolatethread_t* thread,
    const char* request_json,
    ili_row_callback callback,
    void* user_data,
    ili_result* out_summary
);
```

Der Agent soll diese Streaming-Variante erst implementieren, wenn der nicht-streamende MVP stabil ist.

## 6. DuckDB-Extension Design

### 6.1 Extension-Name

Vorschlag:

- Extension-Name: `ili`
- Native Library: `libduckdb_ili_native.dylib`
- DuckDB Extension Binary: `ili.duckdb_extension`

SQL:

```sql
LOAD ili;
```

### 6.2 DuckDB-Funktionen je Phase

#### Phase 0/1

```sql
SELECT ili_extension_version();
SELECT ili_native_version();
```

#### Phase 2

```sql
SELECT ili_validate_summary_json('file.xtf', modeldir := '...');
```

#### Phase 3

```sql
SELECT * FROM ili_validate('file.xtf', modeldir := '...');
SELECT * FROM ili_validate_summary('file.xtf', modeldir := '...');
```

#### Phase 4

```sql
SELECT * FROM ili_models('model.ili');
SELECT * FROM ili_classes('ModelName', modeldir := '...');
SELECT * FROM ili_attributes('Model.Topic.Class', modeldir := '...');
SELECT * FROM ili_enumerations('ModelName', modeldir := '...');
```

#### Phase 5+

```sql
SELECT * FROM read_xtf_objects('file.xtf', modeldir := '...');
SELECT * FROM read_xtf_class('file.xtf', class := 'Model.Topic.Class', modeldir := '...');
SELECT * FROM read_xtf_association('file.xtf', association := 'Model.Topic.Assoc', modeldir := '...');
```

### 6.3 Fehlerverhalten

Alle Funktionen müssen bei fachlichen Fehlern DuckDB-freundlich reagieren:

- Validierungsfehler sind Datenzeilen, keine Extension-Fehler.
- Kaputte Parameter, fehlende Datei oder nicht ladbare Native Library sind Extension-Fehler.
- Java-Exceptions werden in `ili_result.error_message` übersetzt.
- Native Bridge-Fehler müssen eine klare Meldung liefern, z.B. `Could not load libduckdb_ili_native.dylib. Set DUCKDB_ILI_NATIVE_LIB or run scripts/dev-duckdb.sh`.

### 6.4 Native Library Loading

Die DuckDB-Extension sucht die Native Library in dieser Reihenfolge:

1. Environment Variable `DUCKDB_ILI_NATIVE_LIB`
2. Neben der Extension-Datei
3. `build/native/current/`
4. systemweiter Installationspfad, später optional

Die Scripts setzen `DUCKDB_ILI_NATIVE_LIB` explizit.

## 7. Entwicklungs-Scripts

### 7.1 `scripts/env.example.sh`

```bash
#!/usr/bin/env bash
export GRAALVM_HOME="/Library/Java/JavaVirtualMachines/graalvm-jdk-25.0.1/Contents/Home"
export JAVA_HOME="$GRAALVM_HOME"
export DUCKDB_VERSION="1.5.0"
export DUCKDB_CLI="duckdb"
export DUCKDB_ILI_NATIVE_LIB="$PWD/java/ili-native/build/native/libduckdb_ili_native.dylib"
export DUCKDB_ILI_EXTENSION="$PWD/duckdb-extension/build/release/extension/ili/ili.duckdb_extension"
```

### 7.2 `scripts/doctor.sh`

Prüft:

- macOS ARM64
- `JAVA_HOME`
- GraalVM-Version
- `native-image` vorhanden
- Gradle Wrapper ausführbar
- DuckDB CLI vorhanden
- CMake/clang vorhanden
- erwartete Pfade und Environment-Variablen

### 7.3 `scripts/build-java.sh`

```bash
#!/usr/bin/env bash
set -euo pipefail
./gradlew :java:ili-core:test :java:ili-native:test
```

### 7.4 `scripts/build-native.sh`

```bash
#!/usr/bin/env bash
set -euo pipefail
./gradlew :java:ili-native:nativeSharedLibrary
```

### 7.5 `scripts/build-extension.sh`

```bash
#!/usr/bin/env bash
set -euo pipefail
# Variante hängt vom gewählten DuckDB-Extension-Template ab.
# Ziel: build/release/extension/ili/ili.duckdb_extension erzeugen.
cmake -S duckdb-extension -B duckdb-extension/build/release -DCMAKE_BUILD_TYPE=Release
cmake --build duckdb-extension/build/release --target ili_extension -j
```

Wenn das offizielle DuckDB-Extension-Template verwendet wird, darf dieses Script intern `make release` oder die Template-eigenen Targets verwenden. Das Script ist die stabile Entwickler-Schnittstelle.

### 7.6 `scripts/build-all.sh`

```bash
#!/usr/bin/env bash
set -euo pipefail
scripts/doctor.sh
scripts/build-java.sh
scripts/build-native.sh
scripts/build-extension.sh
scripts/copy-native-to-extension.sh
```

### 7.7 `scripts/dev-duckdb.sh`

Startet DuckDB so, dass man sofort manuell testen kann:

```bash
#!/usr/bin/env bash
set -euo pipefail
source scripts/env.sh
exec "$DUCKDB_CLI" -unsigned -cmd "LOAD '$DUCKDB_ILI_EXTENSION';" "$@"
```

Manuelle Nutzung:

```bash
scripts/build-all.sh
scripts/dev-duckdb.sh
```

Dann in DuckDB:

```sql
SELECT ili_extension_version();
SELECT ili_native_version();
.read sql/smoke.sql
```

### 7.8 `scripts/smoke-test.sh`

```bash
#!/usr/bin/env bash
set -euo pipefail
source scripts/env.sh
"$DUCKDB_CLI" -unsigned < sql/smoke.sql
```

`sql/smoke.sql`:

```sql
LOAD '$DUCKDB_ILI_EXTENSION';
SELECT ili_extension_version();
SELECT ili_native_version();
SELECT * FROM ili_validate_summary('testdata/synthetic/simple/valid.xtf', modeldir := 'testdata/synthetic/simple');
SELECT * FROM ili_validate('testdata/synthetic/simple/invalid.xtf', modeldir := 'testdata/synthetic/simple');
```

### 7.9 `scripts/download-testdata.sh`

Lädt externe Daten in `testdata/external/` und entpackt sie. Die Dateien werden nicht eingecheckt.

```bash
#!/usr/bin/env bash
set -euo pipefail
mkdir -p testdata/external
cd testdata/external

curl -L -o ch.so.afu.abbaustellen.xtf.zip \
  'https://files.geo.so.ch/ch.so.afu.abbaustellen/aktuell/ch.so.afu.abbaustellen.xtf.zip'
unzip -o ch.so.afu.abbaustellen.xtf.zip -d ch.so.afu.abbaustellen

curl -L -o ch.so.afu.abbaustellen.relational.xtf.zip \
  'https://files.geo.so.ch/ch.so.afu.abbaustellen.relational/aktuell/ch.so.afu.abbaustellen.relational.xtf.zip'
unzip -o ch.so.afu.abbaustellen.relational.xtf.zip -d ch.so.afu.abbaustellen.relational

curl -L -o 2402.ch.so.arp.nutzungsplanung.kommunal.relational.xtf.zip \
  'https://files.geo.so.ch/ch.so.arp.nutzungsplanung.kommunal.relational/aktuell/2402.ch.so.arp.nutzungsplanung.kommunal.relational.xtf.zip'
unzip -o 2402.ch.so.arp.nutzungsplanung.kommunal.relational.xtf.zip -d ch.so.arp.nutzungsplanung.kommunal.relational
```

## 8. Testdatenstrategie

### 8.1 Synthetische Testdaten im Repository

Der Agent soll zuerst synthetische Modelle/Daten erstellen. Diese müssen klein, lesbar und stabil sein.

#### `simple`

Zweck:

- Modell kompilieren
- einfache Klasse lesen
- Validierung valid/invalid
- keine Strukturen, keine Beziehungen

Beispielklassen:

- `Gemeinde`
- `Abbaustelle`

#### `structures`

Zweck:

- `STRUCTURE`
- `BAG OF STRUCTURE`
- Enumeration
- optionales und mandatory Attribut

Beispiel:

- `Adresse` als STRUCTURE
- `Kontakt` als BAG OF STRUCTURE
- Klasse `Betrieb`

#### `associations`

Zweck:

- `REFERENCE TO`
- einfache Association
- attributierte Association
- Join-Tests in SQL

Beispiel:

- `Person`
- `Grundstueck`
- `Besitz` mit Rolle Person/Grundstueck und Attribut `anteil`

### 8.2 Externe Testdaten

Die externen Testdaten sind in aufsteigender Komplexität zu verwenden.

1. `ch.so.afu.abbaustellen.xtf.zip`
   - Ziel: realer, eher einfacher Einstieg.
   - Tests: Validierung, Objektzählung, erste konkrete Klasse.

2. `ch.so.afu.abbaustellen.relational.xtf.zip`
   - Ziel: Beziehungen/Associationen.
   - Tests: `read_xtf_association()`, Ref-Spalten, Join-Beispiele.

3. `2402.ch.so.arp.nutzungsplanung.kommunal.relational.xtf.zip`
   - Ziel: komplexe Nutzungsplanung.
   - Tests: Performance, viele Klassen, Geometrien, komplexe Referenzen, Import-/Mapping-Strategie.

Externe Tests sollen nicht in jedem Unit-Test-Lauf laufen. Es soll dafür ein separates Script geben:

```bash
scripts/download-testdata.sh
scripts/smoke-test-external.sh
```

## 9. Phasenplan

Jede Phase muss mit Tests, Dokumentation und einem manuellen SQL-Beispiel abgeschlossen werden. Der Coding-Agent darf erst zur nächsten Phase gehen, wenn die Definition of Done der aktuellen Phase erfüllt ist.

### Phase 0: Repository-Bootstrap und Toolchain

Ziel: Das Repository baut leer, die Toolchain ist reproduzierbar, und DuckDB kann eine Dummy-Extension laden.

Umfang:

- Gradle-Multiprojekt anlegen.
- `java/ili-core` und `java/ili-native` als leere Module mit Tests anlegen.
- `duckdb-extension` mit minimaler Extension anlegen.
- Scripts `doctor.sh`, `build-all.sh`, `dev-duckdb.sh` anlegen.
- Dummy-Funktion `ili_extension_version()` implementieren.

SQL:

```sql
SELECT ili_extension_version();
```

Definition of Done:

- `scripts/doctor.sh` läuft auf macOS ARM64.
- `scripts/build-all.sh` erzeugt eine DuckDB-Extension.
- `scripts/dev-duckdb.sh` startet DuckDB und lädt die Extension.
- `SELECT ili_extension_version();` liefert einen String.
- README enthält Setup für macOS ARM64.

### Phase 1: Java-Core und GraalVM Native Shared Library

Ziel: Java-Logik ist als native shared library verfügbar und von C/C++ aufrufbar.

Umfang:

- `IliValidatorService` als Java-Service anlegen.
- Noch keine echte Validierung nötig; zuerst `native_version` und Echo-Funktion.
- GraalVM `native-image --shared` über Gradle-Task bauen.
- C-Testprogramm oder DuckDB-Bridge-Test ruft `ili_native_version()` auf.

Definition of Done:

- `./gradlew :java:ili-native:nativeSharedLibrary` erzeugt `libduckdb_ili_native.dylib` und Header.
- Ein kleiner nativer Smoke-Test ruft die Library auf.
- Memory-Freigabe ist getestet.
- Fehlerfälle liefern kontrollierte Fehlermeldungen.

### Phase 2: Erste echte INTERLIS-Validierung in Java

Ziel: Die Java-Seite validiert synthetische XTF-Dateien ohne DuckDB.

Umfang:

- Synthetisches Modell `simple` erstellen.
- `valid.xtf` und `invalid.xtf` erstellen.
- `IliValidatorService.validate(request)` implementieren.
- Resultat als `ValidationResult` mit Liste von `ValidationMessage` zurückgeben.
- JUnit-Tests für gültige und ungültige Datei.

Definition of Done:

- Java-Tests laufen offline.
- Gültige Datei hat `valid = true`.
- Ungültige Datei hat mindestens eine `ERROR`-Meldung.
- Fehlermeldungen enthalten mindestens `severity` und `message`.
- Native API kann dieselbe Validierung als JSON-Payload zurückgeben.

### Phase 3: DuckDB-Scalar-Funktionen für Validierung

Ziel: DuckDB kann die GraalVM-Library laden und eine Validierungszusammenfassung liefern.

Umfang:

- `ili_native_version()` in DuckDB implementieren.
- `ili_validate_summary_json(path, modeldir := ...)` implementieren.
- Native Library Loading über `DUCKDB_ILI_NATIVE_LIB`.
- SQL-Smoke-Test.

SQL:

```sql
SELECT ili_native_version();
SELECT ili_validate_summary_json('testdata/synthetic/simple/valid.xtf', modeldir := 'testdata/synthetic/simple');
```

Definition of Done:

- DuckDB lädt Extension und Native Library.
- Gültige synthetische Datei liefert `valid=true`.
- Ungültige synthetische Datei liefert `valid=false`.
- Fehlende Datei liefert eine klare DuckDB-Fehlermeldung.
- `scripts/smoke-test.sh` läuft ohne manuelle Schritte.

### Phase 4: `ili_validate()` als Table Function

Ziel: Validierungsfehler sind als DuckDB-Tabelle abfragbar.

Umfang:

- Table Function `ili_validate()` implementieren.
- Bind-Parameter: `input`, named parameter `modeldir`, optional `config`, optional `max_messages`.
- Ausgabe gemäss Abschnitt 3.2.
- Erst intern JSON/NDJSON oder TSV verarbeiten; Streaming erst später.

SQL:

```sql
SELECT severity, message, line, column
FROM ili_validate('testdata/synthetic/simple/invalid.xtf', modeldir := 'testdata/synthetic/simple')
WHERE severity = 'ERROR';
```

Definition of Done:

- Tabellenfunktion liefert 0 ERROR-Zeilen für gültige Datei.
- Tabellenfunktion liefert ERROR-Zeilen für ungültige Datei.
- DuckDB-Tests prüfen Spaltennamen und Beispielwerte.
- Manuelles SQL-Beispiel ist in `sql/validate-simple.sql` vorhanden.

### Phase 5: Externe Validierung `abbaustellen`

Ziel: Erste reale XTF-Datei aus files.geo.so.ch validieren.

Umfang:

- `scripts/download-testdata.sh` implementieren.
- `sql/validate-external.sql` implementieren.
- Validierung von `ch.so.afu.abbaustellen.xtf.zip` bzw. entpackter XTF-Datei.
- Robustheit bei ZIP/entpackter Datei klären.

Definition of Done:

- Externe Testdaten können per Script geladen werden.
- Validierung läuft manuell mit DuckDB.
- Wenn Modell-Repositories nötig sind, ist der erwartete `modeldir` dokumentiert.
- Ergebnis ist reproduzierbar dokumentiert, aber nicht als harter Unit-Test erforderlich.

### Phase 6: Modellanalyse-Funktionen

Ziel: INTERLIS-Modelle können aus SQL inspiziert werden.

Umfang:

- `IliModelService` auf Basis von `ili2c`/TransferDescription implementieren.
- DuckDB-Table-Functions:
  - `ili_models()`
  - `ili_topics()`
  - `ili_classes()`
  - `ili_attributes()`
  - `ili_roles()`
  - `ili_enumerations()`
- Zuerst synthetische Modelle abdecken.

Definition of Done:

- Klassen und Attribute aus synthetischem Modell erscheinen in DuckDB.
- Mandatory-Information ist sichtbar.
- Struktur-/Association-Metadaten sind mindestens als `kind`/`type_name` sichtbar.
- Tests prüfen konkrete Modellnamen, Klassennamen, Attribute und Enums.

### Phase 7: Generischer XTF-Objektstrom

Ziel: XTF-Dateien können unabhängig von konkreter Klasse als Objektstrom gelesen werden.

Umfang:

- `XtfObjectReader` mit `iox-ili` implementieren.
- DuckDB-Table-Function `read_xtf_objects()` implementieren.
- Attribute und Referenzen zunächst als JSON-Spalten.
- Baskets, Topics, Klassen, TIDs sauber ausgeben.

SQL:

```sql
SELECT xtf_class, count(*)
FROM read_xtf_objects('testdata/synthetic/simple/valid.xtf', modeldir := 'testdata/synthetic/simple')
GROUP BY xtf_class;
```

Definition of Done:

- Objektanzahl für synthetische Dateien stimmt.
- `xtf_class`, `xtf_tid`, `xtf_bid` sind korrekt.
- Attribute sind als JSON nachvollziehbar.
- Funktion läuft auch auf `ch.so.afu.abbaustellen.xtf` manuell.

### Phase 8: `read_xtf_class()` für einfache Klassen

Ziel: Eine konkrete INTERLIS-Klasse kann als DuckDB-Tabelle gelesen werden.

Umfang:

- Bind-Schritt kompiliert Modell und erzeugt DuckDB-Spalten.
- Nur einfache skalare Attribute im ersten Schritt.
- Technische Spalten: `xtf_bid`, `xtf_tid`, `xtf_class`.
- Nicht unterstützte Attribute zusätzlich in `unsupported_json` sammeln.

SQL:

```sql
SELECT *
FROM read_xtf_class(
  'testdata/synthetic/simple/valid.xtf',
  class := 'SO_AGI_Simple_20260605.Topic.Abbaustelle',
  modeldir := 'testdata/synthetic/simple'
);
```

Definition of Done:

- DuckDB-Schema wird dynamisch aus INTERLIS-Klasse erzeugt.
- Skalare Attribute sind als Spalten sichtbar.
- Nicht unterstützte Attribute führen nicht zum Crash.
- Tests prüfen Spalten und Werte.

### Phase 9: Strukturen und BAG OF STRUCTURE

Ziel: Strukturen werden bewusst und nachvollziehbar abgebildet.

Umfang:

- `STRUCTURE` zuerst als JSON-Spalte.
- `BAG OF STRUCTURE` zuerst als JSON-Array-Spalte.
- Optionaler Modus `nested := 'duckdb'` für `STRUCT`/`LIST<STRUCT>`, falls mit DuckDB-API sauber umsetzbar.
- Zusätzliche Funktion `read_xtf_structures()` prüfen.

SQL:

```sql
SELECT id, adresse_json, kontakte_json
FROM read_xtf_class(
  'testdata/synthetic/structures/valid.xtf',
  class := 'SO_AGI_Structures_20260605.Topic.Betrieb',
  modeldir := 'testdata/synthetic/structures',
  nested := 'json'
);
```

Definition of Done:

- Strukturwerte sind vollständig im JSON enthalten.
- BAG-Reihenfolge bleibt erhalten, soweit fachlich relevant.
- Missing/empty Strukturen sind korrekt als NULL oder `[]` unterscheidbar.
- Tests prüfen Struktur- und BAG-Inhalte.

### Phase 10: Referenzen und Associationen

Ziel: Beziehungen sind abfragbar und joinbar.

Umfang:

- `REFERENCE TO` als `<role_or_attr>_ref` ausgeben.
- `read_xtf_association()` implementieren.
- Attributierte Associationen unterstützen.
- Tests mit synthetischem Association-Modell.
- Manuelle Tests mit `ch.so.afu.abbaustellen.relational.xtf`.

SQL:

```sql
WITH
p AS (
  SELECT * FROM read_xtf_class('testdata/synthetic/associations/valid.xtf', class := 'SO_AGI_Associations_20260605.Topic.Person', modeldir := 'testdata/synthetic/associations')
),
g AS (
  SELECT * FROM read_xtf_class('testdata/synthetic/associations/valid.xtf', class := 'SO_AGI_Associations_20260605.Topic.Grundstueck', modeldir := 'testdata/synthetic/associations')
),
b AS (
  SELECT * FROM read_xtf_association('testdata/synthetic/associations/valid.xtf', association := 'SO_AGI_Associations_20260605.Topic.Besitz', modeldir := 'testdata/synthetic/associations')
)
SELECT p.name, g.nummer, b.anteil
FROM b
JOIN p ON b.person_ref = p.xtf_tid
JOIN g ON b.grundstueck_ref = g.xtf_tid;
```

Definition of Done:

- Referenzspalten enthalten Ziel-TIDs.
- Association-Tabelle enthält Rollen und Attribute.
- Join-Beispiel funktioniert.
- Erste externe relationale Abbaustellen-Datei ist manuell lesbar.

### Phase 11: Geometrien

Ziel: Geometrien werden für DuckDB sinnvoll verfügbar.

Umfang:

- Zuerst WKB als `BLOB` oder WKT als `VARCHAR`.
- Optional DuckDB Spatial Integration prüfen.
- LV95/LV03-SRID-Metadaten als separate Spalte oder Metainfo ausgeben.
- Mehrere Geometrieattribute pro Klasse unterstützen.

Definition of Done:

- Synthetische Punkt-/Linien-/Flächengeometrien lesbar.
- Mindestens ein reales Geometriedataset liefert Geometriespalten.
- Falls DuckDB Spatial verwendet wird, ist `INSTALL spatial; LOAD spatial;` dokumentiert.

### Phase 12: Relationaler Import in DuckDB

Ziel: Ganze XTF-Dateien können in ein DuckDB-Schema materialisiert werden.

Umfang:

- `CALL ili_import_xtf(...)` implementieren.
- Tabellen je Klasse/Association erzeugen.
- Strukturen entweder nested, JSON oder eigene Tabellen, konfigurierbar.
- Mapping-Konventionen mit ili2db vergleichen.
- Nutzungsplanung als anspruchsvoller manueller Test.

SQL:

```sql
CALL ili_import_xtf(
  input := 'testdata/external/ch.so.arp.nutzungsplanung.kommunal.relational/2402....xtf',
  schema := 'np',
  modeldir := 'https://models.interlis.ch;https://geo.so.ch/models;testdata/models',
  mapping := 'relational'
);
```

Definition of Done:

- Import erzeugt mehrere Tabellen.
- Tabellen enthalten erwartete Anzahl Zeilen.
- Referenzen sind joinbar.
- Import bricht bei Validierungsfehlern optional ab oder läuft mit Warnung, je nach Parameter.

### Phase 13: Packaging, CI und Distribution

Ziel: Reproduzierbare Builds und interne Distribution.

Umfang:

- macOS ARM64 Release-Artifact.
- Linux x64 prüfen.
- GitHub/Forgejo/Codeberg Actions evaluieren.
- Interner Extension-Installationspfad dokumentieren.
- Signing/unsigned-Strategie dokumentieren.
- Versionierung: DuckDB-Version, GraalVM-Version, Java-Lib-Version.

Definition of Done:

- Release-Build erzeugt Extension und native Library.
- README zeigt Installation und manuelles Laden.
- CI baut mindestens Java-Tests und optional native macOS/Linux Artefakte.

## 10. Qualitätsanforderungen

### 10.1 Tests

Pflicht:

- JUnit-Tests für Java-Core.
- Native Smoke-Test für GraalVM-Library.
- DuckDB SQL-Smoke-Tests.
- Synthetische Testdaten im Repository.
- Externe Testdaten nur per Script.

Nice-to-have:

- Snapshot-Tests für SQL-Ausgaben.
- Performance-Smoke für grosse XTF-Dateien.
- Fuzz-/Robustheitstests für kaputte XML/XTF-Dateien.

### 10.2 Logging

- Java-Seite soll kontrolliert loggen, aber DuckDB nicht mit stdout/stderr fluten.
- Debugging über Parameter `debug := true` oder Environment Variable `DUCKDB_ILI_DEBUG=1`.
- Validatormeldungen sind Daten, nicht Logs.

### 10.3 Performance

- Kein per-row Starten/Beenden von GraalVM-Isolates.
- Isolate pro DuckDB-Connection oder Extension-Kontext prüfen.
- Für grosse Dateien später Streaming statt vollständigem JSON-Payload.
- Projektion/Filter-Pushdown ist später zu prüfen, aber nicht MVP.

### 10.4 Stabilität

- Native Library muss auch bei invaliden XTF-Dateien stabil bleiben.
- Alle JNI/GraalVM/C-Speicherobjekte müssen eindeutig freigegeben werden.
- DuckDB-Prozess darf bei Java-Exception nicht crashen.
- Fehler müssen reproduzierbare Meldungen enthalten.

## 11. Mapping-Entscheide für XTF nach DuckDB

### 11.1 Technische Spalten

Jede gelesene Fachzeile soll technische Spalten erhalten:

| Spalte | Bedeutung |
|---|---|
| xtf_bid | Basket-ID |
| xtf_tid | Objekt-ID/TID |
| xtf_class | qualifizierter INTERLIS-Klassenname |
| xtf_topic | Topic |
| xtf_operation | Operation, falls vorhanden |

### 11.2 Attributnamen

- Default: INTERLIS-Attributnamen möglichst unverändert übernehmen.
- Optional später: SQL-sichere Normalisierung über Parameter `name_mapping := 'ili' | 'snake_case' | 'ili2db'`.
- Kollisionen müssen deterministisch aufgelöst werden.

### 11.3 Typmapping

Erste Zielabbildung:

| INTERLIS | DuckDB MVP |
|---|---|
| TEXT | VARCHAR |
| BOOLEAN | BOOLEAN |
| NUMERIC | DOUBLE oder DECIMAL, offene Präzisionsfrage |
| Enumeration | VARCHAR |
| Date | DATE, falls eindeutig |
| DateTime | TIMESTAMP, falls eindeutig |
| STRUCTURE | JSON/VARCHAR |
| BAG OF STRUCTURE | JSON/VARCHAR |
| REFERENCE | VARCHAR |
| Geometry | WKT/VARCHAR oder WKB/BLOB |

### 11.4 Vererbung

Initial:

- `read_xtf_class(..., class := 'ConcreteClass')` liest nur konkrete Objekte dieser Klasse.

Später:

- `inheritance := 'exact'`: nur exakte Klasse.
- `inheritance := 'subtypes'`: Klasse plus Subklassen.
- `inheritance := 'super_projection'`: Projektion auf Attribute der Superklasse.

## 12. Anforderungen an den LLM-Coding-Agenten

Der Agent soll strikt iterativ arbeiten.

### 12.1 Arbeitsweise

- Immer kleinste nächste Phase implementieren.
- Vor jeder grösseren Änderung kurz Plan in `docs/dev-notes.md` oder Issue-Text notieren.
- Keine Grossumbauten ohne funktionierenden Zwischenstand.
- Keine Fake-Implementierungen, die nur Tests hartcodieren.
- Jede neue SQL-Funktion braucht mindestens:
  - Dokumentation in `docs/duckdb-functions.md`
  - Beispiel in `sql/`
  - Test oder Smoke-Test
- Bei unklarer API zuerst Spike in separatem kleinen Commit/Branch.

### 12.2 Coding-Standards

Java:

- Java 25-kompatibel, aber keine unnötig exotischen Features im Core.
- Kleine Services, klare DTOs/Records.
- Keine globale mutable Konfiguration ohne Tests.
- Exceptions an der Native-Grenze immer in kontrollierte Resultate übersetzen.

C/C++:

- Native Bridge klein halten.
- Klare Ownership-Regeln für Strings und Resultate.
- Keine unkontrollierten Exceptions über DuckDB-Callbacks.
- DuckDB-Registrierung sauber in einzelne Dateien aufteilen.

Build/Scripts:

- Scripts müssen aus Repo-Root laufen.
- `set -euo pipefail` in Bash-Scripts.
- Scripts dürfen Pfade aus `scripts/env.sh` lesen.
- `scripts/env.sh` ist lokal und ignored; `scripts/env.example.sh` wird eingecheckt.

### 12.3 Akzeptanzregel

Eine Phase ist nicht fertig, wenn nur Code kompiliert. Fertig heisst:

```text
Build läuft.
Tests laufen.
DuckDB kann manuell gestartet werden.
Mindestens ein SQL-Beispiel funktioniert.
README/Doku ist aktualisiert.
Bekannte Einschränkungen sind dokumentiert.
```

## 13. Offene Fragen

Diese Fragen soll der Coding-Agent nicht stillschweigend entscheiden, sondern in `docs/open-questions.md` dokumentieren und, wenn nötig, mit einem kleinen Spike vorbereiten.

1. DuckDB-Extension-Basis:
   - C API Extension Template oder klassisches C++ Extension Template?
   - C API wäre potenziell stabiler und passt gut zur GraalVM-C-ABI.
   - C++ Template ist etablierter für DuckDB-Extensions, aber stärker an DuckDB-Versionen gebunden.

2. Native Result Transport:
   - JSON-String, NDJSON, TSV, temporäre Datei oder Callback-Streaming?
   - MVP darf JSON verwenden.
   - Für grosse Dateien braucht es vermutlich Streaming.

3. GraalVM Isolate-Lifecycle:
   - Ein Isolate pro DuckDB-Prozess?
   - Ein Isolate pro Connection?
   - Thread-Attach/Detach in DuckDB-Table-Functions?

4. Modellrepository und Caching:
   - Wo werden heruntergeladene Modelle gecached?
   - Wie wird Offline-Reproduzierbarkeit sichergestellt?
   - Wie werden mehrere `modeldir`-Einträge behandelt?

5. ZIP-Unterstützung:
   - Soll `ili_validate()` direkt ZIP-Dateien akzeptieren?
   - Oder müssen ZIPs vorher entpackt werden?

6. Geometrieformat:
   - WKT als einfacher Start?
   - WKB als BLOB?
   - DuckDB Spatial `GEOMETRY` als optionaler Modus?
   - Wie werden SRID/LV95/LV03-Metadaten transportiert?

7. Typpräzision:
   - INTERLIS Numeric Types als DECIMAL oder DOUBLE?
   - Wie werden Einheiten und Wertebereiche dokumentiert?

8. Strukturen:
   - JSON zuerst ist robust.
   - DuckDB `STRUCT`/`LIST<STRUCT>` wäre schöner, aber komplexer in der Extension-API.
   - Braucht es zusätzlich relationale Struktur-Tabellen?

9. Associations:
   - Wie exakt soll ili2db-Mapping nachgebildet werden?
   - Wie werden nicht attributierte Associations repräsentiert?
   - Wie werden Rollennamen und Kardinalitäten ausgegeben?

10. Vererbung:
    - Exakte Klasse, Subklassen oder Superklassenprojektion?
    - Wie wird Mehrfachgeometrie in Subklassen behandelt?

11. Verwendung von ili2db:
    - Kann ili2db direkt gegen DuckDB-JDBC arbeiten?
    - Falls ja: lohnt sich das für `ili_import_xtf()`?
    - Falls nein: nur Mapping-Regeln übernehmen?

12. Lizenzierung:
    - Welche Lizenz soll dieses Repository haben?
    - Welche Konsequenzen ergeben sich aus den Lizenzen der verwendeten INTERLIS-Werkzeuge?
    - Ist statisches Linken/Native Image im Zusammenspiel mit LGPL-Komponenten unproblematisch? Das muss juristisch/organisatorisch geklärt werden.

13. Distribution:
    - Nur intern unsigned laden?
    - Später signierte Extension?
    - Eigener Extension-Repository-Mechanismus?

14. Plattformen:
    - macOS ARM64 ist MVP.
    - Wann folgen Linux x64 und Windows?
    - Welche GraalVM-/DuckDB-Kombinationen werden offiziell unterstützt?

## 14. Erste konkrete Aufgaben für den Agenten

1. Repository gemäss Abschnitt 4 anlegen.
2. `scripts/env.example.sh`, `scripts/doctor.sh`, `scripts/build-all.sh`, `scripts/dev-duckdb.sh` erstellen.
3. DuckDB-Dummy-Extension mit `ili_extension_version()` bauen.
4. Gradle-Module `java:ili-core` und `java:ili-native` anlegen.
5. GraalVM-native shared library mit `ili_native_version()` erzeugen.
6. DuckDB-Extension so erweitern, dass `SELECT ili_native_version();` die GraalVM-Library aufruft.
7. Synthetisches `simple`-Modell und zwei XTF-Dateien erstellen.
8. Java-Validierung implementieren und testen.
9. `ili_validate_summary_json()` in DuckDB implementieren.
10. `ili_validate()` als Table Function implementieren.

Erst danach sollen Modellanalyse und XTF-Lesen begonnen werden.

## 15. Referenznotizen

- DuckDB Table Functions werden aus dem `FROM`-Teil einer Query aufgerufen und benötigen Bind-/Init-/Main-Funktionalität.
- DuckDB-Extensions müssen für die verwendete DuckDB-Version passend gebaut werden; Extension-Binaries sind eng an DuckDB-Versionen gekoppelt.
- Für lokale nicht signierte Extensions muss DuckDB passend gestartet/konfiguriert werden.
- GraalVM Native Image Shared Libraries stellen eine C-API bereit, inklusive Isolate-/Thread-Management.
- `ilivalidator` prüft INTERLIS 1/2 Transferdateien gegen das zugehörige Modell.
- `ili2db` ist fachliche Referenz für SQL-Schema-Generierung sowie Import/Export von INTERLIS-Daten in relationale Datenbanken.
