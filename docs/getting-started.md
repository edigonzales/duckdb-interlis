# Getting started

This guide builds and uses the native DuckDB extension from a local checkout on
macOS ARM64. The native MVP targets DuckDB 1.5.3 and accepts local `.ili` model
files or non-recursive model directories only.

## 1. Build a local installation

Install AppleClang, CMake 4.1 or newer, DuckDB 1.5.3, and Git. The default
extension build is GEOS-free and does not require vcpkg, GEOS, or `pkg-config`.

For the optional strict geometry build, install vcpkg with the pinned baseline:

```sh
git clone https://github.com/microsoft/vcpkg.git /path/to/vcpkg
cd /path/to/vcpkg
git checkout ce613c41372b23b1f51333815feb3edd87ef8a8b
./bootstrap-vcpkg.sh -disableMetrics
./vcpkg install \
  --x-feature=geos \
  --x-manifest-root=/path/to/duckdb-interlis \
  --x-install-root=/path/to/vcpkg-installed
```

Set the corresponding `VCPKG_TOOLCHAIN_PATH`, `VCPKG_TARGET_TRIPLET=arm64-osx`,
and `VCPKG_INSTALLED_DIR` values in the copied `scripts/env.sh`. The sibling
ilic/iox source checkouts are optional; without them, CMake uses the pinned
revisions in `CMakeLists.txt`.

From the repository root:

```sh
cp scripts/env.example.sh scripts/env.sh
source scripts/env.sh
scripts/doctor.sh
scripts/build-all.sh
```

`scripts/build-all.sh` defaults to the GEOS-free profile. To enable native
topological geometry validation, use a fresh or cleaned build directory and
run:

```sh
INTERLIS_ENABLE_GEOS=ON scripts/build-all.sh
```

The GEOS-free profile still emits DuckDB `GEOMETRY` values for supported
geometries. It performs structural conversion checks, but not native
topological validity checks.

The release extension is written to:

```text
build/release/extension/interlis/interlis.duckdb_extension
```

If a build reports a stale CMake source directory, run
`scripts/clean-local.sh` and repeat the build. The build scripts never remove a
stale build automatically.

Start an interactive DuckDB session with the local extension:

```sh
scripts/dev-duckdb.sh
```

The `-unsigned` flag is required for a locally built extension. The following
SQL statements use the repository fixtures, so they can be copied verbatim into
that session.

## 2. Check the installation

```sql
SELECT interlis_version();
SELECT * FROM interlis_components();
```

The version reports the native extension and ilic snapshot versions. The
component table reports the extension, ilic, iox-cpp, GEOS, and DuckDB
revisions. In the default build, the GEOS component is reported as
`disabled`.

## 3. Inspect a local model

The fixture model is `testdata/native/introspection.ili`.

```sql
SELECT *
FROM ili_models(['testdata/native/introspection.ili']);

SELECT *
FROM ili_classes(
  ['testdata/native/introspection.ili'],
  model := 'NativeIntrospection'
);

SELECT *
FROM ili_properties(
  'NativeIntrospection.Data.Feature',
  ['testdata/native/introspection.ili']
);

SELECT *
FROM ili_geometry_properties(
  'NativeIntrospection.Data.Feature',
  ['testdata/native/introspection.ili']
);
```

Use the fully qualified class name returned by `ili_classes` when calling the
transfer functions.

## 4. Read a transfer

The matching XTF fixture is `testdata/native/simple.xtf`.

```sql
SELECT _bid, _tid, _class, _operation, Name
FROM xtf_scan(
  'testdata/native/simple.xtf',
  'NativeIntrospection.Data.Feature',
  ['testdata/native/introspection.ili']
);
```

Geometry conversion errors normally abort the query. To keep the row and
receive a diagnostic instead, use:

```sql
SELECT _tid, Name, _unsupported_json
FROM xtf_scan(
  'testdata/native/simple.xtf',
  'NativeIntrospection.Data.Feature',
  ['testdata/native/introspection.ili'],
  geometry_errors := 'null'
);
```

If spatial SQL functions are needed, load DuckDB Spatial separately. This is
optional and is not a build dependency of the GEOS-free Interlis extension:

```sql
INSTALL spatial;
LOAD spatial;

SELECT _tid,
       ST_GeometryType(Point) AS geometry_type,
       ST_IsValid(Point) AS is_valid,
       ST_AsText(Point) AS wkt
FROM xtf_scan(
  'testdata/native/scan.xtf',
  'NativeScan.Data.Feature',
  ['testdata/native/scan.ili']
)
WHERE Point IS NOT NULL;
```

The complete spatial example is [sql/spatial.sql](../sql/spatial.sql).

Read primitive values with optional TID and BID filters. Paths can address a
nested primitive, an indexed occurrence, or all occurrences through a wildcard.

```sql
SELECT *
FROM xtf_values(
  'testdata/native/simple.xtf',
  'NativeIntrospection.Data.Feature',
  'Name',
  ['testdata/native/introspection.ili'],
  tid := 'F1',
  bid := 'B1'
);

SELECT tid, occurrence, value
FROM xtf_values(
  'testdata/native/scan.xtf',
  'NativeScan.Data.Feature',
  'Tags[*].Value',
  ['testdata/native/scan.ili'],
  tid := 'F1',
  bid := 'B1'
);
```

The result columns are `bid`, `tid`, `class_name`, `occurrence`, and `value`.

## 5. Rewrite one primitive value

`xtf_set` writes a new transfer through a temporary file and moves it into
place. It requires a distinct output path and changes exactly one primitive
property of exactly one object.

```sh
rm -f /tmp/duckdb-interlis-getting-started-updated.xtf
```

```sql
SELECT *
FROM xtf_set(
  'testdata/native/simple.xtf',
  '/tmp/duckdb-interlis-getting-started-updated.xtf',
  'NativeIntrospection.Data.Feature',
  'F1',
  'Name',
  'updated',
  ['testdata/native/introspection.ili'],
  bid := 'B1',
  expected := 'alpha',
  overwrite := true
);
```

The input remains unchanged. `xtf_set` does not update geometry, generate an
UPDATE transfer, or perform complete INTERLIS data validation.

## 6. Run all examples

The complete executable example is maintained in
[`sql/examples/10-native-mvp.sql`](../sql/examples/10-native-mvp.sql):

```sh
rm -f /tmp/duckdb-interlis-native-mvp-updated.xtf
scripts/dev-duckdb.sh < sql/examples/10-native-mvp.sql
rm -f /tmp/duckdb-interlis-native-mvp-updated.xtf
```

See [functions.md](functions.md) for complete signatures,
[model-sources.md](model-sources.md) for source resolution rules,
[troubleshooting.md](troubleshooting.md) for common errors, and
[limitations.md](limitations.md) for the deliberate MVP boundaries.
