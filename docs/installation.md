# Installation

Es gibt zwei getrennte Distributionskanäle.

## Projektartefakte

GitHub Releases und das projektspezifische Extension-Repository enthalten
unsignierte Binaries für die dort ausgewiesene DuckDB-Produktversion. DuckDB
muss deshalb mit `-unsigned` gestartet werden:

```sh
duckdb -unsigned
```

```sql
LOAD '/absolute/path/interlis.duckdb_extension';
SELECT interlis_version();
SELECT * FROM interlis_components();
```

Zum Download gehören die jeweilige `.sha256`-Datei und das
plattformbezogene Provenienzmanifest. Extension und DuckDB müssen dieselbe
Produktversion verwenden.

## DuckDB Community Extensions

Sobald die gewünschte Version im Community-Deskriptor aktiv und für die lokale
DuckDB-Version gebaut ist, verwendet DuckDB signierte Community-Binaries:

```sql
INSTALL interlis FROM community;
LOAD interlis;
SELECT interlis_version();
```

Der Standardkanal wählt den im Community-Repository aktiven Source-Stand; eine
beliebige historische Extension-Version kann bei `INSTALL … FROM community`
nicht ausgewählt werden. Vor einem Upgrade daher DuckDB-Version,
Community-Deskriptor und Extension-Changelog prüfen. Der vollständige
Publikationsablauf steht unter [Release](release.md#duckdb-community-publizieren).

## Lokaler Build

```sh
cp scripts/env.example.sh scripts/env.sh
source scripts/env.sh
scripts/doctor.sh
scripts/build-all.sh
```

Das Artefakt liegt danach unter
`build/release/extension/interlis/interlis.duckdb_extension`. Voraussetzungen,
vcpkg und GEOS sind unter [Entwicklung](development.md) dokumentiert.

SQL-Funktionen akzeptieren lokale `.ili`-Dateien oder nichtrekursive lokale
Verzeichnisse; siehe [Modellquellen](model-sources.md).
