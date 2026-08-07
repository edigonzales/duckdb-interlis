# Version policy

`VERSION` is the duckdb-interlis extension version. The current native MVP is
`0.2.0` and targets DuckDB product version `1.5.3`.

`interlis_components()` reports the runtime component versions and revisions:

- duckdb-interlis;
- ilic;
- iox-cpp;
- GEOS;
- DuckDB.

The fetched ilic compiler source is pinned to commit
`c5c37108fe2cde282aa9a29ff6b2a01cf4e2974e`, which includes the 3D coordinate
domain clone and third-axis bound fixes used by the native geometry path.

For a GEOS-free build, the GEOS component version is reported as `disabled`.
For a strict build, it reports the configured GEOS version family.

Release artifacts are placed below the DuckDB product-version directory, for
example `v1.5.3/osx_arm64/interlis.duckdb_extension`. A matching artifact must
be built when the DuckDB extension ABI or product version changes.

There is no Java, Gradle, GraalVM, native-image, or text-protocol version in the
native release contract.
