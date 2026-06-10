# Handlungsanweisungen für den LLM-Coding-Agenten

## Projekt: `duckdb-interlis`

Diese Anweisungen gelten für die Weiterentwicklung des bestehenden Repositories `edigonzales/duckdb-interlis`. Sie ersetzen frühere Vorgaben, die eine DuckDB-C++-Extension oder die interne DuckDB-C++-API voraussetzen.

## 1. Ziel

Die bestehende Extension ist als **DuckDB C Extension** weiterzuentwickeln. Die Extension muss INTERLIS-Modelle und XTF-Daten über die bestehende GraalVM-Native-Library erschliessen und insbesondere Geometrieattribute als echte DuckDB-`GEOMETRY`-Spalten zurückgeben.

Die Zielarchitektur lautet:

```text
DuckDB 1.5.x
    │
    │ DuckDB C Extension API
    ▼
duckdb-extension/                 C
    │
    │ projektspezifische C-ABI
    ▼
java/ili-native/                  GraalVM Native Image Shared Library
    │
    ▼
java/ili-core/                    Java, ili2c, iox-ili, JTS
```

## 2. Verbindlicher Architekturentscheid

1. Die DuckDB-Extension bleibt in **C**.
2. Es darf **keine Migration auf die interne DuckDB-C++-API** erfolgen.
3. Es dürfen keine DuckDB-C++-Klassen oder internen Header verwendet werden.
4. Die Extension verwendet `duckdb_extension.h` und nach Möglichkeit ausschliesslich den versionierten stabilen Teil der DuckDB C Extension API.
5. `DUCKDB_EXTENSION_API_VERSION_UNSTABLE` darf nicht aktiviert werden, solange eine Anforderung mit der stabilen API umgesetzt werden kann.
6. Die erste unterstützte und verbindlich getestete DuckDB-Version bleibt **1.5.3**.
7. Eine behauptete Binärkompatibilität mit weiteren DuckDB-Versionen ist nur zulässig, wenn diese Versionen in CI tatsächlich gebaut und getestet werden.
8. Die Java-/GraalVM-Seite bleibt für INTERLIS-Modellauflösung, XTF-Lesen, Geometriekonvertierung und fachliche Semantik verantwortlich.
9. Die C-Seite bleibt für DuckDB-Binding, DuckDB-Typen, Data Chunks, Vektoren, NULL-Gültigkeit und Fehlerweitergabe verantwortlich.

## 3. Technischer Entscheid zu Geometrien

### 3.1 Rückgabetyp

Klassenbezogene Geometriespalten von `read_xtf_class(...)` sind als

```c
DUCKDB_TYPE_GEOMETRY
```

zu binden. Sie dürfen nicht länger als `VARCHAR` gebunden werden.

Für die erste Umsetzung wird ein **generischer DuckDB-Typ `GEOMETRY` ohne typgebundenes CRS** verwendet.

### 3.2 Transportformat

Die Java-Seite erzeugt bereits WKB in `GeometryValue`. Dieses WKB ist direkt zu verwenden.

Für die erste kompatible Erweiterung der bestehenden String-basierten Native-ABI gilt:

```text
Java GeometryValue.wkb()
        ↓
Hex-kodiertes WKB im typisierten TSV-Protokoll
        ↓
C-seitige Hex-Dekodierung
        ↓
duckdb_vector_assign_string_element_len(...)
        ↓
DuckDB GEOMETRY vector
```

Nicht zulässig ist der aktuelle Umweg:

```text
WKB → WKT → TSV → VARCHAR → späterer CAST
```

Wichtig:

- Für WKB muss immer die längenbasierte Vektorfunktion verwendet werden.
- `duckdb_vector_assign_string_element(...)` ist für WKB verboten, weil binäre Daten Nullbytes enthalten können.
- Ein fehlendes INTERLIS-Geometrieattribut wird zu SQL `NULL`.
- Eine gültige leere Geometrie bleibt eine gültige, nicht-nullige `GEOMETRY`.
- Fehlertexte oder JSON-Fehlerobjekte dürfen nie in einen `GEOMETRY`-Vektor geschrieben werden.
- Eine fehlgeschlagene Geometriekonvertierung muss standardmässig die Abfrage mit einer verständlichen Fehlermeldung abbrechen.

### 3.3 INTERLIS-Abbildung

Die folgende fachliche Abbildung ist beizubehalten und zu testen:

| INTERLIS | DuckDB-/OGC-Geometrie |
|---|---|
| `COORD` | `POINT` |
| `MULTICOORD` | `MULTIPOINT` |
| `POLYLINE` | `LINESTRING` |
| `MULTIPOLYLINE` | `MULTILINESTRING` |
| `SURFACE` | `POLYGON` |
| `MULTISURFACE` | `MULTIPOLYGON` |
| `AREA` | `POLYGON` mit erhaltener INTERLIS-Metainformation |
| `MULTIAREA` | `MULTIPOLYGON` mit erhaltener INTERLIS-Metainformation |

DuckDB `GEOMETRY` kennt keine INTERLIS-Bogensegmente. ARC-Segmente sind deshalb weiterhin auf der Java-Seite kontrolliert zu linearisieren. Die bestehende Voreinstellung `ArcHandlingMode.LINEARIZE` und die konfigurierbare Toleranz sind zu respektieren.

Z-Koordinaten sind gemäss bestehender Option `preserveZ=true` zu erhalten. Verlust von Z darf nicht stillschweigend erfolgen.

### 3.4 CRS-Strategie

Die Table Function liefert vorerst generisches `GEOMETRY`.

Ein CRS-spezifischer DuckDB-Typ wie

```sql
GEOMETRY('EPSG:2056')
```

wird nicht über instabile C-API-Funktionen konstruiert. CRS-Typisierung ist in der Import-Schicht umzusetzen:

- Standardmodus: Zieltabelle verwendet generisches `GEOMETRY`.
- Optionaler CRS-Modus: Das generierte SQL erstellt eine Spalte `GEOMETRY('<AUTH>:<CODE>')` und castet die generische Quellgeometrie explizit auf den Zieltyp.
- Der optionale CRS-Modus darf nur verwendet werden, wenn das CRS in DuckDB bekannt ist, typischerweise nach Laden der `spatial`-Extension.
- Unbekannte oder nicht zuverlässig ermittelte CRS dürfen nicht erfunden werden.
- CRS-Metadaten müssen unabhängig vom DuckDB-Spaltentyp über `ili_geometry_attributes(...)` verfügbar bleiben.

Der Coding-Agent muss beachten, dass `XtfObjectReader` beim klassenbezogenen Lesen derzeit teilweise `GeometryMetadata` mit leeren CRS-Feldern erzeugt. Vor einer CRS-Typisierung ist diese Duplizierung zu beseitigen und dieselbe zentrale Modell-/Geometrieauflösung wie bei `ili_geometry_attributes(...)` zu verwenden.

## 4. Bestehende öffentliche SQL-Oberfläche

Die folgenden Namen sind grundsätzlich beizubehalten:

- `read_xtf_objects(...)`
- `read_xtf_class(...)`
- `read_xtf_structures(...)`
- `read_xtf_association(...)`
- `ili_geometry_attributes(...)`
- `ili_generate_import_sql(...)`
- bestehende Validierungs- und Versionsfunktionen

`read_xtf_objects(...)` darf `geom_json` weiterhin als heterogene JSON-/Textspalte liefern. Die echte `GEOMETRY`-Typisierung betrifft in erster Linie `read_xtf_class(...)`, weil dort pro INTERLIS-Attribut eine stabile Ergebnisspalte existiert.

Eine Änderung bestehender Funktionsnamen oder Parameter ist nur zulässig, wenn sie unvermeidbar ist und die alte Form weiterhin als kompatibler Wrapper erhalten bleibt.

## 5. Native-ABI und typisiertes Transportprotokoll

### 5.1 Rückwärtskompatibilität

Bestehende Native-Symbole und das bisherige Protokoll dürfen nicht ohne Übergang entfernt werden.

Es ist eine neue Capability für typisierte Klassenscans einzuführen, beispielsweise:

```c
ILI_CAP_TYPED_CLASS_SCAN
```

Die C-Extension prüft die Capability beim Initialisieren:

- Capability vorhanden: neuer typisierter Pfad.
- Capability nicht vorhanden: alter `VARCHAR`-Pfad als klar dokumentierter Kompatibilitätsmodus.

### 5.2 Neue Entry Points

Bevorzugt sind neue, explizite Symbole, beispielsweise:

```text
ili_native_read_xtf_class_schema_v2
ili_native_read_xtf_class_v2
```

Die bisherigen Symbole bleiben erhalten.

### 5.3 Schemaformat v2

Die Schemaantwort muss nicht nur Spaltennamen, sondern pro Spalte mindestens folgende Informationen liefern:

```text
name
logical_type
wire_encoding
nullable
geometry_kind
crs_auth_name
crs_code
```

Ein einfaches zeilenorientiertes TSV-Format ist für die erste Version ausreichend, beispielsweise:

```text
xtf_bid    VARCHAR     TEXT       false
xtf_tid    VARCHAR     TEXT       false
lage_geom  GEOMETRY    HEX_WKB    true     POINT    EPSG    2056
name       VARCHAR     TEXT       true
```

Alle Felder müssen mit dem bestehenden `TsvCodec` korrekt escaped werden. Das Format erhält eine eindeutige Versionskennung. Unbekannte Typen oder Encodings führen beim Binding zu einem Fehler und nicht zu einem stillen Fallback.

### 5.4 Datenformat v2

Für die erste Phase gelten folgende Encodings:

| Logischer Typ | Wire Encoding |
|---|---|
| `VARCHAR` | bestehendes TSV-Text-Encoding |
| `GEOMETRY` | uppercase oder lowercase Hex-WKB, konsistent dokumentiert |
| `NULL` | bestehender eindeutiger TSV-NULL-Marker |

Weitere native DuckDB-Typen werden erst in einer späteren Phase aktiviert. Bis dahin dürfen bestehende skalare INTERLIS-Werte weiterhin als `VARCHAR` geliefert und im Import-SQL gecastet werden.

## 6. Änderungen in der C-Extension

Der Coding-Agent soll `interlis_extension.c` nicht weiter zu einer monolithischen Datei ausbauen. Neue Logik ist mindestens wie folgt aufzuteilen:

```text
duckdb-extension/src/
├── interlis_extension.c          Registrierung und Entry Point
├── ili_typed_schema.c            Schema-v2-Parser und Typdeskriptoren
├── ili_typed_scan.c              Bind-, Init- und Function-Callbacks
├── ili_hex.c                     sichere Hex-Dekodierung
├── ili_duckdb_geometry.c         GEOMETRY-Vektorzuweisung
└── ... bestehende Dateien
```

Passende Header gehören nach `duckdb-extension/src/include/`.

### 6.1 Interne Typdeskriptoren

Es ist eine explizite interne Beschreibung einzuführen, beispielsweise:

```c
typedef enum {
    ILI_COLUMN_VARCHAR,
    ILI_COLUMN_GEOMETRY
} ili_column_kind;

typedef enum {
    ILI_WIRE_TEXT,
    ILI_WIRE_HEX_WKB
} ili_wire_encoding;

typedef struct {
    char *name;
    ili_column_kind kind;
    ili_wire_encoding encoding;
    bool nullable;
    char *geometry_kind;
    char *crs_auth_name;
    char *crs_code;
} ili_column_descriptor;
```

Alle Ownership-Regeln sind im Header zu dokumentieren. Jeder Allocationspfad benötigt einen korrekten Destruktor.

### 6.2 Binding

Im Bind-Callback gilt:

- `ILI_COLUMN_VARCHAR` → `DUCKDB_TYPE_VARCHAR`
- `ILI_COLUMN_GEOMETRY` → `DUCKDB_TYPE_GEOMETRY`
- unbekannter Typ → `duckdb_bind_set_error(...)`

Jeder erzeugte `duckdb_logical_type` ist nach `duckdb_bind_add_result_column(...)` korrekt zu zerstören.

### 6.3 Ausführung

Die generische Funktion `mi_function`, die jede Spalte über `ili_tsv_assign_varchar(...)` schreibt, darf nicht für den typisierten Klassenpfad verwendet werden.

Der neue Executor schaltet pro Spalte nach Deskriptor:

- `TEXT`: bestehende Stringzuweisung.
- `HEX_WKB`: streng validieren, dekodieren und mit `duckdb_vector_assign_string_element_len(...)` in den `GEOMETRY`-Vektor schreiben.
- `NULL`: Validity-Bitmap auf ungültig setzen.

Bei Hex-WKB gelten mindestens folgende Prüfungen:

- gerade Anzahl Hex-Zeichen;
- ausschliesslich `[0-9A-Fa-f]`;
- Overflow-Prüfung bei der Berechnung der Binärlänge;
- erfolgreicher Buffer-Allocate;
- keine Verwendung nach `free`;
- Fehler enthält Spaltenname und möglichst XTF-TID;
- keine teilweise gültige Ergebnis-Charge nach einem Fehler.

## 7. Änderungen in Java

### 7.1 `XtfObjectReader`

Für den v2-Pfad:

- Geometrien mit `GeometryValue.hexWkb()` ausgeben, nicht mit `GeometryValue.wkt()`.
- Die Schemafunktion liefert Typ- und Encodinginformationen.
- Geometriefehler nicht als JSON-String in der Geometriespalte ausgeben.
- Modellmetadaten nicht ad hoc mit leeren CRS-Werten nachbauen.
- Bestehende v1-Methoden für Kompatibilität zunächst erhalten.

### 7.2 Zentrale Typauflösung

Die Ermittlung, ob ein Attribut Geometrie ist, und die Ermittlung von Geometrieart, Dimension und CRS dürfen nicht in mehreren Services unterschiedlich implementiert sein.

Es ist eine zentrale, getestete Beschreibung pro INTERLIS-Attribut einzuführen oder wiederzuverwenden. `IliModelService`, `InterlisGeometryTypeResolver`, `XtfObjectReader` und `IliImportService` müssen dieselbe Quelle verwenden.

### 7.3 Native Entry Points

Neue `@CEntryPoint`-Methoden müssen:

- den bestehenden Request-Struct-Validator verwenden;
- Statuscodes und strukturierte Fehler konsistent zurückgeben;
- Speicher mit `UnmanagedMemory` allozieren;
- über `ili_free_string` freigegeben werden können;
- keine Java-Exception über die C-Grenze entweichen lassen;
- in Capability-Handshake und Dokumentation erscheinen.

## 8. Änderungen am Import-SQL

`IliImportService` bildet Geometrieattribute nicht mehr auf `VARCHAR`, sondern standardmässig auf `GEOMETRY` ab.

Der generierte `SELECT` darf eine bereits typisierte Geometriespalte nicht über WKT oder einen unnötigen `VARCHAR`-Cast führen.

Zielbild im Standardmodus:

```sql
CREATE TABLE schema.topic__class (
    xtf_bid VARCHAR,
    xtf_tid VARCHAR,
    lage_geom GEOMETRY
);

INSERT INTO schema.topic__class
SELECT xtf_bid, xtf_tid, lage_geom
FROM read_xtf_class(...);
```

Optionaler CRS-Modus:

```sql
CREATE TABLE schema.topic__class (
    lage_geom GEOMETRY('EPSG:2056')
);

INSERT INTO schema.topic__class
SELECT CAST(lage_geom AS GEOMETRY('EPSG:2056'))
FROM read_xtf_class(...);
```

Das SQL muss weiterhin korrekt quotierte Bezeichner und Literale verwenden. `create`, `replace` und `append` bleiben unterstützt.

## 9. Umsetzung in Phasen

Jede Phase muss einen baubaren und getesteten Stand hinterlassen. Keine Phase darf nur halbfertige Produktionspfade einchecken.

### Phase 0 – Baseline sichern

- Bestehende Java-Tests ausführen.
- Native Library bauen.
- Extension bauen.
- Bestehenden Smoke-Test ausführen.
- Zusätzlichen Regressionstest für den heutigen `read_xtf_class(...)`-Pfad ergänzen.
- Aktuelles Verhalten dokumentieren.

**Abnahmekriterium:** Alle bestehenden Tests sind grün; kein funktionaler Umbau.

### Phase 1 – Typisiertes Schema-v2-Protokoll

- Capability und neue Native Entry Points ergänzen.
- Schema-v2-Deskriptor implementieren.
- Java- und C-seitige Parser-/Codec-Tests ergänzen.
- Noch keine Änderung am öffentlichen Ergebnis von `read_xtf_class(...)`.

**Abnahmekriterium:** C-Seite kann ein Modellschema eindeutig als `VARCHAR` oder `GEOMETRY` erkennen; v1 bleibt funktionsfähig.

### Phase 2 – GEOMETRY-Binding und WKB-Transport

- Geometrieausgabe in Java auf Hex-WKB umstellen.
- C-Hex-Decoder implementieren.
- Geometriespalten als `DUCKDB_TYPE_GEOMETRY` binden.
- Typisierten Executor verwenden.
- NULL, EMPTY, Z und Fehlerfälle behandeln.

**Abnahmekriterium:** `typeof(lage_geom)` ergibt `GEOMETRY`; Werte können ohne vorgängigen WKT-Cast gelesen werden.

### Phase 3 – Import auf echte GEOMETRY-Spalten

- `IliImportService` auf `GEOMETRY` umstellen.
- Unnötige Casts entfernen.
- `create`, `replace` und `append` testen.
- Generisches CRS-Verhalten dokumentieren.

**Abnahmekriterium:** Ein XTF mit Geometrien wird in eine DuckDB-Tabelle mit echter `GEOMETRY`-Spalte importiert.

### Phase 4 – CRS-Konsolidierung

- Zentrale CRS-Ermittlung herstellen.
- CRS-Metadaten für Schweizer Referenzsysteme testen, insbesondere EPSG:2056 und EPSG:21781, sofern das Modell sie eindeutig definiert.
- Optionalen CRS-Importmodus implementieren.
- Verhalten mit und ohne geladene `spatial`-Extension testen.

**Abnahmekriterium:** Kein CRS wird geraten; typgebundenes CRS funktioniert nur bei nachweislich bekanntem CRS.

### Phase 5 – Weitere native DuckDB-Typen

Erst nach stabiler Geometrieunterstützung können folgende Zuordnungen schrittweise aktiviert werden:

| INTERLIS | DuckDB |
|---|---|
| Boolean | `BOOLEAN` |
| ganzzahliger NumericType | `BIGINT` mit Range-Prüfung |
| dezimaler NumericType | zunächst `DOUBLE`, später optional `DECIMAL` |
| Datum | `DATE` |
| Zeit | `TIME` |
| Datum/Zeit | `TIMESTAMP` |
| Text, Enum, OID, Referenz | `VARCHAR` |
| Strukturen und Kompositionen | vorerst JSON in `VARCHAR` |

Jeder Typ benötigt eigene Parsing-, NULL-, Grenzwert- und Fehlertests.

### Phase 6 – Streaming-ABI als separates Vorhaben

Die aktuelle Native-ABI liefert ganze Ergebnisse als einen String und hält sie mehrfach im Speicher. Nach Abschluss der typisierten Geometrieunterstützung ist eine cursor- oder batchbasierte Streaming-ABI zu entwerfen.

Diese Phase darf nicht mit Phase 2 vermischt werden. Zuerst ist der Typvertrag korrekt und getestet herzustellen; danach wird der Transport optimiert.

## 10. Verbindliche Tests

### 10.1 Java-Tests

Mindestens:

- WKB-/Hex-WKB-Roundtrip;
- `POINT`;
- `LINESTRING`;
- `POLYGON` mit Innenring;
- `MULTIPOINT`;
- `MULTILINESTRING`;
- `MULTIPOLYGON`;
- `NULL`;
- leere Geometrie;
- Z-Koordinaten;
- ARC-Linearisierung;
- ungültige oder unvollständige Geometrie;
- Geometrie-Metadaten und CRS-Auflösung.

### 10.2 C-Tests

Mindestens:

- gültiges Hex-WKB in Gross- und Kleinschreibung;
- ungerade Länge;
- ungültiges Hex-Zeichen;
- Nullwert;
- sehr grosse Eingabe mit Overflow-Schutz;
- korrekte Freigabe bei jedem Fehlerpfad;
- Schema-v2 mit unbekanntem Typ/Encoding;
- mehrere Geometriespalten in derselben Zeile;
- gemischte `VARCHAR`-/`GEOMETRY`-Spalten.

Sanitizer-Tests sind für neue C-Module zu erweitern.

### 10.3 DuckDB-Integrationstests

Mindestens folgende Aussagen müssen automatisiert geprüft werden:

```sql
SELECT typeof(lage_geom) FROM read_xtf_class(...);
-- GEOMETRY
```

```sql
SELECT lage_geom::VARCHAR FROM read_xtf_class(...);
-- erwartetes WKT
```

Mit geladener `spatial`-Extension zusätzlich:

```sql
SELECT ST_GeometryType(lage_geom) FROM read_xtf_class(...);
SELECT ST_AsWKB(lage_geom) FROM read_xtf_class(...);
```

Weiter zu testen:

- Import in persistente Tabelle;
- erneutes Lesen nach `CHECKPOINT` und Neustart;
- Z-Erhalt;
- mehrere Geometriearten;
- `NULL` und EMPTY;
- fehlerhaftes XTF;
- Modell mit ARC;
- CRS-Modus mit bekanntem und unbekanntem CRS;
- Laden ohne `spatial`, soweit generisches `GEOMETRY` betroffen ist.

## 11. Versions- und Kompatibilitätsregel

DuckDB 1.5 verwendet für `GEOMETRY` eine WKB-basierte Ausführungsrepräsentation. Diese Repräsentation ist nicht als dauerhaft unveränderliche ABI zu behandeln.

Daraus folgen verbindlich:

1. DuckDB 1.5.3 bleibt zunächst gepinnt.
2. Ein Integrationstest muss nachweisen, dass ein über die C-API geschriebener WKB-Wert als korrekte DuckDB-Geometrie gelesen wird.
3. Vor Freigabe einer weiteren DuckDB-Version ist derselbe Test gegen diese Version auszuführen.
4. Die Extension darf nicht allein aufgrund der versionierten C Extension API als automatisch geometrie-binärkompatibel mit beliebigen künftigen DuckDB-Versionen bezeichnet werden.
5. Bei einer künftigen Änderung der DuckDB-Geometrierepräsentation ist zuerst zu prüfen, ob eine neue stabile C-API-Konvertierungsfunktion verfügbar ist.

## 12. Robustheit und Sicherheit

- Keine C- oder Java-Exception über eine ABI-Grenze entweichen lassen.
- Keine stillen Datenverluste.
- Keine stillen NULL-Werte bei Parse- oder Konvertierungsfehlern.
- Jede Allokation benötigt einen klaren Owner und einen Fehlerpfad.
- Längen und Multiplikationen vor Allokationen auf Overflow prüfen.
- Binärdaten niemals mit `strlen` behandeln.
- Keine globalen veränderlichen Zustände ohne bestehende Locks verwenden.
- Die bestehende Serialisierung der Java-Aufrufe bleibt erhalten, bis die Thread-Sicherheit der INTERLIS-Bibliotheken nachweislich geklärt ist.
- Keine unkontrollierten Netzwerkzugriffe in Tests; benötigte Modelle und XTF-Testdaten lokal bereitstellen.
- Bestehende Sicherheitslogik für die eingebettete Native-Library nicht schwächen.

## 13. Performance

Für die erste Geometriephase ist Hex-WKB akzeptiert, obwohl es die Nutzdaten vergrössert. Korrektheit und ABI-Kompatibilität haben Vorrang.

Der Coding-Agent soll dennoch:

- keine WKT-Zwischenrepräsentation erzeugen;
- keine JTS-WKB→WKT→WKB-Roundtrips ausführen;
- pro Chunk arbeiten;
- temporäre Buffer unmittelbar freigeben;
- keine zusätzlichen vollständigen Kopien des Resultsets einführen;
- Benchmarks vor und nach der Änderung dokumentieren.

Streaming und ein binäres Batchprotokoll sind eine spätere, separate Optimierungsphase.

## 14. Toolchain und Befehle

Entwicklungsplattform: macOS ARM64.

| Werkzeug | Pfad/Version |
|---|---|
| GraalVM JDK | `/Users/stefan/.sdkman/candidates/java/25.0.3-graal` |
| `native-image` | `/Users/stefan/.sdkman/candidates/java/25.0.3-graal/bin/native-image` |
| DuckDB CLI | `~/bin/duckdb`, Version 1.5.3 |
| CMake | `~/cmake-4.1.0/CMake.app/Contents/bin/cmake` |

Vor Builds:

```bash
source scripts/env.sh
```

Relevante Befehle:

```bash
scripts/doctor.sh
scripts/build-java.sh
scripts/build-native.sh
scripts/build-extension.sh
scripts/build-all.sh
scripts/smoke-test.sh
```

Nach Änderungen an Geometrie oder ABI sind mindestens auszuführen:

```bash
scripts/build-java.sh
scripts/build-native.sh
scripts/build-extension.sh
scripts/smoke-test.sh
```

Zusätzlich sind die neuen SQL-/Sanitizer-/Integrationstests auszuführen.

## 15. Arbeitsweise des Coding-Agenten

1. Vor Änderungen die betroffenen Klassen, C-Dateien, ABI-Header und Tests vollständig lesen.
2. Keine Architekturannahme treffen, die nicht im aktuellen Repository überprüft wurde.
3. Kleine, logisch geschlossene Änderungen durchführen.
4. Nach jeder Phase bauen und testen.
5. Bestehende öffentliche Funktionen nicht beiläufig umbenennen oder entfernen.
6. Bei einem fehlenden stabilen C-API-Feature zuerst einen Lösungsweg über generische DuckDB-Typen oder SQL prüfen.
7. Die instabile C API oder die interne C++-API nur nach dokumentiertem Nachweis verwenden, dass die stabile C API die konkrete Anforderung nicht erfüllen kann.
8. Keine grossflächige Refaktorierung gleichzeitig mit der ersten GEOMETRY-Umstellung durchführen.
9. Dokumentation, Beispiele und Limitationshinweise im selben Änderungssatz aktualisieren.
10. Am Ende jeder Phase eine kurze Zusammenfassung liefern: geänderte Dateien, Architekturentscheid, Tests, bekannte Restrisiken.

## 16. Definition of Done für echte GEOMETRY-Unterstützung

Die Umsetzung ist erst abgeschlossen, wenn alle folgenden Punkte erfüllt sind:

- `read_xtf_class(...)` bindet Geometrieattribute als DuckDB `GEOMETRY`.
- Java liefert WKB-basiert und nicht über WKT.
- C dekodiert und schreibt Binärwerte längensicher.
- `NULL`, EMPTY und Z sind korrekt unterschieden.
- ARC-Verhalten ist dokumentiert und getestet.
- Importtabellen verwenden echte `GEOMETRY`-Spalten.
- CRS wird nicht geraten und ist als separate Strategie dokumentiert.
- Bestehende v1-Native-Symbole bleiben während der Migration funktionsfähig.
- Java-, C-, Sanitizer- und DuckDB-Integrationstests sind grün.
- DuckDB 1.5.3 ist als getestete Zielversion dokumentiert.
- README, Funktionsdokumentation, Architektur- und Limitationsdokumente entsprechen dem implementierten Verhalten.
