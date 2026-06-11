# Implementierungsspezifikation: Robustheits- und Produktionsreife-Offensive für `duckdb-interlis`

## 1. Zweck dieses Dokuments

Dieses Dokument ist ein verbindlicher Implementierungsauftrag für einen LLM-Coding-Agenten.

Das Repository `edigonzales/duckdb-interlis` integriert INTERLIS- und XTF-Funktionalität in DuckDB. Die Architektur besteht aus:

1. einer DuckDB-C-Extension,
2. einer dynamisch geladenen GraalVM-Native-Shared-Library,
3. Java-Code mit `ili2c`, `ilivalidator`, `iox-ili` und verwandten Bibliotheken.

Die Grundidee und der bestehende Proof of Concept sind gut. Der aktuelle Code enthält jedoch mehrere grobe Robustheits-, Speicher-, Fehlerbehandlungs-, Nebenläufigkeits- und API-Probleme. Diese Probleme müssen vor einer breiteren produktiven Verwendung systematisch beseitigt werden.

Das primäre Ziel dieser Spezifikation ist **nicht**, sofort eine hochskalierbare Streaming-Architektur für sehr grosse XTF-Dateien zu bauen.

Das primäre Ziel ist:

> Die bestehende Extension für den produktiven Einsatz mit kleinen und mittleren INTERLIS-/XTF-Dateien robust, deterministisch, diagnostizierbar und plattformübergreifend sicher zu machen.

Für den vorgesehenen Einsatz gilt die Extension bereits dann als produktionsreif, wenn:

- die groben Speicher- und ABI-Fehler behoben sind,
- Fehler niemals still verschluckt werden,
- keine partiellen Resultate als Erfolg ausgegeben werden,
- parallele Aufrufe nicht zu undefiniertem Verhalten führen,
- die Native-Library sicher geladen und verwaltet wird,
- die Validierungssemantik klar und korrekt ist,
- der Build reproduzierbar und zuverlässig ist,
- die bestehenden Funktionen durch aussagekräftige Tests abgesichert sind.

Batching und echtes Streaming sind wichtig, werden aber bewusst in eine spätere Phase verschoben.

---

# 2. Verbindliche Arbeitsweise für den Coding-Agenten

Der Coding-Agent muss diese Regeln einhalten:

1. **Keine grossflächige Neuentwicklung ohne Tests.**
2. Jede Phase muss einen eigenständig lauffähigen und getesteten Zwischenstand liefern.
3. Bestehende SQL-Funktionen dürfen nicht stillschweigend inkompatibel verändert werden.
4. Notwendige Breaking Changes müssen:
   - explizit dokumentiert,
   - in der Versionsnummer sichtbar,
   - durch Migrationsempfehlungen begleitet werden.
5. Fehler dürfen niemals durch leere Strings oder leere Tabellen kaschiert werden.
6. C-, GraalVM- und Java-Speicher dürfen niemals mit dem falschen Deallocator freigegeben werden.
7. Alle Ownership-Regeln an der C-ABI müssen im Header dokumentiert sein.
8. Kein `catch (Exception) { return null; }`, sofern dadurch die Fehlerursache verloren geht.
9. Keine festen Request-Buffer für potentiell benutzerkontrollierte Pfade oder Modellverzeichnisse.
10. Neue Funktionen müssen unter Linux x86_64, Linux ARM64, macOS ARM64 und Windows x86_64 berücksichtigt werden.
11. Die CI muss nach jeder Phase grün sein.
12. Performanceoptimierungen dürfen Robustheit und Diagnosefähigkeit nicht verschlechtern.
13. Wo die INTERLIS-Bibliotheken globale Zustände verwenden, ist im Zweifel eine konservative Serialisierung besser als unsichere Parallelität.
14. Batching darf erst begonnen werden, wenn die vorherigen Phasen vollständig abgeschlossen sind.

---

# 3. Bestehende Architektur

## 3.1 Schichten

```text
DuckDB SQL
    ↓
DuckDB C Extension
    ↓
dynamisch geladene GraalVM Shared Library
    ↓
Java Native Entry Points
    ↓
INTERLIS Java Core
    ├── ili2c
    ├── ilivalidator
    ├── iox-ili
    └── weitere INTERLIS-Bibliotheken
```

## 3.2 Bestehendes Austauschmuster

Die C-Extension erzeugt Requests als JSON-Strings.

Die Java-Native-Library verarbeitet die Requests und liefert Resultate meist als:

- JSON-String,
- TSV-String,
- SQL-String,
- oder Text mit Präfix `ERROR:`.

Die Resultate werden mit `UnmanagedMemory.malloc()` allokiert und müssen über `ili_free_string()` freigegeben werden.

Die C-Seite lädt die Native-Library mit `dlopen()` beziehungsweise `LoadLibrary()`, löst Symbole dynamisch auf und erzeugt einen GraalVM-Isolate.

## 3.3 Bestehende Funktionsgruppen

Die Extension enthält unter anderem:

- Versionsfunktionen,
- Validierung,
- Modellanalyse,
- XTF-Objektlesen,
- klassenspezifisches XTF-Lesen,
- Struktur- und Assoziationsinformationen,
- Import-SQL-Generierung.

---

# 4. Definition von „produktionsreif“ für dieses Projekt

Die Extension wird für den vorgesehenen Einsatz mit kleinen Dateien als produktionsreif betrachtet, wenn alle folgenden Punkte erfüllt sind:

## 4.1 Sicherheit und Speicher

- Kein bekannter Use-after-free.
- Kein Allocator-Mismatch.
- Kein bekanntes Leck bei regulären Erfolgs- oder Fehlerpfaden.
- Native-Resultate werden immer genau einmal freigegeben.
- Fehlerpayloads werden nicht verworfen.
- Initialisierung und Shutdown sind nebenläufigkeitssicher.
- Eine fehlende oder inkompatible Native-Library führt zu einer klaren DuckDB-Fehlermeldung.

## 4.2 Fehlerverhalten

- Jede fachliche oder technische Fehlersituation erzeugt entweder:
  - einen klaren DuckDB-Fehler, oder
  - ein explizites strukturiertes Fehlerresultat, falls dies Bestandteil der SQL-Funktion ist.
- Leere Tabellen oder leere Strings dürfen nicht als Ersatz für technische Fehler dienen.
- Partielle XTF-Resultate dürfen nicht als vollständiger Erfolg ausgegeben werden.
- Originalursachen müssen in der Fehlermeldung erhalten bleiben.

## 4.3 Fachliche Semantik

- Der tatsächliche Validierungsumfang ist klar dokumentiert.
- Constraint- und AREA-Prüfung sind nicht unbemerkt deaktiviert.
- Klassen werden über vollständige qualifizierte Namen unterschieden.
- NULL und leerer String werden in SQL soweit technisch möglich unterschieden.
- Importtabellen kollidieren nicht bei gleichen Klassennamen in verschiedenen Topics oder Modellen.

## 4.4 Plattformen und Build

- CI für:
  - Linux x86_64,
  - Linux ARM64,
  - macOS ARM64,
  - Windows x86_64.
- Release-Build schlägt fehl, wenn die Native-Library fehlt.
- Keine hart codierten lokalen Entwicklerpfade.
- Zielversionen und ABI-Versionen sind eindeutig und konsistent dokumentiert.
- Smoke- und Integrationstests prüfen mehr als nur Versionsfunktionen.

## 4.5 Abgrenzung

Für die erste produktionsreife Version sind **nicht zwingend erforderlich**:

- echtes Streaming sehr grosser XTF-Dateien,
- zero-copy columnar batches,
- vollständige relationale Abbildung aller INTERLIS-Konstrukte,
- maximale Parallelität,
- optimierter Import von Gigabyte-Dateien.

Diese Punkte gehören in spätere Phasen.

---

# 5. Prioritäten

## P0 – Blocker

Diese Probleme können zu Speicherfehlern, Abstürzen, falschen Resultaten oder undefiniertem Verhalten führen.

Sie müssen zuerst behoben werden.

1. Allocator-Mismatch bei Native-Resultaten.
2. Leaks und Verlust von Fehlerpayloads.
3. Kein konsistenter Fehlervertrag an der C-ABI.
4. Nicht threadsichere Native-Initialisierung.
5. Fehlerhafte manuelle JSON-Erzeugung.
6. Feste Request-Buffer und stille Truncation.
7. Verschluckte Exceptions und partielle Resultate.
8. Falsche oder unklare Validierungssemantik.
9. Unsichere Extraktion der eingebetteten Native-Library.
10. Release-Build kann ohne Native-Library erfolgreich sein.

## P1 – Hohe Priorität

1. Thread-Sicherheit des Java-Caches und globaler Logger-Zustände.
2. Eindeutige Modell-, Topic- und Klassennamen.
3. NULL-Semantik.
4. CSV-Parsing der Validatorausgabe.
5. Importtabellen-Kollisionen.
6. Wirkungsloser `mapping`-Parameter.
7. Fehlende Transaktions- und Wiederholungssemantik beim Import.
8. ABI-Handshake und Kompatibilitätsprüfung.
9. Bessere Tests, Sanitizer und Fehlerpfadtests.

## P2 – Fachliche und strukturelle Reife

1. bessere Typabbildung,
2. Geometrie- und CRS-Semantik,
3. Strukturen und BAG OF,
4. Basket- und Transfermetadaten,
5. Vererbung,
6. Assoziationen und Referenzen,
7. Constraints und Schemaeigenschaften,
8. Importmodi.

## P3 – Batching und Performance

1. Cursor-basierte Native-API,
2. batchweiser Ergebnistransfer,
3. Vermeidung vollständiger Stringmaterialisierung,
4. ein XTF-Durchlauf pro Import,
5. Cancellation,
6. Time-to-first-row-Optimierung.

---

# 6. Phase 0 – Baseline sichern

## 6.1 Ziel

Vor Änderungen muss der aktuelle Stand reproduzierbar dokumentiert werden.

## 6.2 Aufgaben

1. Aktuelle SQL-Funktionen erfassen.
2. Aktuelle Signaturen erfassen.
3. Aktuelle Rückgabespalten erfassen.
4. Aktuelle CI-Plattformen dokumentieren.
5. Testdaten und erwartete Resultate sichern.
6. Eine Testmatrix erstellen.
7. Bestehende bekannte Fehler als Regressionstests vorbereiten.

## 6.3 Erforderliche Dokumente

Erstelle:

```text
docs/current-api.md
docs/error-semantics.md
docs/native-abi.md
docs/production-readiness.md
```

## 6.4 Abnahmekriterien

- Alle vorhandenen Funktionen sind dokumentiert.
- Jede Funktion hat:
  - Signatur,
  - Parameter,
  - NULL-Verhalten,
  - Fehlerverhalten,
  - Resultatspalten,
  - Beispiel,
  - bekannte Limitationen.
- Die CI bleibt unverändert grün.

---

# 7. Phase 1 – Memory Ownership und grobe ABI-Fehler

Dies ist die wichtigste Phase.

## 7.1 Problem: Fehlerpayload wird geleakt

Die Java-Native-Funktionen allokieren auch bei Fehlern einen Resultatstring und geben einen Fehlerstatus zurück.

Die C-Helfer verwerfen aktuell bei `rc != 0` den Pointer und geben `NULL` zurück.

Dadurch:

- wird die echte Fehlermeldung verloren,
- wird der Native-String nicht freigegeben,
- entsteht bei jedem solchen Fehler ein Speicherleck.

## 7.2 Problem: falscher Deallocator beim Import

Der Java-Code allokiert Resultate mit GraalVM `UnmanagedMemory.malloc()`.

Der Importcode speichert den Pointer teilweise direkt und gibt ihn später mit C-`free()` frei.

Das ist undefiniertes Verhalten.

## 7.3 Verbindliche Ownership-Regel

Für jede Funktion, die einen String zurückgibt:

```text
- Der Callee allokiert den Resultatbuffer.
- Der Caller besitzt den Buffer nach erfolgreicher Rückkehr.
- Der Caller muss den Buffer exakt einmal über ili_free_string() freigeben.
- Diese Regel gilt unabhängig vom Statuscode.
- Auch Fehlerpayloads müssen freigegeben werden.
- Ein NULL-Pointer bedeutet nur: Es konnte überhaupt kein Payload erzeugt werden.
```

## 7.4 Neue ABI-Ergebnisstruktur

Bevorzugte Lösung:

```c
typedef enum ili_status_code {
    ILI_STATUS_OK = 0,
    ILI_STATUS_INVALID_ARGUMENT = 1,
    ILI_STATUS_IO_ERROR = 2,
    ILI_STATUS_MODEL_ERROR = 3,
    ILI_STATUS_PARSE_ERROR = 4,
    ILI_STATUS_VALIDATION_ERROR = 5,
    ILI_STATUS_UNSUPPORTED = 6,
    ILI_STATUS_INTERNAL_ERROR = 100
} ili_status_code;

typedef struct ili_result {
    uint32_t struct_size;
    ili_status_code status;
    char *payload;
    size_t payload_length;
} ili_result;
```

Alternativ darf zunächst die bestehende Signatur behalten werden, sofern:

- Fehlerpayloads nicht verloren gehen,
- Ownership korrekt ist,
- alle Pfade getestet werden.

## 7.5 Anforderungen

1. `ili_free_string()` muss für alle Native-Strings verwendet werden.
2. C-`free()` darf niemals auf GraalVM-Speicher angewendet werden.
3. GraalVM-`ili_free_string()` darf niemals auf C-eigenen Speicher angewendet werden.
4. Jeder Helper muss klar dokumentieren, wem ein Pointer gehört.
5. Es muss Tests für Erfolg und Fehler geben.
6. Fehlerpayload muss in DuckDB-Fehlern sichtbar werden.
7. Bei einem Fehler darf kein Native-Pointer verloren gehen.
8. Das Verhalten muss unter AddressSanitizer und LeakSanitizer geprüft werden.

## 7.6 Abnahmetests

- 10'000 absichtlich fehlschlagende Native-Aufrufe ohne wachsenden Speicher.
- Import-SQL-Erzeugung und Freigabe ohne Sanitizer-Fehler.
- Fehlender Input liefert Originalfehlermeldung.
- Modellkompilierungsfehler liefert Originalfehlermeldung.
- Alle Resultatpointer werden genau einmal freigegeben.

---

# 8. Phase 2 – Einheitlicher Fehlervertrag

## 8.1 Problem

Die Native-Funktionen verwenden derzeit unterschiedliche Muster:

- Status `1` plus JSON,
- Status `1` plus `ERROR: ...`,
- Status `0` plus `ERROR: ...`,
- Status `1` plus leerer String,
- leeres Resultat bei internem Fehler.

Das ist unzuverlässig und zwingt die C-Seite zu Heuristiken.

## 8.2 Ziel

Alle Native-Einstiegspunkte verwenden denselben Fehlervertrag.

## 8.3 Verbindliche Regeln

1. Status `ILI_STATUS_OK` bedeutet:
   - Payload ist ein fachlich gültiges Resultat.
2. Jeder andere Status bedeutet:
   - Payload ist eine strukturierte Fehlermeldung.
3. Ein Fehler darf niemals als leere Ergebnismenge dargestellt werden.
4. `ERROR:`-Präfixe in normalen Resultatstrings sind verboten.
5. Interne Java-Exceptions müssen in eine definierte Fehlerstruktur übersetzt werden.
6. Stacktraces dürfen optional im Debug-Modus geliefert werden.
7. Standardfehlermeldungen müssen enthalten:
   - Statuscode,
   - Operation,
   - kurze Meldung,
   - Ursache,
   - optional betroffener Pfad,
   - optional Modellname,
   - optional Exception-Klasse.

## 8.4 Fehlerpayload

Beispiel:

```json
{
  "status": "MODEL_ERROR",
  "operation": "compile_model",
  "message": "INTERLIS model compilation failed",
  "detail": "Model ExampleModel was not found",
  "path": "/tmp/models",
  "exception": "Ili2cFailure"
}
```

## 8.5 DuckDB-Verhalten

Technische Fehler müssen mit:

```c
duckdb_init_set_error(...)
duckdb_bind_set_error(...)
duckdb_scalar_function_set_error(...)
```

an DuckDB weitergegeben werden.

Fachliche Validierungsmeldungen sind hingegen normale Datenzeilen.

## 8.6 Abnahmekriterien

- Keine Funktion liefert Text mit `ERROR:` als scheinbar erfolgreiches Resultat.
- Alle Fehlerpfade geben die Ursache weiter.
- Fehlende Datei, ungültiges Modell, ungültige XTF-Datei und ungültige Parameter sind unterscheidbar.
- Fehlerpayload wird immer korrekt freigegeben.

---

# 9. Phase 3 – Sichere Request-Übergabe

## 9.1 Problem

Die C-Seite baut JSON manuell mit `snprintf()`.

Probleme:

- Quotes werden nicht escaped.
- Backslashes werden nicht escaped.
- Windows-Pfade erzeugen problematische JSON-Sequenzen.
- feste Buffer können abgeschnitten werden.
- optionale Felder können syntaktisch ungültiges JSON erzeugen.
- Truncation wird nicht als Fehler behandelt.
- Java verwendet keinen echten JSON-Parser.

## 9.2 Ziel

Requests müssen unabhängig von Pfadlänge, Unicode, Quotes und Plattform korrekt übertragen werden.

## 9.3 Kurzfristige Variante

Falls JSON beibehalten wird:

1. C-seitigen JSON-Builder einführen.
2. Strings vollständig escapen.
3. dynamische Speicherallokation anhand der benötigten Länge.
4. `snprintf(NULL, 0, ...)` oder sicheren Builder verwenden.
5. Java-seitig echten JSON-Parser verwenden.
6. ungültiges JSON als `INVALID_ARGUMENT` ablehnen.
7. `null` und leerer String unterscheiden.

## 9.4 Bevorzugte mittelfristige Variante

Für einfache Requestparameter C-Strukturen verwenden:

```c
typedef struct ili_string_view {
    const char *data;
    size_t length;
} ili_string_view;

typedef struct ili_read_request {
    uint32_t struct_size;
    ili_string_view input;
    ili_string_view modeldir;
    ili_string_view class_name;
    ili_string_view models;
    ili_string_view nested_mode;
} ili_read_request;
```

## 9.5 Verbindliche Tests

- Pfad mit Leerzeichen.
- Pfad mit `"` im Namen.
- Pfad mit Backslash.
- Windows-Pfad.
- Unicode.
- Pfad länger als 8192 Bytes muss entweder unterstützt oder explizit sauber abgelehnt werden.
- Modellverzeichnis mit mehreren Semikolon-Einträgen.
- leeres Feld.
- NULL-Feld.
- ungültiges JSON.
- unbekanntes Feld.

## 9.6 Abnahmekriterien

- Keine festen 4096-/8192-Request-Buffer für benutzerkontrollierte Eingaben.
- Keine stille Truncation.
- Kein manueller primitiver JSON-Parser.
- Alle Tests sind plattformübergreifend.

---

# 10. Phase 4 – Nebenläufigkeit und Lifecycle

## 10.1 Problem: globale Native-Zustände

Die C-Extension verwendet globale Variablen für:

- Library-Handle,
- Isolate,
- Funktionspointer,
- Initialisierungsstatus,
- Fehlerbuffer.

Die Initialisierung ist derzeit nicht gegen parallele Aufrufe geschützt.

## 10.2 Risiken

- doppelte Isolate-Erzeugung,
- überschriebenes Handle,
- unvollständig gesetzte Funktionspointer,
- beschädigter globaler Zustand,
- parallele Extraktion derselben Library,
- Race beim Shutdown.

## 10.3 Ziel

Native-Initialisierung muss genau einmal und threadsicher erfolgen.

## 10.4 Anforderungen

1. `call_once`, `pthread_once`, `InitOnceExecuteOnce` oder portable Mutex-Lösung.
2. Initialisierungsfehler müssen dauerhaft und reproduzierbar gespeichert werden.
3. Kein Thread darf halb initialisierte Funktionspointer sehen.
4. Shutdown darf nicht stattfinden, solange aktive Aufrufe existieren.
5. Falls DuckDB keinen verlässlichen Extension-Unload-Lifecycle bietet, darf bewusst auf Prozessende als Cleanup gesetzt werden. Dies muss dokumentiert werden.
6. `g_error_buf` darf nicht von parallelen Threads überschrieben werden.
7. Fehler sollen möglichst lokal als dynamische Strings oder thread-lokale Daten verwaltet werden.

## 10.5 GraalVM Thread Attach/Detach

Der aktuelle Ablauf attached und detached häufig mehrfach pro Resultat.

Kurzfristig:

```text
attach
call
verarbeiten/kopieren
free
detach
```

Nicht:

```text
attach
call
detach
attach
free
detach
```

## 10.6 Konservative Parallelität

Solange unklar ist, ob `ili2c`, `ilivalidator`, `EhiLogger` oder die verwendeten Metamodelle vollständig thread-safe sind:

- kritische Java-Operationen dürfen durch einen globalen oder operationstypbezogenen Lock serialisiert werden,
- Korrektheit ist wichtiger als Parallelität,
- diese Einschränkung ist zu dokumentieren.

## 10.7 Abnahmetests

- 50 parallele erste Aufrufe.
- parallele Versionsabfragen.
- parallele Validierungen.
- parallele Modellabfragen.
- parallele XTF-Leser.
- ThreadSanitizer soweit auf der Plattform möglich.
- kein Crash, kein Deadlock, keine verlorenen Fehler.

---

# 11. Phase 5 – Java-Cache und globale Logger-Zustände

## 11.1 Modellcache

Der aktuelle Cache ist ein nicht synchronisierter `HashMap`.

Probleme:

- nicht threadsicher,
- keine Invalidierung,
- keine Grössenbegrenzung,
- Cache-Key berücksichtigt Änderungen im Dateisystem nicht,
- langfristig veraltete `TransferDescription`.

## 11.2 Ziel

Ein kontrollierter, threadsicherer Cache.

## 11.3 Anforderungen

1. `ConcurrentHashMap` oder dedizierter Cache.
2. `computeIfAbsent()` nur mit sauberer Exceptionbehandlung.
3. Schlüssel enthält:
   - normalisierten Modeldir,
   - konkrete Modellmenge,
   - relevante Optionen,
   - bei lokalen Dateien einen Fingerprint.
4. Fingerprint mindestens:
   - absoluter normalisierter Pfad,
   - Dateigrösse,
   - Änderungszeit,
   - optional SHA-256.
5. konfigurierbare Obergrenze.
6. explizite Invalidierungsfunktion.
7. Cache-Metriken im Debug-Modus:
   - Hits,
   - Misses,
   - Evictions,
   - Compile-Zeit.
8. fehlgeschlagene Kompilierung darf nicht unkontrolliert dauerhaft als Erfolgscache gespeichert werden.

## 11.4 Bezug zu ilivalidator

Die Spezifikation muss ausdrücklich untersuchen, wie `ilivalidator` heute intern Modelle oder Validierungskontexte cached.

Wichtiger Hinweis:

> Bereits heute muss `ilivalidator` intern oder in seiner typischen Nutzung Caching betreiben, damit wiederholte Prüfungen nicht jedes Mal alle Modelle vollständig neu aufbauen. Diese bestehende Implementierung ist gezielt zu analysieren.

Der Coding-Agent soll nach Anhaltspunkten suchen für:

- Modellcache,
- TransferDescription-Wiederverwendung,
- Objektindexe,
- temporäre Objektablagen,
- alle-Objekte-zugreifbar-Mechanismen,
- mehrphasige Validierung,
- eventuelle Zwischenpuffer.

Diese Mechanismen können später Hinweise für Batching oder einen inkrementellen XTF-Verarbeitungspfad liefern.

## 11.5 Logger

Globale Änderungen an `System.err` sind problematisch.

Anforderungen:

1. Keine dauerhafte globale Umleitung von `System.err`.
2. Bevorzugt eigener EhiLogger-Listener.
3. Logger-Registrierung muss threadsicher sein.
4. Gleichzeitige Operationen dürfen sich nicht gegenseitig Logging entziehen.
5. Debug-Modus muss gezielt pro Prozess oder Operation dokumentiert sein.

## 11.6 Abnahmekriterien

- Cache ist threadsicher.
- lokale Modelländerungen werden erkannt.
- parallele Modellabfragen liefern konsistente Resultate.
- kein globaler Loggerzustand bleibt nach einem Fehler verändert.
- Cacheverhalten ist getestet und dokumentiert.

---

# 12. Phase 6 – Validator robust und fachlich korrekt machen

## 12.1 Grober Mangel: unklare Validierungssemantik

Die Funktion heisst `ili_validate`, während Constraint- und AREA-Prüfung deaktiviert sind.

Das ist fachlich missverständlich.

## 12.2 Ziel

Der Benutzer muss genau wissen, was geprüft wird.

## 12.3 Validierungsprofile

Einführen:

```text
profile = full
profile = structural
profile = fast
```

### `full`

- Type Validation: an
- Multiplicity Validation: an
- Constraint Validation: an
- AREA Validation: an
- alle fachlich üblichen Prüfungen

### `structural`

- XML/XTF-Struktur
- Typen
- Multiplizitäten
- Modellkonformität
- Constraints optional aus

### `fast`

- minimaler schneller Check
- ausdrücklich nicht als vollständige Validierung bezeichnen

Default:

```text
full
```

Falls technische Gründe zunächst gegen `full` sprechen, muss die Funktion bis zur Umsetzung:

- anders benannt werden, oder
- deutlich warnen,
- oder einen expliziten Pflichtparameter verlangen.

## 12.4 CSV-Parsing

Der aktuelle Ansatz mit `split(",")` ist unzulässig.

Bevorzugte Lösung:

- strukturierte Meldungen über einen eigenen ilivalidator-/EhiLogger-Listener sammeln.

Fallback:

- echte CSV-Bibliothek,
- Streaming-Parser,
- Unterstützung von Quotes, Kommas und Zeilenumbrüchen.

## 12.5 Temporäre Dateien

Falls weiterhin temporäre CSV-Dateien verwendet werden:

1. sichere Temp-Datei.
2. Cleanup in jedem Pfad.
3. Fehler beim Löschen im Debug-Log.
4. kein vollständiges `readAllLines()` für potentiell grosse Logs.
5. Dateiname pro Aufruf eindeutig.
6. keine Kollision paralleler Validierungen.

## 12.6 `maxMessages`

Prüfen, ob die verwendete Setting-Konstante offiziell unterstützt ist.

Keine magischen Stringschlüssel ohne Dokumentation.

## 12.7 Abnahmetests

- gültige Datei mit Constraints.
- Datei mit Constraint-Verletzung.
- Datei mit AREA-Fehler.
- Multiplizitätsfehler.
- Typfehler.
- Meldung mit Komma.
- Meldung mit Anführungszeichen.
- Meldung mit Unicode.
- `maxMessages`.
- parallele Validierungen.
- lokale und entfernte Model-Repositories.

---

# 13. Phase 7 – XTF-Leser: keine stillen oder falschen Resultate

## 13.1 Grober Mangel: Exceptions werden verschluckt

Ein Fehler beim Lesen kann derzeit zu einem partiellen Resultat führen.

Das ist für ETL nicht akzeptabel.

## 13.2 Verbindliche Regel

Bei einem technischen Lesefehler:

```text
- kein partielles Erfolgsresultat,
- Reader abbrechen,
- Native-Fehler liefern,
- DuckDB-Query fehlschlagen lassen.
```

Optional kann später ein expliziter tolerant mode eingeführt werden:

```sql
ignore_errors := true
```

Dieser darf niemals Default sein.

## 13.3 Klassenvergleich

Keine Prüfung über:

```text
endsWith(".ClassName")
```

Stattdessen vollständige Tags vergleichen:

```text
Model.Topic.Class
```

## 13.4 Resultatspalten

Generischer Reader soll mindestens behalten:

```text
xtf_bid
xtf_model
xtf_topic
xtf_class
xtf_class_fqn
xtf_tid
operation
```

## 13.5 NULL-Semantik

Fehlender Wert ist nicht dasselbe wie leerer String.

Anforderungen:

- SQL-NULL für fehlende Werte,
- leerer String nur für tatsächlich vorhandenen leeren String,
- fehlende Integerwerte nicht als `0`,
- fehlende Geometrie nicht als leerer Erfolg.

## 13.6 Geometriefehler

Bei Geometriekonvertierungsfehlern:

- nicht still `""` zurückgeben,
- entweder Queryfehler,
- oder explizite Fehler-/Unsupported-Spalte,
- oder definierter tolerant mode.

## 13.7 Abnahmetests

- zwei Klassen mit gleichem Kurznamen in verschiedenen Topics.
- zwei Modelle mit gleichem Kurznamen.
- abgeschnittene XTF-Datei.
- ungültiges XML.
- ungültige Geometrie.
- leeres Attribut.
- fehlendes Attribut.
- NULL versus leerer String.
- UPDATE/DELETE/INSERT.
- mehrere Baskets.

---

# 14. Phase 8 – Sichere Extraktion und Auflösung der Native-Library

## 14.1 Problem

Die eingebettete Shared Library wird direkt in den finalen Cachepfad geschrieben.

Risiken:

- parallele Prozesse,
- partielle Dateien,
- beschädigte Datei wird später wiederverwendet,
- kein Hash,
- kein atomarer Rename,
- mögliche Symlink-Probleme,
- unklare Versionierung.

## 14.2 Ziel

Deterministische, atomare und überprüfbare Extraktion.

## 14.3 Anforderungen

1. Eingebettete Library erhält Build-Time-SHA-256.
2. Cache-Dateiname enthält mindestens:
   - Extension-Version,
   - ABI-Version,
   - Plattform,
   - Hash.
3. In temporäre Datei im gleichen Verzeichnis schreiben.
4. geschriebenen Inhalt anhand Grösse und Hash prüfen.
5. Datei flushen.
6. atomar umbenennen.
7. bestehende Datei nur verwenden, wenn Hash stimmt.
8. Verzeichnis mit sicheren Rechten anlegen.
9. Symlinks nicht unkontrolliert überschreiben.
10. parallele Extraktion muss sicher sein.
11. Fehler müssen Pfad und Ursache enthalten.
12. `DUCKDB_ILI_NATIVE_LIB` bleibt für Entwicklung möglich, muss aber klar als expliziter Override dokumentiert werden.

## 14.4 Suchreihenfolge

Verbindlich dokumentieren:

1. expliziter Environment Override,
2. gehashter Extension-Cache,
3. nur im Development-Modus lokale Fallbackpfade.

Produktive Builds sollen nicht zufällig eine lokale Entwicklerlibrary laden.

## 14.5 Abnahmetests

- zwei parallele Prozesse.
- beschädigte Cache-Datei.
- unvollständige Cache-Datei.
- falscher Hash.
- read-only Home.
- fehlendes Home.
- Windows USERPROFILE.
- sehr langer Pfad.
- Development Override.

---

# 15. Phase 9 – ABI-Handshake

## 15.1 Problem

Die Extension löst viele Symbole einzeln auf, aber prüft keine ABI-Kompatibilität.

## 15.2 Ziel

Extension und Native-Library müssen vor der ersten fachlichen Operation feststellen, ob sie kompatibel sind.

## 15.3 Empfohlene API

```c
#define ILI_NATIVE_ABI_VERSION 1

typedef struct ili_api_v1 {
    uint32_t struct_size;
    uint32_t abi_version;
    uint64_t capabilities;

    ili_status_code (*version)(...);
    ili_status_code (*validate)(...);
    ili_status_code (*model_info)(...);
    ili_status_code (*read_xtf)(...);
    ili_status_code (*free_result)(...);
} ili_api_v1;

int ili_get_api(
    uint32_t requested_abi_version,
    ili_api_v1 *out_api
);
```

## 15.4 Anforderungen

- `struct_size` prüfen.
- ABI-Version prüfen.
- Capability-Bits.
- Fehlende optionale Fähigkeiten sauber melden.
- Nur ein obligatorisches Einstiegssymbol.
- Abwärtskompatible Erweiterung durch neue Felder am Strukturende.
- Versionsinfo getrennt von ABI-Kompatibilität.

## 15.5 Abnahmetests

- passende ABI.
- zu alte Native-Library.
- zu neue Native-Library.
- fehlende Capability.
- verkürzte Struct.
- falsche Struct-Grösse.

---

# 16. Phase 10 – Importfunktion korrigieren

## 16.1 Benennung

Wenn die Funktion lediglich SQL erzeugt, soll sie heissen:

```text
ili_generate_import_sql
```

Eine Funktion `ili_import_xtf` soll nur bestehen, wenn tatsächlich importiert wird.

## 16.2 `mapping`

Der Parameter darf nicht wirkungslos sein.

Optionen:

1. implementieren,
2. entfernen,
3. bis zur Implementierung klar mit `UNSUPPORTED` ablehnen.

Stilles Ignorieren ist verboten.

## 16.3 Tabellenidentität

Tabellennamen dürfen nicht nur aus dem kurzen Klassennamen bestehen.

Empfohlene Defaultstrategie:

```text
<topic>__<class>
```

oder:

```text
<model>__<topic>__<class>
```

Kollisionen müssen geprüft und als Fehler gemeldet werden.

## 16.4 Identifier

- alle Schema-, Tabellen- und Spaltennamen konsistent quoten,
- SQL-Schlüsselwörter unterstützen,
- Namensnormalisierung dokumentieren,
- Kollision nach Normalisierung erkennen.

## 16.5 Transaktion

Generiertes Importsql muss standardmässig enthalten:

```sql
BEGIN TRANSACTION;
...
COMMIT;
```

Oder es muss ausdrücklich als nicht atomar dokumentiert sein.

Bevorzugt: atomar.

## 16.6 Wiederholung

Importmodus:

```text
create
replace
append
```

Später optional:

```text
upsert
```

Default muss klar sein.

## 16.7 Typmapping

Mindestens prüfen:

- INTERLIS Numeric → `DECIMAL(p,s)` wenn ableitbar,
- Ganzzahl → `BIGINT`,
- Boolean → `BOOLEAN`,
- Date → `DATE`,
- DateTime → `TIMESTAMP`,
- Geometrie → zunächst `BLOB` oder klar dokumentiertes Hex-WKB,
- Enumeration → `VARCHAR`,
- Referenz → `VARCHAR`,
- Strukturen → JSON oder separate relationale Tabellen, klar dokumentiert.

## 16.8 Geometrie

Wenn Hex-WKB verwendet wird:

- Spaltenname und Dokumentation müssen dies klar sagen,
- keine Behauptung, es sei ein DuckDB-GEOMETRY-Wert,
- CRS separat dokumentieren.

Optional:

- Integration mit DuckDB Spatial,
- Ausgabe als `GEOMETRY` nur bei verfügbarer Funktionalität.

## 16.9 Abnahmetests

- gleiche Klasse in zwei Topics.
- SQL-Schlüsselwort als Attributname.
- Quotes im Identifier.
- wiederholter Import.
- Fehler mitten im Import mit Rollback.
- leeres Modell.
- Enumeration.
- Decimal.
- Geometrie.
- Referenz.
- Struktur.

---

# 17. Phase 11 – Build- und Release-Härtung

## 17.1 Release-Build ohne Native-Library

Ein Release-Build muss fehlschlagen, wenn die Native-Library fehlt.

Dummy-Blob nur bei explizitem Development-Schalter:

```text
-DILI_ALLOW_MISSING_NATIVE_LIB=ON
```

Default:

```text
OFF
```

## 17.2 Build-Skripte

- keine hart codierten `/Users/stefan/...`-Pfade,
- `GRAALVM_HOME`,
- `JAVA_HOME`,
- Gradle Toolchains,
- klare Fehlermeldung bei falscher GraalVM-Version.

## 17.3 Gradle Outputs

Plattformspezifischen Output deklarieren:

- `.dylib`,
- `.so`,
- `.dll`.

Up-to-date-Checks müssen funktionieren.

## 17.4 Versionen

Explizit unterscheiden:

```text
DuckDB product version
DuckDB C extension ABI version
Extension version
Native-library version
Native ABI version
INTERLIS core version
GraalVM version
```

Keine missverständliche Verwendung von `v1.2.0` und `v1.5.3`.

## 17.5 Reproduzierbarkeit

- Dependency Locking,
- Gradle Wrapper,
- gepinnte Action-Versionen soweit sinnvoll,
- Build-Metadaten,
- Checksums,
- SBOM,
- Release-Artefakte pro Plattform.

## 17.6 Abnahmekriterien

- Build ohne Native-Library schlägt fehl.
- Build mit falscher GraalVM-Version schlägt verständlich fehl.
- alle Plattformartefakte enthalten korrekte Metadaten.
- installierte Extension findet exakt die passende Native-Library.

---

# 18. Phase 12 – Teststrategie

## 18.1 Testpyramide

### Java-Unit-Tests

- Validator,
- Modellauflösung,
- XTF-Leser,
- Typmapping,
- Fehlerübersetzung,
- Cache.

### Native-ABI-Tests

- Erfolg,
- Fehler,
- Ownership,
- Längen,
- Unicode,
- parallele Aufrufe.

### DuckDB-Integrationstests

- jede SQL-Funktion,
- korrekte Schemas,
- korrekte NULLs,
- DuckDB-Fehler,
- mehrere Aufrufe,
- parallele Queries.

### End-to-End-Tests

- Extension laden,
- Native-Library extrahieren,
- Modelle lesen,
- XTF lesen,
- validieren,
- Import-SQL erzeugen,
- Import durchführen.

## 18.2 Sanitizer

Mindestens auf Linux:

- AddressSanitizer,
- UndefinedBehaviorSanitizer,
- LeakSanitizer.

Wenn realistisch:

- ThreadSanitizer in separatem Job.

## 18.3 Fuzzing

Fuzz-Ziele:

- Request-Parser,
- TSV-Parser solange vorhanden,
- Fehlerpayload-Parser,
- XTF-Reader mit kleinen mutierten Dateien,
- Library-Auflösung.

## 18.4 Regressionstests

Jeder in dieser Spezifikation beschriebene grobe Mangel benötigt einen Regressionstest.

## 18.5 Testdaten

Testmodell mit:

- mehreren Modellen,
- mehreren Topics,
- gleichen Klassennamen,
- Strukturen,
- BAG OF,
- Assoziationen,
- Referenzen,
- Geometrien,
- Constraints,
- AREA,
- Unicode,
- leeren und fehlenden Werten.

---

# 19. Phase 13 – Dokumentation und Betrieb

## 19.1 Produktionsdokumentation

Erforderlich:

```text
docs/installation.md
docs/security.md
docs/native-abi.md
docs/validation-profiles.md
docs/error-handling.md
docs/limitations.md
docs/performance.md
docs/troubleshooting.md
```

## 19.2 Explizite Limitationen

Dokumentieren:

- primär für kleine und mittlere Dateien,
- Resultate werden aktuell vollständig materialisiert,
- grosse Dateien können hohen Speicherbedarf verursachen,
- Parallelität ist möglicherweise absichtlich begrenzt,
- nicht alle INTERLIS-Konstrukte werden relational abgebildet,
- Remote-Model-Repositories benötigen Netzwerkzugriff.

## 19.3 Diagnose

Debug-Ausgabe soll enthalten können:

- gefundener Native-Library-Pfad,
- ABI-Version,
- Extension-Version,
- Cache-Hit/Miss,
- Modellkompilierungsdauer,
- Validierungsdauer,
- XTF-Dateigrösse,
- Resultatzeilen,
- Peak oder ungefähre Payloadgrösse.

Keine sensitiven Inhalte ungefragt loggen.

---

# 20. Phase 14 – Batching und Streaming, bewusst später

## 20.1 Einordnung

Batching ist wichtig, aber technisch anspruchsvoll.

Es soll erst umgesetzt werden, wenn:

- ABI stabil,
- Fehlervertrag stabil,
- Ownership korrekt,
- Lifecycle threadsicher,
- bestehende Funktionen gut getestet,
- Produktionsbetrieb mit kleinen Dateien stabil ist.

Die Extension gilt für den vorgesehenen Einsatz bereits vorher als produktionsreif.

## 20.2 Bestehendes Problem

Heute werden Resultate häufig mehrfach vollständig materialisiert:

```text
Java StringBuilder
→ Java UTF-8 byte[]
→ unmanaged Native-Buffer
→ C-Zeilenkopien
→ DuckDB-Strings
```

Das ist für grosse Dateien ineffizient.

## 20.3 Untersuchung von ilivalidator

Vor dem Design einer eigenen Batching-API muss der Coding-Agent den bestehenden `ilivalidator`-Code untersuchen.

Besonders zu prüfen:

1. Wie werden Modelle gecached?
2. Wie werden XTF-Objekte intern gehalten?
3. Wie funktioniert `SETTING_ALL_OBJECTS_ACCESSIBLE`?
4. Werden Objekte in mehreren Phasen verarbeitet?
5. Gibt es bestehende temporäre Objektablagen?
6. Gibt es Listener oder Callbacks pro Objekt?
7. Gibt es bereits interne Batches oder Reader-Phasen?
8. Wie werden globale Constraints ausgewertet?
9. Welche Prüfungen benötigen Zugriff auf alle Objekte?
10. Welche Prüfungen können objektweise erfolgen?

Die bestehende Cache- und Mehrphasenlogik von `ilivalidator` kann wichtige Anhaltspunkte für eine spätere inkrementelle Verarbeitung liefern.

## 20.4 Mögliches Ziel-ABI

```c
ili_status ili_reader_open(
    ili_context *context,
    const ili_read_request *request,
    ili_reader **out_reader,
    ili_schema **out_schema
);

ili_status ili_reader_next(
    ili_reader *reader,
    size_t max_rows,
    ili_batch **out_batch
);

void ili_batch_free(
    ili_batch *batch
);

void ili_reader_close(
    ili_reader *reader
);
```

## 20.5 Schwierigkeit bei INTERLIS

Batching darf nicht naiv implementiert werden.

Zu beachten:

- Referenzen können auf spätere Objekte zeigen.
- Constraints können globale Objektmengen benötigen.
- AREA-Prüfungen benötigen möglicherweise vollständigen Kontext.
- Assoziationen und Rollen können Querverbindungen erzeugen.
- Basketgrenzen sind fachlich relevant.
- Vererbung und polymorphe Klassen erschweren statische Schemas.
- dynamische Klassenschemata werden bereits in der Bind-Phase benötigt.
- DuckDB erwartet planbare Resultatspalten.

## 20.6 Mögliche Zwischenstufe

Vor echtem columnarem Batching:

1. Native Reader öffnen.
2. XTF-Reader im Java-Kontext offen halten.
3. pro Aufruf maximal N TSV-Zeilen liefern.
4. später TSV durch spaltenorientierte Batches ersetzen.

Diese Zwischenstufe reduziert Peak Memory, ohne sofort eine vollständige columnare ABI zu verlangen.

## 20.7 Langfristige Performanceziele

- Time to first row deutlich kleiner als Gesamtlaufzeit.
- Speicher proportional zur Batchgrösse statt zur Gesamtdatei.
- ein XTF-Durchlauf pro Reader.
- ein XTF-Durchlauf pro Import, soweit fachlich möglich.
- Query Cancellation.
- Pushdown von Klassenfilter soweit möglich.
- Wiederverwendung kompilierter Modelle.

---

# 21. Verbindliche Reihenfolge der Umsetzung

Der Coding-Agent darf die Reihenfolge nur mit klarer Begründung ändern.

## Meilenstein A – Speicher- und Fehlerstabilität

Enthält:

- Phase 0,
- Phase 1,
- Phase 2,
- Phase 3.

Abnahme:

- keine bekannten Allocator-Fehler,
- keine verlorenen Fehlerpayloads,
- klarer Fehlervertrag,
- robuste Requests.

## Meilenstein B – Nebenläufigkeit und Lifecycle

Enthält:

- Phase 4,
- Phase 5,
- Phase 8,
- Phase 9.

Abnahme:

- threadsichere Initialisierung,
- kontrollierter Java-Cache,
- sichere Library-Extraktion,
- ABI-Handshake.

## Meilenstein C – Fachlich korrekte kleine-Dateien-Version

Enthält:

- Phase 6,
- Phase 7,
- Phase 10.

Abnahme:

- klare vollständige Validierungsprofile,
- keine stillen partiellen Resultate,
- korrekte Klassenidentität,
- stabiler Importpfad.

## Meilenstein D – Produktionsrelease

Enthält:

- Phase 11,
- Phase 12,
- Phase 13.

Abnahme:

- grüne Multi-Platform-CI,
- Sanitizer,
- vollständige Dokumentation,
- Release-Checkliste,
- kleine und mittlere Dateien produktiv einsetzbar.

## Meilenstein E – Performanceforschung

Enthält:

- Phase 14.

Kein Blocker für den ersten produktionsreifen Release.

---

# 22. Definition of Done pro Änderung

Eine Änderung gilt nur als abgeschlossen, wenn:

1. Code implementiert.
2. Unit-Test vorhanden.
3. Integrationstest vorhanden, sofern relevant.
4. Fehlerpfad getestet.
5. Ownership dokumentiert.
6. Plattformauswirkungen geprüft.
7. Dokumentation aktualisiert.
8. keine bestehenden Tests deaktiviert.
9. keine Exception still verschluckt.
10. CI grün.
11. Sanitizer grün, falls C-Code betroffen.
12. Changelog-Eintrag vorhanden.

---

# 23. Verbotene Muster

Folgende Muster dürfen nicht neu eingeführt oder beibehalten werden:

```java
catch (Exception e) {
    return null;
}
```

```java
catch (Exception e) {
    return "";
}
```

```c
if (rc != 0) {
    return NULL; // ohne möglichen Payload zu befreien
}
```

```c
free(pointer_from_graalvm);
```

```c
char request[8192];
snprintf(request, ... user_input ...);
```

```java
String[] fields = csvLine.split(",");
```

```java
if (error) {
    return partialResult;
}
```

```text
Status 0 + Text "ERROR: ..."
```

```text
fehlender Wert → leerer String
```

```text
fehlende Integerzahl → 0
```

```text
kurzer Klassenname als alleinige Identität
```

---

# 24. Release-Checkliste für die erste produktionsreife Version

## Native und ABI

- [ ] keine Allocator-Mismatches
- [ ] alle Resultate werden exakt einmal freigegeben
- [ ] Fehlerpayloads werden freigegeben
- [ ] ABI-Handshake
- [ ] inkompatible Library wird abgelehnt
- [ ] Initialisierung threadsicher

## Fehler

- [ ] keine `ERROR:`-Pseudoresultate
- [ ] keine stillen leeren Resultate
- [ ] keine partiellen Resultate bei technischen Fehlern
- [ ] Originalursache in DuckDB sichtbar
- [ ] Fehlercodes dokumentiert

## Validierung

- [ ] Validierungsprofile
- [ ] Default klar definiert
- [ ] Constraint-Validierung getestet
- [ ] AREA-Validierung getestet
- [ ] korrekter Meldungsparser
- [ ] parallele Validierung getestet

## XTF

- [ ] vollständiger Klassenname
- [ ] NULL-Semantik
- [ ] kaputte Datei erzeugt Fehler
- [ ] Geometriefehler nicht still
- [ ] mehrere Baskets getestet

## Import

- [ ] keine Tabellenkollisionen
- [ ] `mapping` implementiert oder abgelehnt
- [ ] Transaktion
- [ ] Wiederholungsmodus
- [ ] konsistentes Quoting
- [ ] Typmapping dokumentiert

## Build

- [ ] kein Release ohne Native-Library
- [ ] keine lokalen hart codierten Pfade
- [ ] klare Versionsmetadaten
- [ ] vier Zielplattformen
- [ ] Artefaktchecksums
- [ ] Smoke-Tests

## Qualität

- [ ] ASan
- [ ] UBSan
- [ ] LSan
- [ ] parallele Tests
- [ ] Regressionstests
- [ ] Dokumentation
- [ ] bekannte Limitationen

---

# 25. Erwartetes Endergebnis

Nach Abschluss der Phasen 0 bis 13 soll das Repository folgende Eigenschaften besitzen:

1. Die Extension lädt reproduzierbar die passende Native-Library.
2. ABI-Inkompatibilitäten werden früh erkannt.
3. Fehler werden vollständig und konsistent an DuckDB übertragen.
4. Speicher wird in allen Pfaden korrekt verwaltet.
5. Kleine und mittlere XTF-Dateien können zuverlässig:
   - validiert,
   - analysiert,
   - gelesen,
   - und in DuckDB überführt werden.
6. Parallele DuckDB-Aufrufe führen nicht zu undefiniertem Verhalten.
7. Der Validierungsumfang ist fachlich eindeutig.
8. Der Import erzeugt keine unbemerkten Tabellenkollisionen oder Teilimporte.
9. CI, Sanitizer und Integrationstests schützen gegen Regressionen.
10. Die Grenzen bezüglich grosser Dateien sind offen dokumentiert.
11. Batching bleibt als klar vorbereitete spätere Optimierungsphase bestehen.

Der Coding-Agent soll Robustheit vor Eleganz, Diagnosefähigkeit vor Stille und Korrektheit vor maximaler Parallelität priorisieren.
