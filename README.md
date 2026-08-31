# duckdb-interlis

`duckdb-interlis` ist eine vollständig native DuckDB-Extension für lokale
INTERLIS-/XTF-Workflows. Sie verbindet
[`ilic`](https://github.com/edigonzales/ilic-fork) und
[`iox-cpp`](https://github.com/edigonzales/iox-cpp) mit DuckDB 1.5.5 – ohne
Java, GraalVM oder zusätzlichen Laufzeitdienst.

## Schnellstart

Ein lokal oder aus dem projektspezifischen Repository geladenes Artefakt ist
unsigniert:

```sh
duckdb -unsigned
```

```sql
LOAD '/path/to/interlis.duckdb_extension';
SELECT interlis_version();

SELECT *
FROM xtf_scan('/path/to/data.xtf',
              'MyModel.MyTopic.MyClass',
              ['/path/to/model.ili']);
```

Nach einer publizierten DuckDB-Community-Version steht stattdessen der
signierte Standardkanal zur Verfügung:

```sql
INSTALL interlis FROM community;
LOAD interlis;
```

Details zu beiden Kanälen stehen unter [Installation](docs/installation.md).

## Funktionsumfang

- Modell-, Klassen-, Property- und Geometrie-Introspektion
- streamingbasierte typisierte XTF-Abfragen
- Zugriff auf primitive IOM-Pfade
- sichere Änderung eines einzelnen primitiven Werts in eine neue XTF-Datei
- native `GEOMETRY`-Werte, optional mit GEOS-Prüfung
- vollständige Komponenten- und Source-SHA-Auskunft

Modelle werden ausschliesslich aus lokalen `.ili`-Dateien oder
nichtrekursiven lokalen Verzeichnissen geladen. Vollständige INTERLIS-
Datenvalidierung, entfernte Modell-Repositories, `ATTACH` und In-place-Updates
gehören nicht zum aktuellen Vertrag.

## Entwicklung und Release

- [Lokaler Build und Tests](docs/development.md)
- [Architektur](docs/architecture.md)
- [SQL-Funktionen](docs/functions.md)
- [Modelquellen](docs/model-sources.md)
- [Release und DuckDB Community](docs/release.md)
- [Einschränkungen](docs/limitations.md)

Die übergreifende ilic/iox/vcpkg-Abhängigkeitsmatrix steht in der
[zentralen Ökosystemübersicht](https://github.com/edigonzales/ilic-fork/blob/main/docs/ecosystem.md).

## Lizenz

MIT, siehe [LICENSE](LICENSE).
