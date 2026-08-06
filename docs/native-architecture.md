# Native architecture

The extension is a DuckDB C++ extension built from the pinned DuckDB 1.5.3
extension template. Its runtime path is deliberately short:

```text
DuckDB table function
        │
        ├── ModelSourceResolver → ilic::ModelCompilation
        │                         └── iox::ilic::IlicModelIndex
        └── iox::xtf::IlicXtfReader / iox::xtf::XtfWriter
                              │
                              └── GEOS-backed WKB projection
```

`CompiledModel` owns the ilic compilation and the iox model index. A function
bind compiles local model sources once and keeps that object in its bind data;
execution does not recompile the model. There is no process-global model cache.

`xtf_scan` and `xtf_values` feed a local file to one streaming reader through a
fixed 64 KiB buffer. The MVP pins those functions to one execution thread so
that row order and reader lifetime are deterministic. `xtf_set` reads and writes
sequentially through a temporary file in the destination directory and moves
the completed file into place only after a successful writer close.

Geometry metadata comes from iox descriptors. Geometry conversion uses the iox
projection and the optional GEOS dependency; DuckDB receives WKB-backed
`GEOMETRY` values. Ownership is RAII throughout the extension boundary.

The repository has no Java module, GraalVM native-image build, embedded library,
TSV transport, or C ABI bridge. The only external source checkouts are the
pinned DuckDB/extension-template submodules and the ilic/iox-cpp dependencies.
