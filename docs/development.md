# Entwicklung

Benötigt werden die in `release/dependencies.lock.json` festgelegte
DuckDB-Version, CMake 4.1 oder neuer, ein C++17-Compiler und initialisierte
DuckDB-/extension-ci-tools-Submodule. GEOS ist optional.

```sh
cp scripts/env.example.sh scripts/env.sh
source scripts/env.sh
scripts/doctor.sh
INTERLIS_ENABLE_GEOS=OFF scripts/build-all.sh
```

Für zusätzliche topologische Geometrieprüfung einen frischen Build verwenden:

```sh
INTERLIS_ENABLE_GEOS=ON scripts/build-all.sh
```

Äquivalente Template-Targets sind `make debug`, `make release`,
`make test_debug` und `make test_release`.

## Abhängigkeitswege

- `INTERLIS_ILIC_SOURCE_DIR` und `INTERLIS_IOX_SOURCE_DIR` prüfen lokale
  Geschwister-Working-Trees.
- Ohne Overrides verwendet ein Source-Build die in der Lock-Datei festgelegten
  FetchContent-Stände.
- Der schnelle Projekt-CI stellt `iox-cpp[ilic]` und ilic strikt aus dem
  privaten vcpkg-Binary-Cache wieder her.
- DuckDB Community CI kann diesen privaten Cache nicht voraussetzen und baut
  aus den öffentlichen Vorlagen unter `vcpkg/ports`.

`python3 scripts/release_metadata.py sync` erzeugt vcpkg-Manifeste aus dem
Lock; `check` weist Abweichungen zurück. Expat-Versionen können sich zwischen
Source-Integration, schnellem Binary-CI und Community-Toolchain unterscheiden;
jeder Weg ist im Lock beziehungsweise in seinen Manifesten explizit gepinnt.

## Tests

```sh
python3 scripts/release_metadata.py check
python3 test/release_metadata_test.py
scripts/build-all.sh
git diff --check
```

SQLLogicTests liegen unter `test/sql/`. Native Resolver-Tests werden mit
`-DINTERLIS_BUILD_NATIVE_TESTS=ON` aktiviert. Kleine deterministische Fixtures
liegen unter `testdata/native/`.

Ein Wechsel des GEOS-Modus benötigt ein neues oder bereinigtes
`build/debug`/`build/release`. Die Skripte löschen keine fremden oder veralteten
CMake-Buildverzeichnisse automatisch.
