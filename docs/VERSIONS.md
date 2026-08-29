# Version policy

`VERSION` is the independent duckdb-interlis extension version. Development
after the existing `v0.2.0` release uses `0.2.1`; DuckDB itself is pinned to
`1.5.5`. All machine-readable dependency identities live in
`release/dependencies.lock.json` and are copied into build provenance by
`scripts/release_metadata.py`.

Before 1.0, compatible fixes and internal dependency updates increment patch
(`0.2.0` to `0.2.1`). A breaking public API or ABI change increments minor
(`0.2.x` to `0.3.0`). Once the public contract is stable, the project should
move to 1.0 and normal Semantic Versioning.

`interlis_components()` reports:

- duckdb-interlis with its full build commit (`-dirty` for a dirty checkout);
- ilic and iox-cpp with their full actual local/fetched SHA or installed lock SHA;
- GEOS with `disabled` or the actual package version;
- DuckDB with runtime version and source ID.

`working-tree` is used only when no Git SHA can be determined. The DuckDB
runtime values are never hard-coded. The locked native binary-cache build uses
ilic `e901af64247082b5164252b675d87bd7a2aa829d` and iox-cpp
`c82fd5f5a2cd8c1a06eef7b98f492055fb954460`.

Third-party versions deliberately differ by tested build role:

| Build role | Expat | yyjson | GEOS | Gate |
| --- | --- | --- | --- | --- |
| Local/source integration | 2.6.4 | 0.12.0 | 3.14.1 if enabled | blocking local build |
| Fast native binary-cache CI | 2.8.1 | 0.12.0 | 3.14.1 if enabled | blocking, binary-only restore |
| DuckDB Community preflight | 2.7.3 | 0.12.0 | 3.14.1 if enabled | blocking community source fallback |

These are an explicit compatibility matrix, not an accidental claim that all
builds use one Expat binary.

Historical `v0.2.0` remains frozen in `release/history/v0.2.0.json`: DuckDB
1.5.3, iox `600d191e387405b3e957617f7a1e6dd7a29a1d94`, and ilic
`cd74490b1fddfe38ac80288067e1af0dd800e8da`.
