# vcpkg inputs

`native-deps/` is generated from `release/dependencies.lock.json` and restores
the published ilic/iox packages for fast project CI.

`ports/` contains overlay/templates at the last verified source revisions for
DuckDB Community and offline source-fallback builds. It is not a version
catalogue; published immutable versions live in the shared
`ilic-fork/vcpkg-registry` branch. Run `python3 scripts/release_metadata.py
sync` after intentionally changing the lock and `check` in CI.
