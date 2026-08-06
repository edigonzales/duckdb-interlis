# Native DuckDB extension bootstrap

Phase 7 introduces the native C++ extension boundary while the existing GraalVM
implementation remains available for regression comparison. The new root build follows
the DuckDB extension-template layout:

- extension-template commit: `cfaf3e236008e782d27f4341b0ee036002d0a449`;
- DuckDB `v1.5.3`: `14eca11bd9d4a0de2ea0f078be588a9c1c5b279c`;
- extension-ci-tools `v1.5.3`: `4b3b37b0c9de00da54e1765d65abfea3f94617f4`;
- `vcpkg` baseline: `ce613c41372b23b1f51333815feb3edd87ef8a8b`.

The network fallback SHAs for the sibling repositories are `ilic-fork`
`bd4625485a990629366a770d630db5d9ffa15cc5` and `iox-cpp`
`ff098e26b0da7697208ad02d3fbb48d60810d300`. The local sibling options are preferred
for this MVP because they carry the exact working-tree snapshot used for the native
integration.

The DuckDB and extension-ci-tools repositories are pinned submodules under
`third_party/`. The two project siblings are configurable through
`INTERLIS_ILIC_SOURCE_DIR` and `INTERLIS_IOX_SOURCE_DIR`. Set local paths to avoid
network access; otherwise CMake uses the exact commit SHA fallback recorded in the
cache variables.

The current extension registers only `interlis_version()`. The model, XTF scan, value,
and update registration functions are explicit no-op seams for Phases 8–12, so no
global static registrators or parallel legacy framework are introduced.

DuckDB 1.5.3 exposes the C++ extension hook as `Extension::Load(ExtensionLoader &)`,
so the implementation uses that concrete API while keeping the requested
`InterlisExtension` boundary and explicit registration functions.

## Development build

From a checkout with submodules initialized and a working vcpkg toolchain:

```sh
GEN=ninja make debug
make test_debug
GEN=ninja make release
make test_release
```

For local sibling checkouts, add the two CMake options through `EXT_DEBUG_FLAGS` or
the CMake command line. `IOX_ENABLE_GEOS` remains enabled for the native extension;
the `geos` vcpkg manifest is the only dependency source and no ad-hoc download is
performed by this repository.
