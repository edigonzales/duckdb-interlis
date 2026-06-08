# Implementierungsspezifikation für `duckdb-interlis`

## Robustheitskorrekturen und cursor-basiertes Streaming/Batching für XTF-Lesefunktionen

**Zielgruppe:** LLM-Coding-Agent  
**Repository:** `https://github.com/edigonzales/duckdb-interlis`  
**Zielbranch:** aktueller `main`-Stand  
**Primäre Entwicklungsplattform:** macOS ARM64  
**Weitere Zielplattformen:** Linux x86_64, Linux ARM64, Windows x86_64  
**Java-/Native-Runtime:** GraalVM 25.x  
**DuckDB-Zielversion:** gemäss Repository-Konfiguration, aktuell DuckDB 1.5.3  
**INTERLIS-Abhängigkeiten:** ili2c 5.6.8, ilivalidator 1.15.0, iox-ili 1.24.4  

---

# 1. Zweck und verbindlicher Umfang

Diese Spezifikation beschreibt die nächste Entwicklungsphase von `duckdb-interlis`.

Es sind zwei Arbeitsblöcke umzusetzen:

1. **Noch vorhandene Robustheits- und Korrektheitsmängel beseitigen.**
2. **Cursor-basiertes Streaming und Batching für die XTF-Lesefunktionen implementieren.**

Nicht Bestandteil dieser Spezifikation ist ein eigener Streaming- oder Batching-Umbau von `ilivalidator`.

`ilivalidator` arbeitet bereits intern ereignisbasiert, verwendet Objektpools und führt bei Bedarf einen zweiten Validierungsdurchlauf aus. Die bestehende Funktionalität soll nicht durch eine eigene Validator-Cursor-Architektur ersetzt werden.

Die bestehende Validierungsfunktion darf weiterhin das gesamte Validierungsergebnis materialisieren, sofern:

- sie fachlich korrekt arbeitet,
- technische Fehler hart meldet,
- keine falschen `valid=true`-Resultate erzeugt,
- Speicher und Handles korrekt verwaltet werden.

Das Streaming-/Batching-Ziel gilt primär für:

- `read_xtf_class`
- `read_xtf_association`
- `read_xtf_objects`

Optional und erst nach diesen Funktionen:

- weitere datenintensive XTF-Reader.

Nicht im Fokus stehen:

- Modellmetadatenfunktionen wie `ili_models`, `ili_topics`, `ili_classes`,
- `ili_generate_import_sql`,
- Validierungsresultate.

Diese Resultate sind normalerweise klein und dürfen materialisiert bleiben.

---

# 2. Fachliche Randbedingungen

## 2.1 INTERLIS 2.3 und INTERLIS 2.4

Der Coding-Agent darf **keine eigene XTF-2.3-/XTF-2.4-Parserlogik** implementieren.

Die bestehenden INTERLIS-Libraries abstrahieren das Transferformat. Für das Lesen von XTF sind ausschliesslich die vorhandenen IOX-/INTERLIS-Reader zu verwenden, insbesondere:

```java
ch.interlis.iox.IoxReader
ch.interlis.iox_j.utility.ReaderFactory
ch.interlis.iom_j.xtf.Xtf23Reader
ch.interlis.iom_j.xtf.Xtf24Reader
ch.interlis.iox_j.IoxIliReader
```

Der Anwendungscode darf nicht anhand der XML-Struktur selbst zwischen INTERLIS 2.3 und 2.4 unterscheiden.

Verbindliche Regel:

> Die Formatunterschiede von XTF 2.3 und XTF 2.4 werden vollständig den bestehenden INTERLIS-Libraries überlassen.

## 2.2 Selbst erzeugte INTERLIS-Testmodelle

Jedes vom Coding-Agent neu erzeugte oder geänderte `.ili`-Testmodell muss mit ili2c kompiliert werden.

Bevorzugte Bezugsquelle:

```text
Maven:
ch.interlis:ili2c-tool:5.6.8
ch.interlis:ili2c-core:5.6.8
```

Lokaler Fallback:

```text
/Users/stefan/apps/ili2c-5.6.8/ili2c.jar
```

Ein Testmodell darf nur committed werden, wenn ili2c Exit-Code `0` liefert.

## 2.3 Selbst erzeugte XTF-Testdaten

Jede vom Coding-Agent neu erzeugte oder geänderte XTF-Testdatei muss mit ilivalidator geprüft werden.

Bevorzugte Bezugsquelle:

```text
Maven:
ch.interlis:ilivalidator:1.15.0
```

Lokaler Fallback:

```text
/Users/stefan/apps/ilivalidator-1.15.0/ilivalidator-1.15.0.jar
```

Eine gültige XTF-Datei darf nur committed werden, wenn:

- ilivalidator Exit-Code `0` liefert,
- keine Validierungsfehler vorhanden sind,
- das referenzierte Modell erfolgreich gefunden wird.

Explizite Negativtestdaten dürfen ungültig sein. Dann müssen Dateiname, erwarteter Fehler und Testzweck klar dokumentiert sein.

## 2.4 Keine ungeprüften Testartefakte

Manuell erzeugte XTF-Dateien sind zulässig, aber niemals ungeprüft. Bei komplexeren Testdaten soll bevorzugt ein vorhandener IOX-Writer oder ein bestehendes INTERLIS-Werkzeug verwendet werden.

---

# 3. Zielzustand

Nach Abschluss gelten folgende Eigenschaften:

1. Alle bekannten Invalid-Free- und Memory-Leak-Probleme sind behoben.
2. Alle `duckdb_value`- und `duckdb_get_varchar()`-Ressourcen werden korrekt freigegeben.
3. Technische Validatorfehler können nie als fachlich gültiges Resultat erscheinen.
4. ABI-Handshake, Capability-Prüfung und `struct_size`-Prüfung sind konsistent.
5. Schemafehler bei dynamischen XTF-Tabellenfunktionen führen zu Bind-Fehlern.
6. XTF-Lesefunktionen materialisieren nicht mehr das vollständige Resultat.
7. Java hält pro laufendem DuckDB-Scan einen offenen IOX-Reader-Cursor.
8. DuckDB fordert pro Callback höchstens einen begrenzten Batch an.
9. Früher Query-Abbruch, `LIMIT`, Fehler und normaler Abschluss schliessen den Reader zuverlässig.
10. Bestehende SQL-Signaturen bleiben kompatibel.
11. Bestehende materialisierende Java-Methoden verwenden intern denselben Cursorpfad.
12. Neue INTERLIS-Modelle und XTF-Testdaten werden mit ili2c beziehungsweise ilivalidator geprüft.
13. Sanitizer-Tests führen echte DuckDB-Queries über die C-Extension aus.

---

# 4. Verbindliche Reihenfolge

## Meilenstein A – P0-Robustheitsfixes

1. C-Strukturen vollständig nullinitialisieren.
2. DuckDB-Werte und DuckDB-Strings korrekt freigeben.
3. technische Validatorfehler hart melden.
4. ABI-Handshake härten.
5. `ili_request.struct_size` prüfen.
6. dynamische Schemafehler als Bind-Fehler melden.
7. NULL-Semantik und TSV-Unescaping korrigieren.
8. Import-Identifier und `modeldir` korrigieren.
9. echte Extension-Sanitizer- und Concurrency-Tests.

## Meilenstein B – Java-Cursor-Infrastruktur

1. Cursor-Datenmodell.
2. Cursor-Registry.
3. Row-Schema.
4. Batch-Ergebnis.
5. abstrakte Cursor-Basisklasse.
6. klassenspezifischer XTF-Cursor.
7. Assoziations-Cursor.
8. generischer Objekt-Cursor.

## Meilenstein C – Native-Cursor-ABI

1. neue ABI-Capabilities,
2. Open-/Next-/Close-Einstiegspunkte,
3. Handle- und Ownership-Semantik,
4. Fehlerbehandlung,
5. Tests direkt gegen die Native-Library.

## Meilenstein D – DuckDB-Streaming-Funktionen

1. `read_xtf_class`,
2. `read_xtf_association`,
3. `read_xtf_objects`,
4. Query-Abbruch und Destruktoren,
5. Kompatibilitäts- und Performance-Tests.

## Meilenstein E – Dokumentation und Release-Härtung

Der Coding-Agent darf nicht mit Batching beginnen, bevor Meilenstein A vollständig abgeschlossen und getestet ist.

---

# 5. Meilenstein A – P0-Robustheitsfixes

## 5.1 `mi_bind_data` vollständig initialisieren

### Problem

`mi_bind_data` enthält Pointerfelder, die im Destruktor immer freigegeben werden. Einige Erzeugungsstellen verwenden `malloc()` und initialisieren nicht alle Felder. Das kann zu `free()` auf undefinierten Pointern führen.

### Betroffene Datei

```text
duckdb-extension/src/interlis_extension.c
```

### Verbindliche Änderung

Alle Bind- und Init-Strukturen müssen mit `calloc()` angelegt werden.

Nicht mehr zulässig:

```c
mi_bind_data *bd = malloc(sizeof(mi_bind_data));
```

Zulässig:

```c
mi_bind_data *bd = calloc(1, sizeof(*bd));
if (!bd) {
    duckdb_bind_set_error(info, "Out of memory allocating mi_bind_data");
    return;
}
```

Zu prüfen sind mindestens:

```text
mi_bind_data
mi_init_data
ili_validate_bind_data
ili_validate_init_data
xtf_class_bind_data
xtf_assoc_bind_data
xtf_structures_bind_data
gen_sql_init_data
```

Optional neue Helper:

```c
static void *ili_calloc_or_error_bind(
    duckdb_bind_info info,
    size_t count,
    size_t size,
    const char *what
);

static void *ili_calloc_or_error_init(
    duckdb_init_info info,
    size_t count,
    size_t size,
    const char *what
);
```

### Tests

- alle Modellfunktionen mindestens 100-mal ausführen,
- danach Verbindung zerstören,
- ASan darf keinen Invalid Free melden.

---

## 5.2 DuckDB-String- und Value-Lifecycle korrigieren

### Problem

`duckdb_get_varchar()` liefert einen separat freizugebenden String. Dieser muss mit `duckdb_free()` freigegeben werden. `duckdb_value`-Handles dürfen nicht überschrieben werden, bevor `duckdb_destroy_value()` aufgerufen wurde.

### Neue Dateien

```text
duckdb-extension/src/include/ili_duckdb_utils.h
duckdb-extension/src/ili_duckdb_utils.c
```

### Neue Funktionen

```c
char *ili_bind_copy_parameter_varchar(
    duckdb_bind_info info,
    idx_t parameter_index
);

char *ili_bind_copy_named_varchar(
    duckdb_bind_info info,
    const char *name
);

bool ili_bind_get_named_int32(
    duckdb_bind_info info,
    const char *name,
    int32_t *out_value
);
```

### Semantik

`ili_bind_copy_parameter_varchar()` muss:

1. `duckdb_bind_get_parameter()` aufrufen.
2. NULL-Handle erkennen.
3. `duckdb_is_null_value()` prüfen.
4. `duckdb_get_varchar()` aufrufen.
5. Ergebnis in C-eigenen Speicher kopieren.
6. DuckDB-String mit `duckdb_free()` freigeben.
7. `duckdb_value` mit `duckdb_destroy_value()` zerstören.
8. C-Kopie zurückgeben.

Die C-Kopie gehört dem Caller und wird mit `free()` freigegeben.

### Verbindliche Nutzung

Alle direkten Aufrufe von `duckdb_get_varchar()` in Bind-Funktionen sind durch die Helper zu ersetzen.

### Tests

- NULL-Parameter,
- leere Strings,
- Unicode,
- lange Parameter,
- 10'000 Bind-/Destroy-Zyklen,
- LSan ohne Leaks.

---

## 5.3 Technische Validatorfehler von fachlichen Fehlern trennen

### Betroffene Klassen

```text
IliValidatorService
ValidationResult
NativeEntryPoints
```

### Neue Exceptions

```text
java/ili-core/src/main/java/ch/so/agi/duckdbili/core/validation/ValidationExecutionException.java
java/ili-core/src/main/java/ch/so/agi/duckdbili/core/validation/ValidationOutputException.java
```

`ValidationExecutionException` für:

- Eingabedatei fehlt,
- Temp-Verzeichnis kann nicht erzeugt werden,
- ilivalidator wirft technische Exception,
- Modellauflösung oder Dateizugriff schlägt technisch fehl.

`ValidationOutputException` für:

- erwartetes CSV-Log fehlt,
- CSV-Log kann nicht gelesen werden,
- CSV ist strukturell nicht parsebar,
- Validatorresultat ist widersprüchlich.

### Änderung in `IliValidatorService`

Technische Fehler dürfen nicht als normale `ValidationResult`-ERROR-Zeile zurückgegeben werden.

Nicht mehr zulässig:

```java
catch (IOException e) {
    // non-fatal
}
```

Stattdessen Exception werfen.

### Änderung in `NativeEntryPoints`

```java
catch (ValidationExecutionException e) {
    // IO_ERROR oder MODEL_ERROR
}
catch (ValidationOutputException e) {
    // INTERNAL_ERROR
}
```

Fachlich ungültige Daten bleiben:

```text
NativeStatus.OK
ValidationResult.valid = false
```

### Abnahmetests

1. Datei fehlt → DuckDB-Query schlägt fehl.
2. Temp-Verzeichnis nicht schreibbar → Query schlägt fehl.
3. CSV-Log fehlt → Query schlägt fehl.
4. fachlich ungültige XTF → erfolgreiche Query mit ERROR-Zeilen.
5. gültige XTF → erfolgreiche Query ohne ERROR-Zeilen.

---

## 5.4 Validierungsprofil strikt parsen

### Klasse

```text
ValidationProfile
```

Nur `null` oder blank dürfen auf `FULL` zurückfallen.

Unbekannte Werte:

```java
default -> throw new IllegalArgumentException(
    "Unknown validation profile: " + s
);
```

Der Native Entry Point übersetzt dies zu `INVALID_ARGUMENT`.

---

## 5.5 ABI-Handshake härten

### Betroffene Dateien

```text
duckdb-extension/src/include/ili_request.h
duckdb-extension/src/interlis_extension.c
NativeEntryPoints.java
IliRequest.java
docs/native-abi.md
```

### Anforderungen

- `ili_free_string` vor erster Nutzung prüfen.
- alle registrierten Funktionen mit Capability und Symbol absichern.
- für ABI v1 alle derzeit öffentlichen Funktionen als verpflichtend behandeln oder optionale Funktionen nur bei vorhandener Capability registrieren.
- Request-Pointer und `struct_size` vor Feldzugriff prüfen.

### Neue Klasse

```text
java/ili-native/src/main/java/ch/so/agi/duckdbili/nativeapi/NativeRequestValidator.java
```

```java
public final class NativeRequestValidator {
    public static void requireRequest(
        IliRequest request,
        long minimumStructSize,
        String operation
    );

    public static void requireField(
        String value,
        String fieldName,
        String operation
    );
}
```

Keine duplizierten magischen Offsetzahlen in Entry Points.

### Tests

- Request NULL,
- `struct_size=0`,
- zu kleine Structs,
- passende/falsche ABI,
- fehlende Capability,
- fehlendes Symbol,
- fehlendes `ili_free_string`.

---

## 5.6 Dynamische Schemafehler hart melden

### Betroffene Funktionen

```text
xtf_class_bind
xtf_assoc_bind
```

Wenn der Native-Schemaaufruf fehlschlägt oder ein ungültiges Schema liefert, muss `duckdb_bind_set_error()` aufgerufen werden.

Neue Helper-Funktion:

```c
static void ili_report_bind_error(
    duckdb_bind_info info,
    int status,
    char *payload,
    const char *fallback
);
```

Das Produktions-Fallbackschema ist zu entfernen.

---

## 5.7 TSV-Codec und NULL-Semantik zentralisieren

### Neue Java-Klasse

```text
java/ili-core/src/main/java/ch/so/agi/duckdbili/core/transport/TsvCodec.java
```

```java
public final class TsvCodec {
    public static final String NULL = "\\N";

    public static String encodeNullable(String value);
    public static String encodeRequired(String value);
    public static String encodeNullableInteger(Integer value);
}
```

Regeln:

- `null` → exakt `\N`
- leerer String → leeres Feld
- Backslash, Tab, Newline, Carriage Return escapen
- echter Text `\N` bleibt unterscheidbar

### Neue C-Dateien

```text
duckdb-extension/src/include/ili_tsv.h
duckdb-extension/src/ili_tsv.c
```

```c
typedef struct {
    const char *data;
    size_t length;
    bool is_null;
} ili_tsv_field;
```

```c
bool ili_tsv_next_field(
    const char **cursor,
    const char *end,
    ili_tsv_field *out
);

char *ili_tsv_unescape_copy(
    const ili_tsv_field *field
);

bool ili_tsv_parse_nullable_int32(
    const ili_tsv_field *field,
    int32_t *out_value
);
```

Verbindlich für alle TSV-Pfade nutzen.

---

## 5.8 Import-SQL korrigieren

### Klasse

```text
IliImportService
```

Anforderungen:

1. alle Spaltennamen mit `quoteIdent()` quoten,
2. Tabellen-, Schema- und Spaltenidentifier zentral behandeln,
3. Kollisionen nach Normalisierung erkennen,
4. effektiven `modeldir` durch alle Statements weiterreichen,
5. `null`-`modeldir` darf keine NPE auslösen,
6. Tests mit Schlüsselwörtern und Namenskollisionen.

Optional neue Klasse:

```text
java/ili-core/src/main/java/ch/so/agi/duckdbili/core/sql/SqlIdentifiers.java
```

---

## 5.9 Echte Sanitizer-Tests gegen DuckDB

### Neue Tests

```text
duckdb-extension/test/duckdb_extension_smoke_test.c
duckdb-extension/test/duckdb_extension_concurrency_test.c
```

Der Smoke-Test muss DuckDB öffnen, Extension laden und echte SQL-Queries ausführen.

Mindestqueries:

```sql
SELECT * FROM ili_models(...);
SELECT * FROM ili_topics(...);
SELECT * FROM ili_classes(...);
SELECT * FROM read_xtf_objects(...);
SELECT * FROM read_xtf_class(...);
SELECT * FROM read_xtf_association(...);
SELECT * FROM ili_validate(...);
SELECT * FROM ili_generate_import_sql(...);
```

Sanitizer:

- ASan
- UBSan
- LSan
- optional TSan

Concurrency:

- mindestens 8 Threads,
- eigene Connection pro Thread,
- synchronisierter Start,
- keine Deadlocks oder Race Reports.

---

# 6. Meilenstein B – Java-Cursor-Infrastruktur

## 6.1 Designprinzip

Der IOX-Reader ist bereits ereignisbasiert. Der neue Cursor hält ihn offen und liefert jeweils eine begrenzte Anzahl codierter Zeilen.

Nicht mehr:

```text
gesamte XTF-Datei
→ gesamter StringBuilder
→ gesamter UTF-8-Buffer
```

Sondern:

```text
offener IoxReader
→ Events lesen
→ maximal N Resultatzeilen
→ Batch zurückgeben
→ Reader offen lassen
```

## 6.2 Package-Struktur

```text
java/ili-core/src/main/java/ch/so/agi/duckdbili/core/xtf/cursor/
```

Klassen:

```text
XtfCursor.java
AbstractXtfCursor.java
XtfCursorBatch.java
XtfCursorSchema.java
XtfCursorColumn.java
XtfCursorColumnType.java
XtfClassCursor.java
XtfAssociationCursor.java
XtfObjectCursor.java
XtfCursorException.java
XtfCursorFactory.java
XtfClassSchemaBuilder.java
XtfAssociationSchemaBuilder.java
XtfClassRowEncoder.java
XtfAssociationRowEncoder.java
XtfObjectRowEncoder.java
```

## 6.3 Interface `XtfCursor`

```java
public interface XtfCursor extends AutoCloseable {
    XtfCursorSchema schema();

    XtfCursorBatch nextBatch(
        int maxRows,
        int maxBytes
    );

    boolean isFinished();

    @Override
    void close();
}
```

Regeln:

- `nextBatch()` nach Close wirft definierte Exception.
- nach EOF leeres EOF-Batch.
- `close()` idempotent.
- Fehler nie als EOF maskieren.

## 6.4 `XtfCursorBatch`

```java
public final class XtfCursorBatch {
    private final String payload;
    private final int rowCount;
    private final boolean eof;
}
```

Anforderungen:

- Payload zunächst TSV.
- kein Header im Payload.
- Schema separat.
- `rowCount` stimmt exakt.
- `eof=true` kann mit letztem nichtleeren Batch kombiniert werden.
- `maxBytes` ist weich: einzelne übergrosse Zeile zulässig, danach Batchende.
- ungültige Limits ablehnen.

## 6.5 Schema-Klassen

```java
public final class XtfCursorSchema {
    private final List<XtfCursorColumn> columns;
}
```

```java
public record XtfCursorColumn(
    String name,
    XtfCursorColumnType type,
    boolean nullable
) {}
```

```java
public enum XtfCursorColumnType {
    VARCHAR,
    INTEGER,
    BIGINT,
    DOUBLE,
    BOOLEAN,
    BLOB
}
```

Die erste Version darf alle bestehenden XTF-Ausgabespalten als `VARCHAR` deklarieren.

## 6.6 `AbstractXtfCursor`

Verantwortlichkeiten:

1. `IoxReader` erzeugen.
2. Modell setzen.
3. Reader-Lifecycle.
4. Basket- und Topiczustand.
5. Batch-Limits.
6. EOF.
7. harte Fehlerweitergabe.
8. idempotentes Close.

Felder:

```java
protected final IoxReader reader;
protected final TransferDescription transferDescription;
protected String currentBid;
protected String currentTopic;
protected boolean finished;
protected boolean closed;
```

Optional Metriken:

```java
protected long eventCount;
protected long emittedRowCount;
```

Konstruktor darf Modell kompilieren und Reader öffnen, aber die Datei nicht vollständig lesen.

`nextEvent()` verarbeitet Basketzustand und EndTransfer.

`close()` schliesst genau einmal und bewahrt ursprüngliche Exceptions.

## 6.7 `XtfClassCursor`

Konstruktorparameter:

```java
String xtfPath
String className
String modelDir
String nestedMode
```

Regeln:

- vollständiger Name `Model.Topic.Class`,
- Klasse im Modell vorhanden,
- Objekt-Tag exakt gleich `className`,
- kein `endsWith()`.

Schema über `XtfClassSchemaBuilder`.

Row-Encoding über:

```java
public String encode(
    IomObject object,
    String currentBid
);
```

`nextBatch()` liest Events bis `maxRows`, `maxBytes` oder EOF.

## 6.8 `XtfAssociationCursor`

Analog zum Klassen-Cursor.

Zusätzlich:

- vollständig qualifizierter Assoziationsname,
- Rollenreferenzen deterministisch,
- Assoziationen ohne explizite TID korrekt,
- kein Kurzname als Identität.

## 6.9 `XtfObjectCursor`

Schema:

```text
xtf_bid
xtf_topic
xtf_class
xtf_class_fqn
xtf_tid
operation
xtf_model
attributes_json
refs_json
geom_json
raw_event_json
```

Operationen:

```text
INSERT
UPDATE
DELETE
```

Basket- und Topiczustand muss über Batchgrenzen erhalten bleiben.

## 6.10 `XtfCursorFactory`

```java
public final class XtfCursorFactory {
    public XtfCursor openClassCursor(...);
    public XtfCursor openAssociationCursor(...);
    public XtfCursor openObjectCursor(...);
}
```

Verantwortlichkeiten:

- Modeldir normalisieren,
- Modellcache verwenden,
- `ReaderFactory` verwenden,
- keine eigene XTF-Versionserkennung,
- konsistente Fehler erzeugen.

## 6.11 `XtfObjectReader` als Kompatibilitätsfassade

Bestehende materialisierende Methoden bleiben, nutzen intern aber den Cursor:

```java
try (XtfCursor cursor = factory.open...()) {
    StringBuilder all = new StringBuilder();
    while (!cursor.isFinished()) {
        XtfCursorBatch batch = cursor.nextBatch(4096, 16 * 1024 * 1024);
        all.append(batch.payload());
    }
    return header + all;
}
```

Keine doppelte alte und neue Parsinglogik.

---

# 7. Native Cursor Registry

## 7.1 Package

```text
java/ili-native/src/main/java/ch/so/agi/duckdbili/nativeapi/cursor/
```

Klassen:

```text
NativeCursorRegistry.java
NativeCursorHandle.java
NativeCursorOpenResult.java
NativeCursorBatchResult.java
NativeCursorException.java
```

## 7.2 `NativeCursorRegistry`

Felder:

```java
private final ConcurrentHashMap<Long, NativeCursorHandle> cursors;
private final AtomicLong nextHandle;
```

API:

```java
public long register(XtfCursor cursor);
public NativeCursorHandle require(long handle);
public boolean close(long handle);
public int closeAll();
```

Regeln:

- Handle `0` ungültig.
- keine Wiederverwendung.
- unbekannter Handle → definierte Exception.
- Close idempotent.
- Next und Close atomar koordinieren.

## 7.3 `NativeCursorHandle`

```java
final class NativeCursorHandle {
    final XtfCursor cursor;
    final ReentrantLock lock;
}
```

`next` und `close` verwenden denselben Lock.

Beim kontrollierten Shutdown `closeAll()`.

---

# 8. Native Cursor ABI

## 8.1 ABI-Version

Empfohlen:

```text
ILI_NATIVE_ABI_VERSION = 2
```

Die Extension verlangt nach Umstellung ABI v2.

## 8.2 Capability-Bits

```c
#define ILI_CAP_XTF_CURSOR_OPEN          (1ULL << 12)
#define ILI_CAP_XTF_CURSOR_NEXT          (1ULL << 13)
#define ILI_CAP_XTF_CURSOR_CLOSE         (1ULL << 14)
#define ILI_CAP_XTF_CLASS_CURSOR         (1ULL << 15)
#define ILI_CAP_XTF_ASSOC_CURSOR         (1ULL << 16)
#define ILI_CAP_XTF_OBJECT_CURSOR        (1ULL << 17)
```

## 8.3 Header

Neue Datei:

```text
duckdb-extension/src/include/ili_cursor.h
```

```c
typedef enum ili_cursor_kind {
    ILI_CURSOR_XTF_CLASS = 1,
    ILI_CURSOR_XTF_ASSOCIATION = 2,
    ILI_CURSOR_XTF_OBJECTS = 3
} ili_cursor_kind;
```

```c
typedef struct ili_cursor_open_request {
    uint32_t struct_size;
    uint32_t cursor_kind;
    const char *input;
    const char *modeldir;
    const char *models;
    const char *class_name;
    const char *association;
    const char *nested;
} ili_cursor_open_request;
```

```c
typedef struct ili_cursor_open_result {
    uint32_t struct_size;
    uint64_t handle;
    char *schema_payload;
    size_t schema_payload_length;
} ili_cursor_open_result;
```

```c
typedef struct ili_cursor_next_request {
    uint32_t struct_size;
    uint64_t handle;
    uint32_t max_rows;
    uint32_t max_bytes;
} ili_cursor_next_request;
```

```c
typedef struct ili_cursor_next_result {
    uint32_t struct_size;
    char *payload;
    size_t payload_length;
    uint32_t row_count;
    uint8_t eof;
} ili_cursor_next_result;
```

```c
typedef struct ili_cursor_close_request {
    uint32_t struct_size;
    uint64_t handle;
} ili_cursor_close_request;
```

## 8.4 Ownership

Open:

- Native allokiert `schema_payload`.
- C gibt es mit `ili_free_string()` frei.
- Handle bis Close gültig.

Next:

- Native allokiert `payload`.
- C verarbeitet/kopiert und gibt exakt einmal frei.
- `row_count` und Payload müssen übereinstimmen.
- `eof=1` darf mit letztem Batch kommen.

Close:

- entfernt Handle,
- schliesst Reader,
- idempotent.

Fataler Next-Fehler:

- Cursor automatisch entfernen und schliessen,
- Fehlerpayload liefern,
- Handle danach ungültig.

## 8.5 Entry Points

```java
@CEntryPoint(name = "ili_native_cursor_open")
public static int cursorOpen(...)

@CEntryPoint(name = "ili_native_cursor_next")
public static int cursorNext(...)

@CEntryPoint(name = "ili_native_cursor_close")
public static int cursorClose(...)
```

### `cursorOpen`

- Request prüfen,
- Cursorart prüfen,
- Cursor öffnen,
- registrieren,
- Schema serialisieren,
- bei Fehler Cleanup.

### `cursorNext`

- Request und Handle prüfen,
- Handle-Lock nehmen,
- Batch anfordern,
- Resultat schreiben,
- bei fatalem Fehler Cursor entfernen.

### `cursorClose`

- Handle validieren,
- aus Registry entfernen,
- schliessen,
- idempotent.

## 8.6 Schema-Payload

TSV:

```text
name<TAB>type<TAB>nullable<NEWLINE>
```

Über denselben `TsvCodec`.

---

# 9. DuckDB-C-Streaming-Integration

## 9.1 Bind Data

```c
typedef struct ili_xtf_bind_data {
    char *input;
    char *modeldir;
    char *models;
    char *class_name;
    char *association;
    char *nested;
    ili_cursor_kind cursor_kind;
    char **column_names;
    duckdb_type *column_types;
    bool *column_nullable;
    idx_t column_count;
} ili_xtf_bind_data;
```

## 9.2 Init Data

```c
typedef struct ili_xtf_init_data {
    uint64_t cursor_handle;
    bool cursor_open;
    bool eof;
} ili_xtf_init_data;
```

Keine vollständigen Row-Arrays mehr.

## 9.3 Bind-Phase

- Klasse/Assoziation: reine Schemafunktion nutzen.
- Init öffnet den Cursor.
- Bind darf keinen langfristigen Reader öffnen.
- `read_xtf_objects` besitzt fixes Schema.

## 9.4 Init-Phase

1. Native bereitstellen.
2. Open-Request bauen.
3. Cursor öffnen.
4. Handle speichern.
5. Fehler via `duckdb_init_set_error`.
6. Schema-Payload korrekt freigeben.

## 9.5 Function Callback

1. bei EOF Outputgrösse 0.
2. `cursor_next`.
3. DuckDB-Vector-Size oder Fallback 1024.
4. Default `max_bytes` z. B. 4 MiB.
5. TSV direkt in Vektoren parsen.
6. keine vollständigen Row-Kopien.
7. Payload freigeben.
8. EOF speichern.
9. Outputgrösse setzen.
10. `row_count=0 && eof=false` darf keinen Busy Loop erzeugen.

## 9.6 Destroy

Immer Close aufrufen, wenn Cursor offen:

- normaler Abschluss,
- LIMIT,
- Fehler,
- Connection-Close.

## 9.7 Java-Lock

Der globale Java-Lock bleibt zunächst. Er wird nur während eines einzelnen Open-/Next-/Close-Aufrufs gehalten, niemals über die gesamte Query.

## 9.8 SQL-Kompatibilität

Signaturen bleiben unverändert:

```sql
read_xtf_class(...)
read_xtf_association(...)
read_xtf_objects(...)
```

---

# 10. Fehler- und Abbruchsemantik

## 10.1 Fataler Readerfehler

- Cursor in Fehlerzustand,
- Reader schliessen,
- Registryeintrag entfernen,
- `IO_ERROR`,
- DuckDB-Funktionsfehler.

Bereits ausgegebene Chunks können nicht zurückgenommen werden; dies ist zu dokumentieren.

## 10.2 `LIMIT`

```sql
SELECT * FROM read_xtf_class(...) LIMIT 10;
```

muss den Reader früh schliessen und darf nicht die ganze Datei materialisieren.

## 10.3 Cancellation

Wenn DuckDB Abbruchstatus bereitstellt, prüfen. Sonst Destroy-Callback als Cleanup-Grenze nutzen.

Keine Hintergrundthreads.

---

# 11. Tests

## 11.1 Java Unit Tests

```text
XtfClassCursorTest
XtfAssociationCursorTest
XtfObjectCursorTest
XtfCursorFactoryTest
NativeCursorRegistryTest
TsvCodecTest
```

Mindestfälle:

- 0, 1, genau N und N+1 Zeilen,
- mehrere Batches,
- übergrosse Einzelzeile,
- Unicode,
- Tab/Newline/Backslash,
- NULL vs leer,
- Geometrie,
- Struktur,
- BAG OF,
- Referenz,
- UPDATE/DELETE,
- gleiche Kurzklasse in verschiedenen Topics,
- Close vor Next,
- doppeltes Close,
- Next nach Close,
- EOF,
- Fehler mitten im Transfer,
- paralleles Next auf gleichem Handle.

## 11.2 Native ABI Tests

```text
cursor_open_success
cursor_open_invalid_request
cursor_next_multiple_batches
cursor_next_unknown_handle
cursor_close_idempotent
cursor_error_auto_close
cursor_struct_size
cursor_null_request
```

## 11.3 DuckDB Integration Tests

```text
sql/streaming/read_xtf_class.sql
sql/streaming/read_xtf_association.sql
sql/streaming/read_xtf_objects.sql
sql/streaming/limit.sql
sql/streaming/errors.sql
```

Prüfen:

- gleiche Counts und Inhalte wie bisher,
- LIMIT liest nicht alles,
- Peak Memory niedriger,
- dynamisches Schema korrekt,
- unbekannte Klasse → Bind-Fehler,
- ungültiges XTF → harter Fehler.

## 11.4 Nachweis des frühen Abbruchs

Cursor-Metriken im Testmodus:

```java
long getEventCount();
long getEmittedRowCount();
```

Für `LIMIT 1` muss `eventCount` deutlich kleiner als Gesamtzahl sein.

Alternativ injizierbarer Test-Reader, der nach N Events fehlschlägt.

## 11.5 Performance

Testdaten mit:

- 100,
- 10'000,
- 100'000 Objekten.

Messen:

- Time to first row,
- Gesamtlaufzeit,
- Peak RSS,
- Java Heap,
- maximale Native-Payloadgrösse,
- Anzahl Next-Aufrufe.

Akzeptanz:

- Peak Memory nicht proportional zum Gesamtresultat,
- Payload ungefähr durch `maxBytes` begrenzt,
- erste Zeilen vor Dateiende verfügbar,
- LIMIT deutlich günstiger als COUNT.

---

# 12. Testdaten und Prüfung

## 12.1 Struktur

```text
testdata/streaming/
├── models/
│   └── StreamingTest.ili
├── valid/
│   ├── small.xtf
│   ├── medium.xtf
│   └── large.xtf
├── invalid/
│   ├── truncated.xtf
│   └── malformed-object.xtf
└── scripts/
    ├── generate-testdata.java
    ├── validate-model.sh
    └── validate-xtf.sh
```

## 12.2 Modellinhalt

Mindestens:

- zwei Topics,
- gleiche Klassenkurznamen,
- Text,
- Integer,
- Decimal,
- Boolean,
- Enumeration,
- Struktur,
- BAG OF,
- Referenz,
- Assoziation,
- Geometrie,
- optionale/obligatorische Attribute.

## 12.3 Modellprüfung

`validate-model.sh` Suchreihenfolge:

1. Gradle/Maven-Classpath.
2. `ILI2C_JAR`.
3. `/Users/stefan/apps/ili2c-5.6.8/ili2c.jar`.

Fehler → Exit ungleich 0.

## 12.4 XTF-Prüfung

`validate-xtf.sh` Suchreihenfolge:

1. Gradle/Maven-Classpath.
2. `ILIVALIDATOR_JAR`.
3. `/Users/stefan/apps/ilivalidator-1.15.0/ilivalidator-1.15.0.jar`.

Alle `valid/`-Dateien müssen erfolgreich validieren.

CI darf nicht von lokalen Pfaden abhängen.

---

# 13. Build- und GraalVM-Anforderungen

- `ili_cursor.h` und `ili_request.h` aus einer gemeinsamen Quelle verwenden.
- keine divergierenden Headerkopien.
- Include-Pfad für GraalVM wie bestehend.
- keine unnötige Reflection.
- Registry darf keine Cursor ohne Cleanup halten.
- Tests müssen `closeAll()` ausführen.

---

# 14. Dokumentation

Zu aktualisieren:

```text
docs/architecture.md
docs/native-abi.md
docs/performance.md
docs/limitations.md
docs/error-handling.md
docs/functions.md
docs/troubleshooting.md
README.md
```

Neuer Datenfluss:

```text
DuckDB callback
→ native cursor_next
→ Java XtfCursor.nextBatch
→ IoxReader.read events
→ bounded TSV batch
→ DuckDB vectors
```

Limitationen differenzieren:

- XTF-Lesefunktionen cursorbasiert,
- Validierung materialisiert Resultat,
- Import-SQL materialisiert,
- Modellmetadaten materialisiert.

Dokumentieren:

- Default Batch Rows,
- Default Batch Bytes,
- serialisierte Java-Aufrufe,
- mehrere offene Cursor möglich,
- LIMIT profitiert,
- Import über mehrere Klassen liest Datei weiterhin pro Klasse.

---

# 15. Nicht umsetzen

Nicht erlaubt:

1. `iox-ili` forken.
2. `ilivalidator` forken.
3. eigenen XML-/XTF-Parser schreiben.
4. XTF 2.3/2.4 selbst unterscheiden.
5. Validator-Streaming neu erfinden.
6. columnare Zero-Copy-ABI als ersten Schritt.
7. Hintergrundthreads für Reader.
8. alle Klassen in ein fixes gemeinsames Tabellenschema pressen.
9. Import vollständig neu schreiben.
10. SQL-Signaturen ohne zwingenden Grund brechen.

---

# 16. Definition of Done

## Robustheit

- [ ] kein Invalid Free
- [ ] keine `duckdb_get_varchar`-Leaks
- [ ] alle Values zerstört
- [ ] Validator-I/O-Fehler hart
- [ ] unbekannte Profile abgelehnt
- [ ] ABI vollständig
- [ ] `struct_size` geprüft
- [ ] Bind-Schemafehler sichtbar
- [ ] TSV zentral
- [ ] SQL-NULL korrekt
- [ ] Import-Identifier korrekt

## Cursor

- [ ] `XtfClassCursor`
- [ ] `XtfAssociationCursor`
- [ ] `XtfObjectCursor`
- [ ] idempotentes Close
- [ ] EOF korrekt
- [ ] Registry threadsicher
- [ ] Native Open/Next/Close
- [ ] Capability-Bits
- [ ] DuckDB Init/Function/Destroy

## Tests

- [ ] Java Unit Tests
- [ ] Native ABI Tests
- [ ] DuckDB Integration
- [ ] ASan
- [ ] UBSan
- [ ] LSan
- [ ] echte Concurrency
- [ ] LIMIT-Cleanup
- [ ] grosse synthetische Datei
- [ ] Modelle mit ili2c geprüft
- [ ] XTF mit ilivalidator geprüft

## Dokumentation

- [ ] Architektur
- [ ] ABI
- [ ] Performance
- [ ] Limitationen
- [ ] Funktionen
- [ ] Changelog

---

# 17. Empfohlene Commit-Struktur

```text
fix(c): initialize bind data and free DuckDB values correctly
fix(validation): separate execution errors from validation messages
fix(abi): validate request sizes and required capabilities
fix(tsv): centralize escaping, unescaping and null handling
fix(import): quote identifiers and preserve effective modeldir
test(native): execute DuckDB extension under sanitizers
feat(core): add XTF cursor abstractions
feat(core): implement class cursor
feat(core): implement association cursor
feat(core): implement generic object cursor
feat(native): add cursor registry and ABI entry points
feat(extension): stream read_xtf_class in bounded batches
feat(extension): stream read_xtf_association in bounded batches
feat(extension): stream read_xtf_objects in bounded batches
test(streaming): add validated INTERLIS models and XTF fixtures
docs: document cursor architecture and runtime limits
```

Jeder Commit muss bauen und Tests bestehen.

---

# 18. Abschlussbericht des Coding-Agenten

Der Abschlussbericht muss enthalten:

1. geänderte Dateien,
2. neue Klassen,
3. neue C-Strukturen,
4. ABI-Version und Capabilities,
5. Ownership-Regeln,
6. Testresultate,
7. Sanitizer-Resultate,
8. ili2c-Prüfergebnis der Testmodelle,
9. ilivalidator-Prüfergebnis der gültigen XTF-Dateien,
10. gemessene Speicherverbesserung,
11. bekannte Restlimitationen.

Keine Behauptung wie „streamingfähig“ ohne Testnachweis, dass:

- mehrere Batches geliefert werden,
- `LIMIT` den Reader früh schliesst,
- das vollständige Resultat nicht im Speicher materialisiert wird.
