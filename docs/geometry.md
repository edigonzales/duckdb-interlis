# Geometry

Geometry metadata is compiled from ilic and exposed through
`ili_geometry_properties`. Geometry values from `xtf_scan` are native DuckDB
`GEOMETRY` values produced from iox WKB projection.

```sql
SELECT property_name, geometry_kind, dimension,
       max_overlap_lexical, line_forms
FROM ili_geometry_properties('MyModel.Data.Feature', ['/models/base.ili']);
```

The native converter supports the geometry forms represented by the iox
descriptor, including straight segments, arcs, surfaces, areas, polylines, and
multi-geometries where the source model and input provide them. The optional
`arc_tolerance_override` on `xtf_scan` must be positive; otherwise the descriptor
`MAX OVERLAPS` value or the native default is used.

The MVP deliberately does not project line attributes, clipped geometries, or
custom `LINE FORM` geometry. Geometry updates are not supported by `xtf_set`.
With `geometry_errors := 'null'`, a conversion failure produces a NULL geometry
and a diagnostic entry in `_unsupported_json`; with the default `error`, the
query fails.

The output is WKB-backed and can be used with DuckDB's spatial functions when
the `spatial` extension is loaded. The native build is GEOS-free by default.
In that mode, iox still performs structural conversion checks, but native
topological validation is not performed. A GEOS-enabled strict build adds that
validation without changing the output WKB.

The distinction is visible with
`testdata/synthetic/geometries/invalid-topology.xtf`: the GEOS-free build emits
the structurally valid WKB and DuckDB Spatial reports `ST_IsValid = false`,
whereas the strict build rejects the same transfer during `xtf_scan`.
