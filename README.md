# duckdb-interlis

`duckdb-interlis` is a fully native C++ DuckDB extension for local INTERLIS/XTF
workflows. It integrates [`ilic`](https://github.com/edigonzales/ilic-fork),
[`iox-cpp`](https://codeberg.org/edigonzales/iox-cpp), and GEOS with DuckDB
1.5.3. The native MVP has no validator, accepts local model sources only, and
does not implement `ATTACH` integration.

The MVP focuses on deterministic model introspection, streaming typed XTF reads,
primitive path reads, and one-value rewrites. It has no Java runtime, GraalVM
artifact, embedded native library, or C bridge.

## Quick start

Start DuckDB with unsigned extensions enabled and load the extension:

```sh
duckdb -unsigned
```

```sql
LOAD '/path/to/interlis.duckdb_extension';
SELECT *
FROM xtf_scan('/path/to/data.xtf',
               'MyModel.MyTopic.MyClass',
               ['/path/to/model.ili']);
```

`xtf_set` writes a rewritten transfer to a distinct output file after exactly
one matching object has been found:

```sql
SELECT *
FROM xtf_set('/path/to/data.xtf',
             '/path/to/data-updated.xtf',
             'MyModel.MyTopic.MyClass',
             'obj-1', 'Name', 'new value',
             ['/path/to/model.ili']);
```

See [docs/functions.md](docs/functions.md) for the complete native SQL API and
[docs/limitations.md](docs/limitations.md) for the intentionally narrow MVP
scope.

## Build from source

Prerequisites on macOS ARM64 are DuckDB 1.5.3, CMake 4.1 or newer, an AppleClang
toolchain, and a local vcpkg installation with GEOS available. The sibling
checkouts can be supplied explicitly:

```sh
cp scripts/env.example.sh scripts/env.sh
source scripts/env.sh
scripts/doctor.sh
scripts/build-all.sh
```

For a local development checkout, `scripts/env.sh` may set
`INTERLIS_ILIC_SOURCE_DIR`, `INTERLIS_IOX_SOURCE_DIR`, `VCPKG_TOOLCHAIN_PATH`,
and `DUCKDB_CLI`. Without sibling overrides, CMake fetches the pinned ilic and
iox-cpp revisions recorded in `CMakeLists.txt`.

The extension artifact is written below
`build/release/extension/interlis/interlis.duckdb_extension`. A direct local
load uses `-unsigned`:

```sh
scripts/dev-duckdb.sh
```

Run the native SQL smoke test with:

```sh
scripts/smoke-test.sh
```

The root Makefile remains the canonical extension-template entry point:

```sh
make debug
make release
make test_release
```

## Native API overview

The current functions are:

- `interlis_version()` and `interlis_components()`;
- `ili_models`, `ili_classes`, `ili_properties`, and
  `ili_geometry_properties`;
- `xtf_scan`, `xtf_values`, and `xtf_set`.

Model sources are local `.ili` files or non-recursive directories containing
`.ili` files. XTF input and output are local regular files. Unsupported object
content is preserved in `_unsupported_json` where the scan contract allows it;
rewrites never modify the input file in place.

## Documentation

- [Native architecture](docs/native-architecture.md)
- [Functions](docs/functions.md)
- [Geometry](docs/geometry.md)
- [Model sources](docs/model-sources.md)
- [Limitations](docs/limitations.md)
- [Development](docs/development.md)
- [Release and publishing](docs/release.md)
- [Migration from GraalVM](docs/migration-from-graalvm.md)
- [Installation](docs/installation.md)
- [Security](docs/security.md)
- [Changelog](CHANGELOG.md)

## License

MIT — see [LICENSE](LICENSE).
