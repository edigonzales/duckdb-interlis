# Version policy

`VERSION` is the duckdb-interlis extension version. The current native MVP is
`0.2.0` and targets the DuckDB 1.5 release line; the project submodule is pinned
to DuckDB 1.5.5 for the current build.

`interlis_components()` reports the runtime component versions and revisions:

- duckdb-interlis;
- ilic;
- iox-cpp;
- GEOS;
- DuckDB.

The DuckDB version and source ID are read from the DuckDB library at runtime,
not hard-coded into the extension. The fallback ilic compiler source is pinned
to commit `cd74490b1fddfe38ac80288067e1af0dd800e8da`; iox-cpp is pinned to
`600d191e387405b3e957617f7a1e6dd7a29a1d94`. The root vcpkg overlay ports use
the same source revisions.

For a GEOS-free build, the GEOS component version is reported as `disabled`.
For a strict build, it reports the configured GEOS version family.

Project release artifacts are placed below the DuckDB product-version directory,
for example `v1.5.5/osx_arm64/interlis.duckdb_extension`. A matching artifact
must be built when the DuckDB extension ABI or product version changes.

There is no Java, Gradle, GraalVM, native-image, or text-protocol version in the
native release contract.
