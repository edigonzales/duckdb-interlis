# Versionierung und Publikation

`VERSION` ist die unabhängige Extension-Version. Der aktuelle
Entwicklungsstand ist `0.2.1`; DuckDB ist in
`release/dependencies.lock.json` auf `1.5.5` festgelegt. Vor 1.0 erhöhen
kompatible Korrekturen den Patch, öffentliche Brüche den Minor.

Alle ilic-, iox-, vcpkg- und DuckDB-Identitäten stehen im Lock und werden von
`scripts/release_metadata.py` in die Provenienz kopiert. Nicht hartcodierte
Runtime-Werte sind zusätzlich über `interlis_components()` sichtbar.

Es gibt zwei unabhängige Publikationen:

1. der eigene, unsignierte GitHub-/Extension-Repository-Release;
2. der von DuckDB gebaute und signierte Community-Release.

Ein eigener Tag aktualisiert das Community-Repository nicht automatisch.

## Vorbereitung

1. `VERSION`, `CHANGELOG.md`, Dependency-Lock und bei Bedarf Submodule
   aktualisieren.
2. Erzeugte Dependency-Dateien synchronisieren und prüfen:

   ```sh
   python3 scripts/release_metadata.py sync
   python3 scripts/release_metadata.py check
   python3 test/release_metadata_test.py
   scripts/build-all.sh
   ```

3. `interlis_components()` sowie repräsentative Lese-, Geometrie- und
   Rewrite-Abfragen prüfen.
4. Den Release-Commit reviewen und CI vollständig abschliessen.

## Eigenen Release publizieren

Das unveränderliche Tag muss exakt `v$(cat VERSION)` entsprechen, für den
aktuellen Stand also `v0.2.1`. Ein Tag startet die normale CI und:

- stellt gelockte native Abhängigkeiten aus dem vcpkg-Binary-Cache wieder her;
- baut und testet Linux x86_64, macOS ARM64 und Windows x86_64;
- erzeugt Extension, SHA-256 und Provenienz pro Plattform;
- deployt das projektspezifische Repository für DuckDB 1.5.5;
- erstellt einen GitHub Release, ohne vorhandene Tags oder Assets zu ersetzen.

Projektartefakte sind nicht durch DuckDB signiert und benötigen `-unsigned`.
`repair-release.yml` baut ein vorhandenes Tag nur als kurzlebigen Vergleich
neu und besitzt keine Schreibrechte auf Release oder Deployment.

## DuckDB Community publizieren

Voraussetzung ist ein bereits vorhandenes öffentliches Release-Tag mit einem
exakten, unveränderlichen Commit. Danach:

1. [duckdb/community-extensions](https://github.com/duckdb/community-extensions)
   forken und einen Branch erstellen.
2. Nur den Deskriptor
   [`extensions/interlis/description.yml`](https://github.com/duckdb/community-extensions/blob/main/extensions/interlis/description.yml)
   für den normalen Versionswechsel anpassen:
   - `extension.version` auf `0.2.1` beziehungsweise die neue Version setzen;
   - `repo.ref` auf den vollständigen 40-stelligen Commit hinter dem
     Release-Tag setzen;
   - Repository und übrige Metadaten unverändert lassen, sofern sie sich nicht
     fachlich geändert haben.
3. Den Deskriptor gegen das öffentliche Repository prüfen und einen Pull
   Request eröffnen. Die Community-CI baut die Extension mit ihren öffentlichen
   Source-/Overlay-Ports für die unterstützten Plattformen. Der private
   GitHub-Packages-vcpkg-Cache ist dort nicht verfügbar und darf keine
   Voraussetzung sein.
4. CI-Ergebnisse und Review abwarten. Erst der Merge macht diesen Stand zum
   aktiven Community-Quellstand; DuckDB baut, signiert und verteilt die
   Community-Artefakte.
5. Nach Verfügbarkeit mit einer passenden DuckDB-Version prüfen:

   ```sql
   INSTALL interlis FROM community;
   LOAD interlis;
   SELECT interlis_version();
   SELECT * FROM interlis_components();
   ```

   Bei einer bereits installierten älteren Ausgabe kann abhängig von der
   DuckDB-Version zusätzlich `UPDATE EXTENSIONS;` nötig sein.

Die offizielle [Update-Anleitung](https://github.com/duckdb/community-extensions/blob/main/UPDATING.md)
und die [Entwicklungsdokumentation](https://duckdb.org/community_extensions/development)
sind bei Änderungen am Community-Prozess massgeblich.

### Wechsel auf eine neue DuckDB-Version

Rund um einen noch nicht veröffentlichten DuckDB-Release kann der Deskriptor
`ref_next` verwenden: `repo.ref` bleibt für die aktuelle DuckDB-Linie aktiv,
`repo.ref_next` zeigt auf den dafür vorbereiteten Extension-Stand. Mit dem
DuckDB-Release wird der vorbereitete Stand zum aktiven Ref. Ohne notwendige
Quelländerung baut die Community-Infrastruktur Extensions für neue stabile
DuckDB-Versionen automatisch neu; der Community-PR ist dann nur bei
Inkompatibilität oder einer neuen Extension-Version nötig.

Der Standard-Community-Kanal bietet keinen Versionsselektor für historische
Extension-Releases. Wer einen alten Stand benötigt, muss ein dazu passendes
eigenes Repository-/Projektartefakt verwenden und die Signatur-/`-unsigned`-
Konsequenz bewusst behandeln.

## Fehler und Unveränderlichkeit

- Ein vorhandenes Tag, GitHub Release oder Community-Version wird nicht
  überschrieben.
- Ein Fehler vor dem Release-Job publiziert nichts. Nach Teilfehlern werden nur
  fehlende, nachweislich identische Artefakte ergänzt.
- Eine Quellkorrektur nach einem Release benötigt eine neue Extension-Version.
- `release/history/v0.2.0.json` bleibt als maschinenlesbare historische
  Regressionsreferenz erhalten; aktuelle Dokumentation dupliziert diese Werte
  nicht.
