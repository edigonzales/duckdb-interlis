# Version policy

`VERSION` is the duckdb-interlis extension version. The current native MVP is
`0.2.0` and targets DuckDB product version `1.5.3`.

`interlis_components()` reports the runtime component versions and revisions:

- duckdb-interlis;
- ilic;
- iox-cpp;
- GEOS;
- DuckDB.

Release artifacts are placed below the DuckDB product-version directory, for
example `v1.5.3/osx_arm64/interlis.duckdb_extension`. A matching artifact must
be built when the DuckDB extension ABI or product version changes.

There is no Java, Gradle, GraalVM, native-image, or text-protocol version in the
native release contract.
