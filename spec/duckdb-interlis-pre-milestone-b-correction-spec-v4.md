# Korrekturspezifikation für `duckdb-interlis`

## Restliche Robustheits-, Korrektheits- und Testlücken vor Meilenstein B

**Zielgruppe:** LLM-Coding-Agent  
**Repository:** `https://github.com/edigonzales/duckdb-interlis`  
**Basis:** aktueller `main`-Stand zum Zeitpunkt der Analyse  
**Primäres Ziel:** alle noch relevanten Korrektheits- und Robustheitslücken schliessen, bevor Meilenstein B – Java-Cursor-Infrastruktur – umgesetzt wird  
**Nicht Bestandteil:** eigentliche Cursor-/Streaming-Implementierung aus Meilenstein B  

---

# 1. Ausgangslage

Die bisherige Umsetzung ist insgesamt gut und stellt gegenüber dem ursprünglichen Zustand einen deutlichen Fortschritt dar.

Bereits umgesetzt sind insbesondere:

- zentralisierte DuckDB-Parameterextraktion,
- korrektes Freigeben von `duckdb_value`,
- korrektes Freigeben der von `duckdb_get_varchar()` gelieferten Strings,
- weitgehende Nullinitialisierung von Bind- und Init-Strukturen,
- härtere Native-ABI-Prüfung,
- Capability-Maske,
- verpflichtende Symbolprüfung,
- technische Validatorfehler als harte Native-/DuckDB-Fehler,
- dynamische Schemafehler als Bind-Fehler,
- zentrale Java-TSV-Codierung,
- gequotete SQL-Identifier,
- echte DuckDB-Extension-Smoke-Tests,
- Multi-Connection-Concurrency-Tests,
- ASan-/UBSan-/LSan-/TSan-Jobs.

Die Extension ist damit nicht mehr als Proof of Concept einzustufen.

Vor der Umsetzung von Meilenstein B bestehen jedoch noch einige relevante Restprobleme.

Die wichtigsten Probleme sind:

1. `max_messages` kann eine fachlich ungültige Datei als gültig melden.
2. TSV-Escapes werden beim C-zu-DuckDB-Pfad nicht vollständig decodiert.
3. SQL-NULL und leere Werte werden beim Validator vermischt.
4. Einige C-Pfade werten Pointer nach `free()` noch aus.
5. `IliModelService` verarbeitet semikolongetrennte Model-Repositories inkonsistent.
6. Die Windows-Mutexes können mehrfach initialisiert werden.
7. Der Validator-CSV-Parser akzeptiert fehlerhafte oder mehrzeilige CSV-Records nicht robust.
8. Die ABI-Struct-Grösse ist mehrfach hart codiert.
9. Import-Spaltenkollisionen werden nicht erkannt.
10. Tests prüfen viele Pfade nur auf „mindestens eine Zeile“, aber nicht auf korrekte Inhalte und Wiederholbarkeit.

Diese Punkte sind in der hier vorgegebenen Reihenfolge zu bearbeiten.

---

# 2. Verbindliche Arbeitsweise

Der Coding-Agent muss:

1. jeden Abschnitt einzeln umsetzen,
2. nach jedem Abschnitt die betroffenen Tests ausführen,
3. keine grossflächigen Refactorings ausserhalb des beschriebenen Bereichs vornehmen,
4. bestehende SQL-Signaturen nicht verändern,
5. bestehende öffentliche Java-APIs möglichst kompatibel halten,
6. keine eigene XTF-Parserlogik schreiben,
7. keine INTERLIS-Library forken,
8. alle neu erzeugten `.ili`-Dateien mit ili2c prüfen,
9. alle neu erzeugten gültigen `.xtf`-Dateien mit ilivalidator prüfen,
10. am Ende einen Abschlussbericht mit geänderten Dateien und Testresultaten liefern.

---

# 3. Priorität P0 – `max_messages` darf Validität und Gesamtzähler nicht verändern

## 3.1 Problem

Aktuell beendet `IliValidatorService.parseCsv()` das Lesen des Validator-Logs, sobald die gewünschte Zahl an Meldungen erreicht wurde.

Sinngemäss:

```java
if (maxMessages > 0 && messages.size() >= maxMessages) {
    break;
}
```

Anschliessend erzeugt der Code:

```java
new ValidationResult(messages)
```

`ValidationResult` zählt Fehler, Warnungen und Informationen ausschliesslich in der übergebenen, möglicherweise abgeschnittenen Meldungsliste.

Dadurch kann folgende Situation entstehen:

```text
Meldung 1: INFO
Meldung 2: ERROR
Meldung 3: ERROR
max_messages = 1
```

Aktuelles falsches Resultat:

```text
messages.size() = 1
errorCount = 0
valid = true
```

Die Datei ist jedoch fachlich ungültig.

`max_messages` darf ausschliesslich die Zahl der zurückgegebenen Detailmeldungen begrenzen. Es darf niemals verändern:

- `valid`,
- `errorCount`,
- `warningCount`,
- `infoCount`.

## 3.2 Zielverhalten

Der gesamte CSV-Log muss immer gelesen werden.

Für jeden Record sind die Gesamtzähler zu erhöhen.

Nur das Speichern in `messages` wird begrenzt.

Beispiel:

```text
Gesamte CSV:
1 INFO
2 ERROR
3 ERROR

max_messages = 1
```

Erwartetes Resultat:

```text
messages.size() = 1
errorCount = 2
warningCount = 0
infoCount = 1
valid = false
```

## 3.3 Änderung an `ValidationResult`

### Datei

```text
java/ili-core/src/main/java/ch/so/agi/duckdbili/core/validation/ValidationResult.java
```

### Bestehenden Konstruktor beibehalten

Der bestehende Konstruktor soll aus Kompatibilitätsgründen bleiben:

```java
public ValidationResult(List<ValidationMessage> messages)
```

Er darf intern an den neuen Konstruktor delegieren.

### Neuer Konstruktor

```java
public ValidationResult(
        List<ValidationMessage> messages,
        int errorCount,
        int warningCount,
        int infoCount) {

    this.messages = List.copyOf(messages);
    this.errorCount = errorCount;
    this.warningCount = warningCount;
    this.infoCount = infoCount;
    this.valid = errorCount == 0;
}
```

### Delegation des alten Konstruktors

Empfohlene Hilfsmethode:

```java
private record Counts(int errors, int warnings, int infos) {}

private static Counts countMessages(List<ValidationMessage> messages) {
    int errors = 0;
    int warnings = 0;
    int infos = 0;

    for (ValidationMessage message : messages) {
        if (message.getSeverity() == null) {
            continue;
        }

        switch (message.getSeverity().toUpperCase(Locale.ROOT)) {
            case "ERROR" -> errors++;
            case "WARNING" -> warnings++;
            case "INFO" -> infos++;
            default -> {
                // Unbekannte Severity nicht still in eine bekannte Kategorie umdeuten.
            }
        }
    }

    return new Counts(errors, warnings, infos);
}
```

Da Java vor dem Delegationsaufruf keine lokalen Variablen zulässt, ist eine Factory oder ein privater Konstruktor sinnvoll.

Beispiel:

```java
public ValidationResult(List<ValidationMessage> messages) {
    this(messages, countMessages(messages));
}

private ValidationResult(
        List<ValidationMessage> messages,
        Counts counts) {

    this(
        messages,
        counts.errors(),
        counts.warnings(),
        counts.infos()
    );
}
```

## 3.4 Änderung an `IliValidatorService.parseCsv()`

### Datei

```text
java/ili-core/src/main/java/ch/so/agi/duckdbili/core/validation/IliValidatorService.java
```

### Neue Zähler

```java
int totalErrors = 0;
int totalWarnings = 0;
int totalInfos = 0;
```

### Vollständiges Lesen

Der bisherige `break` muss entfernt werden.

Nicht mehr zulässig:

```java
if (maxMessages > 0 && messages.size() >= maxMessages) {
    break;
}
```

Stattdessen:

```java
boolean retainMessage =
        maxMessages <= 0 || messages.size() < maxMessages;
```

Severity zählen:

```java
switch (severity) {
    case "ERROR" -> totalErrors++;
    case "WARNING" -> totalWarnings++;
    case "INFO" -> totalInfos++;
    default -> throw ValidationOutputException.forMalformedCsv(
            csvLog,
            rowIdx,
            "Unknown severity/type: " + type
    );
}
```

Meldung nur bei Bedarf speichern:

```java
if (retainMessage) {
    messages.add(
        new ValidationMessage.Builder()
            // bestehende Felder
            .build()
    );
}
```

Am Ende:

```java
return new ValidationResult(
    messages,
    totalErrors,
    totalWarnings,
    totalInfos
);
```

## 3.5 Erforderliche Tests

### Datei

```text
java/ili-core/src/test/java/ch/so/agi/duckdbili/core/validation/IliValidatorServiceTest.java
```

Neue Tests:

```java
@Test
void maxMessagesDoesNotChangeValidity() {
    Path xtfFile = TESTDATA.resolve("invalid.xtf");
    String modelDir = TESTDATA.toAbsolutePath().toString();

    ValidationResult unlimited =
        service.validate(xtfFile, modelDir, -1, ValidationProfile.FULL);

    ValidationResult limited =
        service.validate(xtfFile, modelDir, 1, ValidationProfile.FULL);

    assertEquals(unlimited.isValid(), limited.isValid());
    assertFalse(limited.isValid());
}
```

```java
@Test
void maxMessagesDoesNotChangeCounts() {
    Path xtfFile = TESTDATA.resolve("invalid.xtf");
    String modelDir = TESTDATA.toAbsolutePath().toString();

    ValidationResult unlimited =
        service.validate(xtfFile, modelDir, -1, ValidationProfile.FULL);

    ValidationResult limited =
        service.validate(xtfFile, modelDir, 1, ValidationProfile.FULL);

    assertEquals(unlimited.getErrorCount(), limited.getErrorCount());
    assertEquals(unlimited.getWarningCount(), limited.getWarningCount());
    assertEquals(unlimited.getInfoCount(), limited.getInfoCount());

    assertTrue(limited.getMessages().size() <= 1);
}
```

Zusätzlicher synthetischer Test mit einer Meldungsreihenfolge:

```text
INFO
ERROR
ERROR
```

Damit explizit geprüft wird, dass ein frühes INFO nicht zu `valid=true` führt.

## 3.6 Definition of Done

- [ ] CSV wird immer vollständig gelesen.
- [ ] `max_messages` begrenzt nur die zurückgegebenen Detailmeldungen.
- [ ] Gesamtzähler bleiben identisch.
- [ ] `valid` bleibt identisch.
- [ ] Regressionstest für INFO vor ERROR vorhanden.

---

# 4. Priorität P0 – TSV-Decodierung im C-Pfad vollständig korrigieren

## 4.1 Problem

Java codiert TSV-Werte korrekt:

```text
\      -> \\
TAB    -> \t
LF     -> \n
CR     -> \r
null   -> \N
```

Die C-Seite erkennt aktuell zwar `\N`, übernimmt normale Felder aber teilweise unverändert in DuckDB.

Sinngemäss:

```c
duckdb_vector_assign_string_element_len(
    vec,
    row,
    field.data,
    field.length
);
```

Damit werden TSV-Escapes nicht decodiert.

Beispiele:

```text
Originalwert:      a<TAB>b
TSV:               a\tb
DuckDB aktuell:    a\tb
DuckDB korrekt:    a<TAB>b
```

Bei JSON ist dies besonders gefährlich, weil JSON-Backslashes durch die TSV-Schicht nochmals escaped werden.

## 4.2 Ziel

Jeder TSV-Stringwert muss vor der Zuweisung an DuckDB genau einmal decodiert werden.

SQL-NULL muss über die DuckDB-Validity-Bitmap dargestellt werden.

## 4.3 Neue zentrale Helper-Funktion

### Datei

```text
duckdb-extension/src/include/ili_tsv.h
```

Ergänzen:

```c
bool ili_tsv_assign_varchar(
    duckdb_vector vector,
    idx_t row,
    const ili_tsv_field *field
);
```

Dafür muss `ili_tsv.h` entweder `duckdb.h` einbinden oder die DuckDB-spezifische Funktion in eine neue Datei verschoben werden.

Architektonisch sauberer ist:

```text
duckdb-extension/src/include/ili_duckdb_tsv.h
duckdb-extension/src/ili_duckdb_tsv.c
```

### Empfohlene Implementierung

```c
#include "ili_duckdb_tsv.h"
#include "ili_tsv.h"

bool ili_tsv_assign_varchar(
        duckdb_vector vector,
        idx_t row,
        const ili_tsv_field *field) {

    if (!vector || !field) {
        return false;
    }

    if (field->is_null) {
        duckdb_vector_ensure_validity_writable(vector);
        uint64_t *validity = duckdb_vector_get_validity(vector);
        duckdb_validity_set_row_invalid(validity, row);
        return true;
    }

    char *decoded = ili_tsv_unescape_copy(field);
    if (!decoded) {
        return false;
    }

    duckdb_vector_assign_string_element(
        vector,
        row,
        decoded
    );

    free(decoded);
    return true;
}
```

## 4.4 `mi_function()` korrigieren

### Datei

```text
duckdb-extension/src/interlis_extension.c
```

Nicht mehr:

```c
if (field.is_null) {
    ...
} else {
    duckdb_vector_assign_string_element_len(
        vec,
        i,
        field.data,
        field.length
    );
}
```

Stattdessen:

```c
if (!ili_tsv_assign_varchar(vec, i, &field)) {
    duckdb_function_set_error(
        tfinfo,
        "Failed to decode TSV field"
    );
    duckdb_data_chunk_set_size(output, 0);
    return;
}
```

Falls die DuckDB-C-API für Table Functions in der verwendeten Version eine anders benannte Fehlerfunktion besitzt, die tatsächlich vorhandene API verwenden.

Wichtig:

- Nicht still abbrechen.
- Nicht leeren String einsetzen.
- Fehler muss als DuckDB-Queryfehler sichtbar sein.

## 4.5 Abschliessendes leeres Feld unterstützen

Aktuell liefert `ili_tsv_next_field()` kein letztes leeres Feld, wenn eine Zeile mit `\t` endet.

Beispiel:

```text
a<TAB>b<TAB>
```

Erwartete Felder:

```text
"a"
"b"
""
```

Der Parser benötigt dafür Zustand.

### Empfohlene API-Änderung

Statt nur Cursor und Endpointer:

```c
typedef struct {
    const char *cursor;
    const char *end;
    bool pending_trailing_empty;
} ili_tsv_reader;
```

Neue API:

```c
void ili_tsv_reader_init(
    ili_tsv_reader *reader,
    const char *data,
    size_t length
);

bool ili_tsv_reader_next(
    ili_tsv_reader *reader,
    ili_tsv_field *out
);
```

Beispielimplementierung:

```c
void ili_tsv_reader_init(
        ili_tsv_reader *reader,
        const char *data,
        size_t length) {

    reader->cursor = data;
    reader->end = data + length;
    reader->pending_trailing_empty = false;
}
```

```c
bool ili_tsv_reader_next(
        ili_tsv_reader *reader,
        ili_tsv_field *out) {

    if (!reader || !out) {
        return false;
    }

    if (reader->pending_trailing_empty) {
        reader->pending_trailing_empty = false;
        out->data = reader->end;
        out->length = 0;
        out->is_null = false;
        return true;
    }

    if (reader->cursor >= reader->end) {
        return false;
    }

    const char *start = reader->cursor;
    const char *p = start;

    while (p < reader->end && *p != '\t') {
        p++;
    }

    out->data = start;
    out->length = (size_t)(p - start);
    out->is_null =
        out->length == 2
        && start[0] == '\\'
        && start[1] == 'N';

    if (p < reader->end && *p == '\t') {
        p++;
        if (p == reader->end) {
            reader->pending_trailing_empty = true;
        }
    }

    reader->cursor = p;
    return true;
}
```

Alternativ darf die bestehende API erweitert werden, solange der abschliessende leere Wert korrekt erkannt wird.

## 4.6 Spaltenzahl strikt prüfen

Für jede TSV-Zeile muss gelten:

```text
Anzahl gelesener Felder == erwartete Spaltenzahl
```

Pseudo-Code:

```c
idx_t field_count = 0;

while (ili_tsv_reader_next(&reader, &field)) {
    if (field_count >= col_count) {
        return error("TSV row has too many columns");
    }

    ...
    field_count++;
}

if (field_count != col_count) {
    return error("TSV row has wrong column count");
}
```

## 4.7 Erforderliche C-Tests

Neue Datei:

```text
duckdb-extension/test/ili_tsv_test.c
```

Testfälle:

```text
plain text
empty field
NULL sentinel
literal \N
backslash
tab
newline
carriage return
Unicode UTF-8
trailing empty field
multiple trailing empty fields
too few columns
too many columns
```

Beispiel:

```c
static void test_literal_backslash_n(void) {
    const char *encoded = "\\\\N";

    ili_tsv_field field = {
        .data = encoded,
        .length = strlen(encoded),
        .is_null = false
    };

    char *decoded = ili_tsv_unescape_copy(&field);

    assert(decoded != NULL);
    assert(strcmp(decoded, "\\N") == 0);

    free(decoded);
}
```

## 4.8 SQL-Integrationstest

Testdaten benötigen mindestens ein Textfeld mit:

- Tab,
- Newline,
- Backslash,
- literalem `\N`,
- Unicode.

Danach:

```sql
SELECT text_attr
FROM read_xtf_class(...);
```

Die Werte müssen exakt mit dem Original übereinstimmen.

## 4.9 Definition of Done

- [ ] alle TSV-Ausgabepfade decodieren Stringwerte,
- [ ] `\N` ergibt SQL-NULL,
- [ ] `\\N` ergibt den echten Text `\N`,
- [ ] abschliessende leere Felder funktionieren,
- [ ] falsche Spaltenzahl erzeugt einen harten Fehler,
- [ ] C-Unit-Tests vorhanden,
- [ ] DuckDB-Integrationstest mit Sonderzeichen vorhanden.

---

# 5. Priorität P0 – Validator-NULL-Semantik korrigieren

## 5.1 Problem

Fehlende Integerwerte werden aktuell als leere Felder geschrieben.

Die C-Seite interpretiert diese teilweise als `0`.

Fehlende Stringwerte werden teilweise als leerer String ausgegeben.

Damit sind nicht unterscheidbar:

```text
NULL
""
0
```

Das ist für eine SQL-Extension fachlich falsch.

## 5.2 `ValidationMessage` erweitern

### Datei

```text
java/ili-core/src/main/java/ch/so/agi/duckdbili/core/validation/ValidationMessage.java
```

Falls noch nicht vorhanden:

```java
private final Integer column;
private final String xtfBid;
```

Builder:

```java
public Builder column(Integer column) {
    this.column = column;
    return this;
}

public Builder xtfBid(String xtfBid) {
    this.xtfBid = xtfBid;
    return this;
}
```

Getter:

```java
public Integer getColumn() {
    return column;
}

public String getXtfBid() {
    return xtfBid;
}
```

Falls die CSV-Ausgabe diese Werte nicht liefert, bleiben sie `null`.

## 5.3 TSV-Erzeugung korrigieren

### Datei

```text
java/ili-native/src/main/java/ch/so/agi/duckdbili/nativeapi/NativeEntryPoints.java
```

Nicht mehr:

```java
tsv.append(msg.getLine() == null ? "" : String.valueOf(msg.getLine()));
tsv.append("").append('\t');
```

Stattdessen:

```java
tsv.append(
    TsvCodec.encodeNullableInteger(msg.getLine())
).append('\t');

tsv.append(
    TsvCodec.encodeNullableInteger(msg.getColumn())
).append('\t');
```

Ebenso:

```java
tsv.append(
    TsvCodec.encodeNullable(msg.getXtfBid())
).append('\t');
```

## 5.4 C-Datenstruktur erweitern

### Datei

```text
duckdb-extension/src/interlis_extension.c
```

```c
typedef struct {
    char *severity;
    char *code;
    char *message;
    char *filename;

    int32_t line;
    bool line_valid;

    int32_t column;
    bool column_valid;

    char *xtf_tid;
    char *xtf_bid;
    char *model;
    char *topic;
    char *class_name;
    char *attribute_name;
    char *raw;
} ili_validate_row;
```

## 5.5 Strikter Integerparser

Die aktuelle Funktion unterscheidet NULL und Parsefehler nicht ausreichend.

Neue API:

```c
typedef enum {
    ILI_TSV_INT_OK = 0,
    ILI_TSV_INT_NULL = 1,
    ILI_TSV_INT_INVALID = 2
} ili_tsv_int_status;
```

```c
ili_tsv_int_status ili_tsv_parse_nullable_int32(
    const ili_tsv_field *field,
    int32_t *out_value
);
```

Semantik:

- `\N` -> `ILI_TSV_INT_NULL`
- gültige Zahl -> `ILI_TSV_INT_OK`
- leerer String -> `ILI_TSV_INT_INVALID`
- Überlauf -> `ILI_TSV_INT_INVALID`
- sonstige Zeichen -> `ILI_TSV_INT_INVALID`

Leerer String darf nicht automatisch `0` bedeuten.

## 5.6 Validator-Parser korrigieren

Pseudo-Code:

```c
ili_tsv_field field;

status = ili_tsv_parse_nullable_int32(&field, &row->line);

switch (status) {
    case ILI_TSV_INT_OK:
        row->line_valid = true;
        break;

    case ILI_TSV_INT_NULL:
        row->line_valid = false;
        break;

    case ILI_TSV_INT_INVALID:
        fail_validation_payload(
            info,
            "Invalid integer in validator TSV column 'line'"
        );
        return;
}
```

Dasselbe für `column`.

## 5.7 DuckDB-Ausgabe korrigieren

```c
int32_t *line_data =
    (int32_t *)duckdb_vector_get_data(line_vec);

if (row->line_valid) {
    line_data[i] = row->line;
} else {
    duckdb_vector_ensure_validity_writable(line_vec);
    uint64_t *validity =
        duckdb_vector_get_validity(line_vec);
    duckdb_validity_set_row_invalid(validity, i);
}
```

Für Strings nicht mehr:

```c
row->xtf_tid ? row->xtf_tid : ""
```

Sondern:

```c
static void assign_nullable_string(
        duckdb_vector vector,
        idx_t row,
        const char *value) {

    if (!value) {
        duckdb_vector_ensure_validity_writable(vector);
        duckdb_validity_set_row_invalid(
            duckdb_vector_get_validity(vector),
            row
        );
        return;
    }

    duckdb_vector_assign_string_element(
        vector,
        row,
        value
    );
}
```

## 5.8 Tests

DuckDB-SQL:

```sql
SELECT
    line IS NULL,
    column IS NULL,
    xtf_tid IS NULL,
    xtf_bid IS NULL
FROM ili_validate(...);
```

Mindestens ein Testrecord ohne diese Werte muss NULL liefern.

Zusätzlich:

```sql
SELECT line = 0
```

darf für einen fehlenden Wert nicht `true` ergeben.

## 5.9 Definition of Done

- [ ] fehlende Integerwerte sind SQL-NULL,
- [ ] fehlende Strings sind SQL-NULL,
- [ ] leere Strings bleiben leere Strings,
- [ ] `0` bleibt echte `0`,
- [ ] Parsefehler werden hart gemeldet.

---

# 6. Priorität P1 – Pointer nach `free()` nicht mehr auswerten

## 6.1 Problem

Mindestens zwei skalare C-Funktionen tun sinngemäss:

```c
free(result);

if (status != 0 || !result) {
    return;
}
```

Der Pointerwert wird nach `free()` noch geprüft.

Auch wenn nur der Pointerwert und nicht der Inhalt gelesen wird, ist dieses Muster unsauber und zu vermeiden.

## 6.2 Betroffene Funktionen

Mindestens:

```text
ili_native_version_fn_cb
ili_validate_summary_json_fn
```

Weitere ähnliche Muster im gesamten C-Code suchen.

Suchmuster:

```text
free(...);
if (... pointer ...)
```

## 6.3 Korrektur

```c
bool success =
    status == 0 && result != NULL;

if (success) {
    duckdb_validity_set_row_valid(validity, row);
    duckdb_vector_assign_string_element(
        output,
        row,
        result
    );
} else {
    duckdb_validity_set_row_invalid(validity, row);

    char *msg = extract_error_message(result);

    duckdb_scalar_function_set_error(
        info,
        msg
            ? msg
            : result
                ? result
                : "Native call failed"
    );

    free(msg);
}

free(result);

if (!success) {
    return;
}
```

Bevorzugt Fehlerzweig mit sofortigem Return:

```c
if (status != 0 || !result) {
    ...
    free(result);
    return;
}

...
free(result);
```

## 6.4 Test

- erfolgreicher Native-Call,
- Native-Call mit Statusfehler,
- Native-Call mit NULL-Payload,
- Native-Call mit Fehlerpayload.

ASan/UBSan müssen sauber bleiben.

---

# 7. Priorität P1 – Gemeinsame Modeldir-Normalisierung

## 7.1 Problem

`XtfObjectReader` und `IliImportService` splitten Modeldirs nach `;`.

`IliModelService` behandelt den gesamten String teilweise als ein einzelnes Repository.

Beispiel:

```text
/local/models;https://models.interlis.ch
```

Darf nicht als ein einzelner Pfad an `IliManager` übergeben werden.

## 7.2 Neue Klasse

### Datei

```text
java/ili-core/src/main/java/ch/so/agi/duckdbili/core/model/ModelRepositoryResolver.java
```

### API

```java
public final class ModelRepositoryResolver {

    private ModelRepositoryResolver() {
    }

    public static List<String> resolve(
        String modelDir,
        String defaultModelDir
    );

    public static String resolveToString(
        String modelDir,
        String defaultModelDir
    );

    public static List<Path> localDirectories(
        String modelDir,
        String defaultModelDir
    );
}
```

### Implementierung

```java
public static List<String> resolve(
        String modelDir,
        String defaultModelDir) {

    String effective =
        modelDir != null && !modelDir.isBlank()
            ? modelDir
            : defaultModelDir;

    LinkedHashSet<String> repositories =
        new LinkedHashSet<>();

    for (String part : effective.split(";")) {
        String trimmed = part.trim();

        if (!trimmed.isBlank()) {
            repositories.add(trimmed);
        }
    }

    if (repositories.isEmpty()) {
        repositories.add(defaultModelDir);
    }

    return List.copyOf(repositories);
}
```

```java
public static String resolveToString(
        String modelDir,
        String defaultModelDir) {

    return String.join(
        ";",
        resolve(modelDir, defaultModelDir)
    );
}
```

```java
public static List<Path> localDirectories(
        String modelDir,
        String defaultModelDir) {

    List<Path> directories = new ArrayList<>();

    for (String repository :
            resolve(modelDir, defaultModelDir)) {

        if (repository.startsWith("http://")
                || repository.startsWith("https://")) {
            continue;
        }

        try {
            Path path = Path.of(repository);

            if (Files.isDirectory(path)) {
                directories.add(path);
            }
        } catch (InvalidPathException ignored) {
            // Nicht als lokales Verzeichnis interpretierbar.
        }
    }

    return List.copyOf(directories);
}
```

## 7.3 `IliModelService` umbauen

Nicht mehr:

```java
manager.setRepositories(
    new String[]{effectiveDir}
);
```

Stattdessen:

```java
List<String> repositories =
    ModelRepositoryResolver.resolve(
        modelDir,
        DEFAULT_MODELDIR
    );

manager.setRepositories(
    repositories.toArray(String[]::new)
);
```

Lokale `.ili`-Dateien:

```java
for (Path directory :
        ModelRepositoryResolver.localDirectories(
            modelDir,
            DEFAULT_MODELDIR
        )) {

    try (DirectoryStream<Path> stream =
            Files.newDirectoryStream(directory, "*.ili")) {

        for (Path file : stream) {
            entries.add(
                file.toAbsolutePath().toString()
            );
        }
    }
}
```

## 7.4 `XtfObjectReader` und `IliImportService`

Die dort vorhandene Normalisierungslogik durch dieselbe Klasse ersetzen.

Es darf nur noch eine Semantik für Modeldirs geben.

## 7.5 Cache-Key

Der Cache-Key muss die normalisierte Form verwenden:

```java
String normalizedModelDir =
    ModelRepositoryResolver.resolveToString(
        modelDir,
        DEFAULT_MODELDIR
    );
```

## 7.6 Tests

```java
@Test
void resolvesSemicolonSeparatedRepositories() {
    assertEquals(
        List.of("/tmp/models", "https://models.interlis.ch"),
        ModelRepositoryResolver.resolve(
            "/tmp/models; https://models.interlis.ch",
            "https://default.example"
        )
    );
}
```

Weitere Fälle:

- `null`,
- leer,
- Leerzeichen,
- doppelte Einträge,
- mehrere lokale Verzeichnisse,
- URL plus lokaler Pfad,
- ungültiger lokaler Pfad,
- Reihenfolge bleibt erhalten.

Integrationstest:

```sql
SELECT *
FROM ili_models(
    'testdata/models;https://models.interlis.ch'
);
```

## 7.7 Definition of Done

- [ ] `IliModelService` unterstützt `;`,
- [ ] alle Services nutzen dieselbe Normalisierung,
- [ ] Cache-Keys sind konsistent,
- [ ] lokale und entfernte Repositories funktionieren gemeinsam.

---

# 8. Priorität P1 – Windows-Mutexes nur einmal initialisieren

## 8.1 Problem

Die globalen Mutexes werden im Extension-Entry-Point initialisiert.

Wird der Entry-Point im selben Prozess mehrmals ausgeführt, können dieselben Windows-`CRITICAL_SECTION`-Objekte mehrfach initialisiert werden.

## 8.2 Lösung mit `INIT_ONCE`

### Datei

```text
duckdb-extension/src/interlis_extension.c
```

Unter `_WIN32`:

```c
static INIT_ONCE g_mutex_init_once =
    INIT_ONCE_STATIC_INIT;
```

Callback:

```c
static BOOL CALLBACK ili_initialize_mutexes_once(
        PINIT_ONCE init_once,
        PVOID parameter,
        PVOID *context) {

    (void)init_once;
    (void)parameter;
    (void)context;

    ili_mutex_init(&g_init_lock);
    ili_mutex_init(&g_java_lock);

    return TRUE;
}
```

Entry-Point:

```c
#ifdef _WIN32
    if (!InitOnceExecuteOnce(
            &g_mutex_init_once,
            ili_initialize_mutexes_once,
            NULL,
            NULL)) {

        return false;
    }
#endif
```

## 8.3 Anforderungen

- keine Mehrfachinitialisierung,
- keine statische Race Condition,
- keine manuelle `bool`-Abfrage ohne Synchronisierung,
- Fehler muss zum Scheitern des Extension-Ladens führen.

## 8.4 Test

Windows-CI-Test:

1. DuckDB öffnen.
2. Extension laden.
3. zweite Datenbank öffnen.
4. Extension erneut laden.
5. mehrere Connections erzeugen.
6. Native-Funktionen aufrufen.

---

# 9. Priorität P1 – Validator-CSV-Parser robust machen

## 9.1 Problem

Der Parser liest physische Zeilen mit:

```java
BufferedReader.readLine()
```

Das ist kein vollständiger CSV-Record-Parser.

Probleme:

- gequotete Felder mit Newline werden getrennt,
- ungeschlossene Quotes werden nicht erkannt,
- zu kurze Records werden übersprungen,
- strukturelle Fehler werden nicht als `ValidationOutputException` gemeldet.

## 9.2 Bevorzugte Lösung

Eine etablierte CSV-Bibliothek verwenden.

Zulässig sind beispielsweise:

- Apache Commons CSV,
- Univocity Parsers.

Bevorzugt ist Apache Commons CSV, sofern die zusätzliche Dependency akzeptabel ist.

### Gradle

```groovy
implementation "org.apache.commons:commons-csv:<aktuelle-kompatible-version>"
```

Keine Versionsangabe blind einsetzen. Repository-Konventionen beachten.

## 9.3 Beispiel mit Apache Commons CSV

```java
try (Reader reader =
        Files.newBufferedReader(
            csvLog,
            StandardCharsets.UTF_8)) {

    CSVFormat format =
        CSVFormat.DEFAULT.builder()
            .setHeader()
            .setSkipHeaderRecord(true)
            .setIgnoreEmptyLines(true)
            .build();

    try (CSVParser parser =
            new CSVParser(reader, format)) {

        for (CSVRecord record : parser) {
            ...
        }
    }
}
```

Falls ilivalidator keine stabilen Headernamen garantiert, positional lesen, aber die Mindestspaltenzahl strikt prüfen.

## 9.4 Keine stille Fehlerbehandlung

Nicht mehr:

```java
if (fields.size() < 2) {
    continue;
}
```

Stattdessen:

```java
if (record.size() < REQUIRED_COLUMNS) {
    throw ValidationOutputException.forMalformedCsv(
        csvLog,
        record.getRecordNumber(),
        "Expected at least "
            + REQUIRED_COLUMNS
            + " columns, got "
            + record.size()
    );
}
```

## 9.5 Neue Factory in `ValidationOutputException`

```java
public static ValidationOutputException forMalformedCsv(
        Path csvLog,
        long recordNumber,
        String detail) {

    return new ValidationOutputException(
        "Malformed validator CSV log at record "
            + recordNumber
            + ": "
            + detail,
        null,
        csvLog.toAbsolutePath().toString()
    );
}
```

Optional Cause-Variante ergänzen.

## 9.6 Fallback ohne neue Dependency

Falls keine Bibliothek eingeführt werden soll, muss ein recordbasierter Parser implementiert werden:

```java
private String readCsvRecord(
        BufferedReader reader,
        CsvParserState state)
```

Er muss physische Zeilen solange zusammenfügen, bis ein gequoteter Record vollständig geschlossen ist.

Unbedingt testen:

```text
"a
b",Error,...
```

Nicht nur zeilenweise.

## 9.7 Tests

- normales CSV,
- Feld mit Komma,
- escaped Quote,
- leeres Feld,
- Unicode,
- mehrzeiliges Feld,
- ungeschlossene Quote,
- zu wenige Spalten,
- ungültige Severity,
- BOM,
- leere Datei,
- Datei nur mit Header.

## 9.8 Definition of Done

- [ ] echte CSV-Records statt physischer Zeilen,
- [ ] mehrzeilige Felder funktionieren,
- [ ] strukturelle Fehler erzeugen `ValidationOutputException`,
- [ ] keine Meldung wird still übersprungen.

---

# 10. Priorität P1 – ABI-Struct-Grösse absichern

## 10.1 Problem

Die erwartete Grösse ist mindestens doppelt hart codiert:

```c
#define ILI_REQUEST_STRUCT_SIZE 112
```

und:

```java
public static final long EXPECTED_STRUCT_SIZE = 112L;
```

Dies kann bei Strukturänderungen auseinanderlaufen.

## 10.2 C-seitiger Static Assert

### Datei

```text
duckdb-extension/src/include/ili_request.h
```

Nach der Struct-Definition:

```c
_Static_assert(
    sizeof(ili_request) == ILI_REQUEST_STRUCT_SIZE,
    "ILI_REQUEST_STRUCT_SIZE does not match sizeof(ili_request)"
);
```

Für C++ optional:

```c
#ifdef __cplusplus
static_assert(
    sizeof(ili_request) == ILI_REQUEST_STRUCT_SIZE,
    "ILI_REQUEST_STRUCT_SIZE mismatch"
);
#else
_Static_assert(
    sizeof(ili_request) == ILI_REQUEST_STRUCT_SIZE,
    "ILI_REQUEST_STRUCT_SIZE mismatch"
);
#endif
```

## 10.3 Java-seitige Grösse

Bevorzugt:

```java
import org.graalvm.nativeimage.c.struct.SizeOf;
```

Sinngemäss:

```java
public static long expectedStructSize() {
    return SizeOf.get(IliRequest.class);
}
```

Dann:

```java
NativeRequestValidator.requireRequest(
    request,
    NativeRequestValidator.expectedStructSize(),
    "validate"
);
```

Falls `SizeOf` mit der verwendeten GraalVM-API nicht verfügbar oder nicht geeignet ist:

- Konstante aus generiertem Source ableiten,
- nicht manuell an zwei Stellen pflegen.

## 10.4 Header-Prototyp korrigieren

Der Prototyp von `ili_get_api` muss der tatsächlichen exportierten GraalVM-Signatur entsprechen oder entfernt werden.

Nicht korrekt:

```c
int ili_get_api(
    uint32_t requested_abi_version,
    char **out_payload
);
```

Korrekt mit Graal-Thread:

```c
int ili_get_api(
    graal_isolatethread_t *thread,
    uint32_t requested_abi_version,
    char **out_payload
);
```

Falls der gemeinsame Header den Graal-Typ nicht kennen soll, den Funktionsprototyp in einen separaten Dynamic-API-Header verschieben.

## 10.5 Tests

- `struct_size=0`,
- kleiner als benötigt,
- exakt benötigt,
- grösser als benötigt,
- Compile-Time-Fehler bei inkonsistenter Struct-Konstante.

---

# 11. Priorität P1 – Alle relevanten C-Allokationen prüfen

## 11.1 Problem

Mehrere Pfade verwenden:

```c
malloc(...)
memset(...)
memcpy(...)
```

ohne NULL-Prüfung.

Dies betrifft insbesondere:

- Validator-Zeilenarray,
- Modellinfo-Zeilenarray,
- XTF-Zeilenarray,
- dynamische Spaltennamen,
- `strdup()`-Resultate.

## 11.2 Allgemeiner Helper

Neue Funktion:

```c
static void *ili_malloc_or_error_init(
        duckdb_init_info info,
        size_t size,
        const char *what) {

    void *ptr = malloc(size);

    if (!ptr) {
        char message[256];

        snprintf(
            message,
            sizeof(message),
            "Out of memory allocating %s",
            what
        );

        duckdb_init_set_error(info, message);
    }

    return ptr;
}
```

Analog für Bind.

## 11.3 Beispiel Validator

Nicht mehr:

```c
id->rows = malloc(
    id->row_count
        * sizeof(ili_validate_row)
);

memset(
    id->rows,
    0,
    id->row_count
        * sizeof(ili_validate_row)
);
```

Stattdessen:

```c
id->rows = ili_calloc_or_error_init(
    info,
    id->row_count,
    sizeof(*id->rows),
    "validator rows"
);

if (!id->rows) {
    free(result);
    free(id);
    return;
}
```

## 11.4 Einzelzeilen

```c
id->rows[i] =
    ili_malloc_or_error_init(
        info,
        len + 1,
        "TSV row"
    );

if (!id->rows[i]) {
    free(result);
    mi_init_destroy(id);
    return;
}
```

## 11.5 `strdup()` in Parameter-Helpern

Der Helper muss OOM von SQL-NULL unterscheiden können.

Statt nur `char *` zurückzugeben, möglich:

```c
typedef enum {
    ILI_PARAM_OK,
    ILI_PARAM_NULL,
    ILI_PARAM_ERROR
} ili_param_status;
```

```c
ili_param_status ili_bind_copy_parameter_varchar(
    duckdb_bind_info info,
    idx_t parameter_index,
    char **out_value
);
```

Oder der bestehende Helper setzt bei `strdup()`-Fehler direkt einen Bind-Fehler und liefert NULL.

Dann muss der Caller unterscheiden können, ob ein optionaler Parameter NULL war oder ein Fehler gesetzt wurde.

Eine einfache Variante:

```c
char *copy = NULL;

if (raw) {
    copy = strdup(raw);

    if (!copy) {
        duckdb_bind_set_error(
            info,
            "Out of memory copying parameter"
        );
    }
}
```

Der Caller darf danach bei einem obligatorischen Parameter ohnehin abbrechen.

## 11.6 Tests

OOM lässt sich schwer deterministisch testen.

Empfohlen:

- Allokationswrapper über Funktionspointer,
- Testmodus mit „fail allocation after N calls“.

Beispiel:

```c
#ifdef ILI_TEST_ALLOCATOR
void ili_test_fail_allocation_after(long count);
#endif
```

Mindestens müssen alle Allokationspfade statisch geprüft sein.

---

# 12. Priorität P1 – Import-Spaltenkollisionen erkennen

## 12.1 Problem

Spaltennamen werden normalisiert:

```java
normalizeColumnName()
```

Dadurch können verschiedene INTERLIS-Namen denselben DuckDB-Identifier erzeugen.

Beispiele:

```text
Name
name
```

```text
foo-bar
foo_bar
```

```text
geom
geom_wkb
```

```text
xtf_tid
technische Spalte xtf_tid
```

## 12.2 `ColInfo` erweitern

```java
private static final class ColInfo {
    final String sourceName;
    final String name;
    final String duckdbType;

    ColInfo(
            String sourceName,
            String generatedName,
            String duckdbType) {

        this.sourceName = sourceName;
        this.name =
            SqlIdentifiers.normalizeColumnName(
                generatedName
            );
        this.duckdbType = duckdbType;
    }
}
```

Technische Spalten:

```java
new ColInfo(
    "<technical>",
    "xtf_tid",
    "VARCHAR"
)
```

Attribut:

```java
new ColInfo(
    ad.getScopedName(null),
    ad.getName(),
    mappedType
)
```

## 12.3 Kollisionserkennung

```java
private static void ensureNoColumnCollisions(
        String ownerFqn,
        List<ColInfo> columns) {

    Map<String, ColInfo> seen =
        new LinkedHashMap<>();

    for (ColInfo column : columns) {
        String key =
            SqlIdentifiers.collisionKey(
                column.name
            );

        ColInfo existing =
            seen.putIfAbsent(
                key,
                column
            );

        if (existing != null) {
            throw new IllegalArgumentException(
                "Column name collision in "
                    + ownerFqn
                    + ": source '"
                    + existing.sourceName
                    + "' and source '"
                    + column.sourceName
                    + "' both map to DuckDB column '"
                    + column.name
                    + "'"
            );
        }
    }
}
```

Aufruf direkt nach `buildClassColumns()` und `buildAssociationColumns()`.

## 12.4 Mapping direkt im Service validieren

`IliImportService.generateImportSql()` muss selbst prüfen:

```java
String effectiveMapping =
    mapping == null || mapping.isBlank()
        ? "relational"
        : mapping;

if (!"relational".equals(effectiveMapping)) {
    throw new IllegalArgumentException(
        "Unsupported mapping mode: "
            + effectiveMapping
    );
}
```

Nicht nur der Native Entry Point darf diese Regel durchsetzen.

## 12.5 Tests

- `Name` und `name`,
- Zeichenersetzungskollision,
- Attribut kollidiert mit `xtf_tid`,
- Attribut `foo_wkb` kollidiert mit generierter Geometriespalte,
- Rollenname kollidiert mit `_ref`-Spalte,
- erlaubte nicht kollidierende Namen,
- direkter Java-Aufruf mit nicht unterstütztem Mapping.

---

# 13. Priorität P2 – Sanitizer- und Integrationstests vertiefen

## 13.1 Hostprogramme mit Sanitizer-Flags bauen

Der Host muss mit denselben Sanitizern gebaut werden.

ASan/UBSan:

```bash
cc \
  -fsanitize=address,undefined \
  -fno-omit-frame-pointer \
  -g \
  -o duckdb-extension/test/duckdb_extension_smoke_test \
  duckdb-extension/test/duckdb_extension_smoke_test.c \
  -Iduckdb-extension/src/include \
  -ldl \
  -lpthread
```

TSan:

```bash
cc \
  -fsanitize=thread \
  -fno-omit-frame-pointer \
  -g \
  -o duckdb-extension/test/duckdb_extension_concurrency_test \
  duckdb-extension/test/duckdb_extension_concurrency_test.c \
  -Iduckdb-extension/src/include \
  -ldl \
  -lpthread
```

## 13.2 Wiederholte Bind-/Init-/Destroy-Zyklen

Nicht nur jede Funktion einmal aufrufen.

Beispiel:

```c
for (int i = 0; i < 1000; i++) {
    verify_query(
        conn,
        "SELECT * FROM ili_models(...)",
        "ili_models repeated",
        1
    );
}
```

Mindestens:

- `ili_models`,
- `ili_topics`,
- `ili_classes`,
- `read_xtf_objects`,
- `read_xtf_class`,
- `read_xtf_association`,
- `ili_validate`,
- `ili_generate_import_sql`.

Für teurere Funktionen dürfen 100 Wiederholungen genügen.

## 13.3 Werte statt nur Row Count prüfen

Der Smoke-Test muss C-API-Funktionen auflösen:

```c
duckdb_value_varchar
duckdb_value_int32
duckdb_value_is_null
duckdb_column_count
duckdb_column_name
duckdb_column_type
```

Beispiel:

```c
char *value =
    p_value_varchar(
        &result,
        column,
        row
    );

assert(strcmp(value, expected) == 0);

p_free(value);
```

Prüfen:

- Sonderzeichen,
- NULL,
- leere Strings,
- Zeilen- und Spaltennummern,
- JSON-Inhalt,
- richtige dynamische Spaltennamen,
- richtige Reihenfolge.

## 13.4 Fehlerpfade testen

DuckDB-Queries:

```sql
SELECT *
FROM read_xtf_class(
    'missing.xtf',
    class := 'Model.Topic.Class'
);
```

```sql
SELECT *
FROM read_xtf_class(
    'file.xtf',
    class := 'Unknown.Topic.Class'
);
```

```sql
SELECT *
FROM ili_validate(
    'missing.xtf'
);
```

```sql
SELECT *
FROM ili_validate(
    'file.xtf',
    profile := 'unknown'
);
```

Alle müssen als Queryfehler enden.

## 13.5 Concurrency-Test vertiefen

Aktuell nur drei Queries pro Thread.

Erweitern:

```c
for (int round = 0; round < 50; round++) {
    for (size_t query = 0; query < NUM_QUERIES; query++) {
        ...
    }
}
```

Weiterhin:

- eigene Connection pro Thread,
- gemeinsames DuckDB-Database-Handle,
- synchronisierter Start,
- Resultate immer zerstören,
- Connection immer trennen.

## 13.6 Definition of Done

- [ ] Host mit Sanitizer-Runtime gebaut,
- [ ] wiederholte Zyklen,
- [ ] Werte geprüft,
- [ ] NULL geprüft,
- [ ] Fehlerpfade geprüft,
- [ ] Concurrency mehrfach wiederholt.

---

# 14. Priorität P2 – Modellcache bei parallelen Misses härten

## 14.1 Problem

Bei parallelen Cache-Misses kann dasselbe Modell mehrfach kompiliert werden.

Aktuell:

```java
td = map.get(key);

if (td == null) {
    td = compiler.get();
    map.putIfAbsent(key, td);
}
```

## 14.2 Lösung mit `CompletableFuture`

Feld:

```java
private final ConcurrentHashMap<
    CacheKey,
    CompletableFuture<TransferDescription>
> map;
```

Implementierung:

```java
public TransferDescription getOrCompile(
        CacheKey key,
        Supplier<TransferDescription> compiler) {

    AtomicBoolean created =
        new AtomicBoolean(false);

    CompletableFuture<TransferDescription> future =
        map.computeIfAbsent(
            key,
            ignored -> {
                created.set(true);

                return new CompletableFuture<>();
            }
        );

    if (created.get()) {
        long start =
            System.currentTimeMillis();

        try {
            TransferDescription td =
                compiler.get();

            future.complete(td);

            long elapsed =
                System.currentTimeMillis()
                    - start;

            compileTimesMs.put(
                key,
                elapsed
            );

            addAccessOrder(key);
            evictIfNeeded();
        } catch (Throwable throwable) {
            future.completeExceptionally(
                throwable
            );

            map.remove(key, future);

            throw throwable;
        }
    } else {
        hits.increment();
    }

    try {
        return future.join();
    } catch (CompletionException e) {
        Throwable cause = e.getCause();

        if (cause instanceof RuntimeException runtime) {
            throw runtime;
        }

        throw new RuntimeException(cause);
    }
}
```

Alternativ separate `inFlight`-Map verwenden, damit die bestehende Resultmap unverändert bleibt.

## 14.3 Metriken

- genau ein Miss pro tatsächlicher Kompilation,
- wartende Threads zählen als Hits oder eigene `waits`,
- `invalidateAll()` muss auch `compileTimesMs.clear()` ausführen.

## 14.4 Test

20 parallele Threads:

```java
AtomicInteger compileCalls =
    new AtomicInteger();

Supplier<TransferDescription> compiler =
    () -> {
        compileCalls.incrementAndGet();
        sleep(100);
        return td;
    };
```

Erwartung:

```java
assertEquals(1, compileCalls.get());
```

---

# 15. Priorität P2 – Logger-Dokumentation korrigieren

## 15.1 Problem

`EhiLogger` ist global.

Das Entfernen und Hinzufügen eines Listeners beeinflusst grundsätzlich alle Threads.

Die aktuelle Dokumentation behauptet, dass andere Threads nicht betroffen seien.

## 15.2 Korrektur

JavaDoc soll ausdrücklich erklären:

```text
The listener mutation is process-global because EhiLogger is a singleton.
Calls originating from the DuckDB extension are currently serialized by the
native bridge. This prevents overlapping INTERLIS operations inside this
extension, but unrelated code in the same process using EhiLogger can still
observe the temporary listener configuration.
```

Keine falsche Behauptung über vollständige Thread-Isolation.

## 15.3 Optional bessere Architektur

Langfristig prüfen, ob ili2c/ilivalidator einen operationseigenen Listener oder Settings-Mechanismus erlauben.

Nicht Bestandteil dieses Korrekturpakets, sofern dafür Libraryänderungen nötig wären.

---

# 16. Priorität P2 – Dokumentation aktualisieren

## 16.1 `docs/functions.md`

Korrigieren:

- tatsächliche JSON-Feldnamen von `ili_native_version()`,
- vollständige Signatur von `ili_validate()`,
- Parameter `profile`,
- Parameter `max_messages`,
- NULL-Semantik,
- `max_messages` begrenzt nur Detailmeldungen, nicht Gesamtzähler,
- technische Fehler erzeugen Queryfehler.

## 16.2 `docs/native-abi.md`

Ergänzen:

- `struct_size`,
- Mindestgrösse,
- Forward Compatibility,
- Capability-Maske,
- Ownership,
- `ili_free_string`,
- Threadargument in Funktionssignaturen.

## 16.3 `docs/error-handling.md`

Explizit unterscheiden:

```text
fachlich ungültige XTF
-> Query erfolgreich
-> ERROR-Zeilen
-> valid=false

technischer Validatorfehler
-> Query schlägt fehl
```

## 16.4 `docs/limitations.md`

Aktueller Stand vor Meilenstein B:

- XTF-Reader materialisieren weiterhin vollständige Payloads,
- Validatorresultat wird materialisiert,
- Modellmetadaten werden materialisiert,
- Java-Aufrufe global serialisiert,
- für kleine Dateien produktiv nutzbar,
- Streaming folgt in Meilenstein B.

---

# 17. Zusätzliche statische Bereinigung

## 17.1 Doppelte Imports

In `NativeEntryPoints.java` doppelte Imports entfernen, beispielsweise mehrfaches:

```java
import org.graalvm.nativeimage.c.type.CCharPointer;
```

## 17.2 Einheitliche Exceptions

Nicht überall generisches:

```java
throw new RuntimeException(...)
```

verwenden.

Empfohlene fachliche Exceptions:

```text
ModelCompilationException
ImportGenerationException
XtfReadException
```

Dies ist optional, solange der Scope nicht unnötig wächst.

## 17.3 Locale-sichere Normalisierung

Bei:

```java
toLowerCase()
```

immer:

```java
toLowerCase(Locale.ROOT)
```

verwenden.

### Datei

```text
SqlIdentifiers.java
```

Korrigieren:

```java
return Normalizer
    .normalize(s, Normalizer.Form.NFC)
    .toLowerCase(Locale.ROOT);
```

---

# 18. Verbindliche Testmatrix

## 18.1 Java Unit Tests

Mindestens:

```text
IliValidatorServiceTest
ValidationResultTest
ValidationProfileTest
TsvCodecTest
ModelRepositoryResolverTest
IliImportServiceTest
SqlIdentifiersTest
ModelCacheTest
```

## 18.2 Native Tests

Mindestens:

```text
native_smoke_test
ili_tsv_test
duckdb_extension_smoke_test
duckdb_extension_concurrency_test
```

## 18.3 SQL-Tests

Mindestens:

```text
sql/regression.sql
sql/concurrency.sql
sql/errors.sql
sql/null-semantics.sql
sql/tsv-escaping.sql
sql/validation-max-messages.sql
```

## 18.4 Plattformen

- Linux x86_64,
- Linux ARM64,
- macOS ARM64,
- Windows x86_64.

Sanitizer mindestens Linux x86_64.

## 18.5 Testdatenprüfung

Neue Modelle:

```bash
java -jar /Users/stefan/apps/ili2c-5.6.8/ili2c.jar ...
```

oder Maven-/Gradle-Version `5.6.8`.

Neue gültige Transferdateien:

```bash
java -jar /Users/stefan/apps/ilivalidator-1.15.0/ilivalidator-1.15.0.jar ...
```

oder Maven-/Gradle-Version `1.15.0`.

---

# 19. Verbindliche Reihenfolge der Commits

Empfohlene Commits:

```text
fix(validation): keep total counts independent of max_messages
fix(tsv): decode escaped fields before assigning DuckDB values
fix(validation): preserve SQL NULL semantics in table output
fix(c): avoid pointer checks after free
fix(models): normalize semicolon-separated model repositories
fix(windows): initialize extension mutexes exactly once
fix(validation): parse validator CSV records strictly
fix(abi): derive and assert request struct size
fix(c): check all row and column allocations
fix(import): reject normalized column collisions
test(extension): verify values, nulls and repeated lifecycle cycles
test(concurrency): increase repeated multi-connection coverage
fix(cache): deduplicate concurrent model compilation
docs: align function, ABI and limitation documentation
```

Jeder Commit muss eigenständig bauen.

---

# 20. Definition of Done

## Fachliche Korrektheit

- [ ] `max_messages` verändert `valid` nicht.
- [ ] `max_messages` verändert Gesamtzähler nicht.
- [ ] fachlich ungültige Dateien bleiben ungültig.
- [ ] technische Validatorfehler bleiben harte Fehler.

## TSV

- [ ] Backslash korrekt.
- [ ] Tab korrekt.
- [ ] Newline korrekt.
- [ ] Carriage Return korrekt.
- [ ] `\N` als NULL.
- [ ] `\\N` als echter Text.
- [ ] abschliessende leere Felder korrekt.
- [ ] falsche Spaltenzahl wird abgelehnt.

## SQL-NULL

- [ ] fehlende Integerwerte sind NULL.
- [ ] fehlende Strings sind NULL.
- [ ] leere Strings bleiben leer.
- [ ] echte 0 bleibt 0.

## Native/C

- [ ] keine Pointerprüfung nach `free()`.
- [ ] keine ungeprüften relevanten Allokationen.
- [ ] Windows-Mutexes einmalig initialisiert.
- [ ] Struct-Grösse compile-time abgesichert.

## Modeldirs

- [ ] mehrere Repositories funktionieren überall gleich.
- [ ] Cache-Keys verwenden normalisierte Form.
- [ ] lokale und entfernte Repositories kombinierbar.

## Import

- [ ] Tabellenkollisionen erkannt.
- [ ] Spaltenkollisionen erkannt.
- [ ] Mapping direkt im Service geprüft.
- [ ] SQL-Identifier korrekt gequotet.

## Tests

- [ ] Smoke-Tests prüfen Werte.
- [ ] Smoke-Tests prüfen NULL.
- [ ] wiederholte Bind-/Init-/Destroy-Zyklen.
- [ ] Hostprogramme mit Sanitizer-Flags gebaut.
- [ ] Concurrency mehrfach wiederholt.
- [ ] alle Plattform-Builds grün.

## Dokumentation

- [ ] Funktionssignaturen korrekt.
- [ ] JSON-Feldnamen korrekt.
- [ ] NULL-Semantik dokumentiert.
- [ ] `max_messages` dokumentiert.
- [ ] aktuelle Materialisierungslimitation dokumentiert.

---

# 21. Abschlussbericht des Coding-Agenten

Der Coding-Agent muss am Ende liefern:

1. Liste aller geänderten Dateien.
2. Beschreibung jeder fachlichen Korrektur.
3. Resultate aller Java-Tests.
4. Resultate aller C-/Native-Tests.
5. Resultate aller SQL-Integrationstests.
6. Resultate von ASan, UBSan, LSan und TSan.
7. Bestätigung der Windows-Builds.
8. ili2c-Prüfergebnisse für neue oder geänderte Modelle.
9. ilivalidator-Prüfergebnisse für neue oder geänderte gültige XTF-Dateien.
10. Liste verbleibender Limitationen.
11. Explizite Bestätigung, dass Meilenstein B noch nicht umgesetzt wurde.

Der Agent darf keine Aussage wie:

```text
all robustness issues fixed
```

machen, wenn nicht mindestens folgende Nachweise vorhanden sind:

- `max_messages`-Regressionstest,
- TSV-Roundtrip-Test,
- SQL-NULL-Test,
- wiederholter Extension-Smoke-Test,
- Sanitizer-Lauf,
- Multi-Connection-Test.
