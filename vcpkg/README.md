# vcpkg-Eingaben

`native-deps/` wird aus `release/dependencies.lock.json` erzeugt und stellt
publizierte ilic-/iox-Pakete für den schnellen Projekt-CI wieder her.

`ports/` enthält öffentliche Overlay-/Source-Vorlagen für DuckDB Community und
Offline-Fallbacks. Es ist kein Versionskatalog; publizierte unveränderliche
Versionen liegen im gemeinsamen `ilic-fork/vcpkg-registry`.

```sh
python3 scripts/release_metadata.py sync
python3 scripts/release_metadata.py check
```

Die Cache- und Consumer-Matrix steht in der
[zentralen Ökosystemdokumentation](https://github.com/edigonzales/ilic-fork/blob/main/docs/ecosystem.md#vcpkg-registry-und-binary-cache).
