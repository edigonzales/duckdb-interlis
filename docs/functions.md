# Native SQL functions

All model-bearing functions take a `VARCHAR[]` of local model sources. A source
is either one regular `.ili` file or a non-recursive directory containing `.ili`
files. See [model-sources.md](model-sources.md).

## Version and components

```sql
SELECT interlis_version();
SELECT * FROM interlis_components();
```

`interlis_components()` returns `component`, `version`, and `revision` for the
extension, ilic, iox-cpp, GEOS, and DuckDB.
The GEOS row reports `disabled` for the default GEOS-free build and the
configured GEOS version family for a strict build.

## Model introspection

```sql
SELECT * FROM ili_models(['/models/base.ili']);
SELECT * FROM ili_classes(['/models/base.ili'], model := 'MyModel');
SELECT * FROM ili_properties('MyModel.Data.Feature', ['/models/base.ili']);
SELECT * FROM ili_geometry_properties('MyModel.Data.Feature', ['/models/base.ili']);
```

Class rows follow model/topic/declaration order. Property rows follow transfer
order. Geometry rows expose geometry kind, coordinate domain, dimension,
`MAX OVERLAPS`, line forms, and the native straight/arc/custom/attribute flags.

## `xtf_scan`

```sql
SELECT _tid, Name, Geometry
FROM xtf_scan('/data/input.xtf',
               'MyModel.Data.Feature',
               ['/models/base.ili'],
               geometry_errors := 'null');
```

Signature:

```text
xtf_scan(path, class_name, model_sources,
         geometry_errors := 'error', arc_tolerance_override := NULL)
```

The result starts with `_bid`, `_tid`, `_class`, `_operation`, and
`_unsupported_json`, followed by supported primitive and geometry properties in
model transfer order. Missing values are SQL `NULL`. Role references,
structures, collections, and other unsupported values are represented in the
diagnostic JSON column. `geometry_errors := 'null'` keeps a row and records the
conversion diagnostic; the default raises an error. Structural geometry
conversion checks are always performed. Native topological validation is only
performed by a build with `INTERLIS_ENABLE_GEOS=ON`; in a GEOS-free build,
DuckDB Spatial can be loaded and `ST_IsValid` can be used on the returned
`GEOMETRY` values.

## `xtf_values`

```sql
SELECT *
FROM xtf_values('/data/input.xtf',
                'MyModel.Data.Feature',
                'DetailsValue.Label',
                ['/models/base.ili'],
                tid := 'F1');
```

The result is `(bid, tid, class_name, occurrence, value)`.
Paths support a first value, a one-based index such as `Tags[2].Value`, and a
wildcard such as `Tags[*].Value`. Values are returned as primitive lexical
strings in transfer order.

## `xtf_set`

```sql
SELECT *
FROM xtf_set('/data/input.xtf',
             '/data/output.xtf',
             'MyModel.Data.Feature',
             'F1', 'Name', 'updated',
             ['/models/base.ili'],
             expected := 'old');
```

The function returns the matched BID/TID/class/path, old and new lexical
values, and a status/message. It updates exactly one primitive value, requires
distinct input/output files, and uses a temporary output plus final move. Set
`overwrite := true` to replace an existing output. It does not modify input in
place or generate an UPDATE transfer.
