# Migration from the GraalVM implementation

The native MVP supersedes the former Java/GraalVM implementation. Existing
deployments must load a newly built native C++ extension; the old extension
binary and its separately built native library are not interchangeable with
this release.

## What changed

- model compilation is provided directly by ilic and indexed by iox-cpp;
- XTF input is streamed by iox-cpp rather than transported through a text
  protocol;
- DuckDB `GEOMETRY` values are produced from native WKB projection;
- the extension is built by the DuckDB C++ extension template;
- local `.ili` files and directories replace repository/URL model resolution;
- validation, generic legacy readers, SQL import generation, and `ATTACH` are
  outside the MVP.

Remove old deployment settings such as `DUCKDB_ILI_NATIVE_LIB` and any Gradle
or GraalVM build steps. Build the extension with `scripts/build-extension.sh`
or the root Makefile, then load the resulting
`build/release/extension/interlis/interlis.duckdb_extension` with DuckDB's
`-unsigned` option.

## Function mapping

| Former concern | Native MVP replacement |
|---|---|
| Native version handshake | `interlis_version()`, `interlis_components()` |
| Model metadata | `ili_models`, `ili_classes`, `ili_properties`, `ili_geometry_properties` |
| Typed class reading | `xtf_scan` |
| Primitive nested values | `xtf_values` |
| Single primitive rewrite | `xtf_set` |
| Validator calls | Not available; validate before calling the extension |

The migration is intentionally breaking. A query that uses a removed function
should be rewritten rather than routed through a compatibility bridge.
