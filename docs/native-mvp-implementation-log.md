# Native MVP implementation log

The migration was implemented in small, reviewable phases:

1. baseline and pinned version contracts;
2. ilic model compilation ownership and geometry metadata;
3. iox property descriptors, IOM paths, and geometry projection;
4. the native DuckDB extension boundary and local model resolution;
5. model introspection functions;
6. streaming `xtf_scan`;
7. primitive `xtf_values` paths;
8. transactional one-value `xtf_set`;
9. removal of the former runtime/build architecture and documentation update.

The native test fixtures under `testdata/native/` cover model compilation,
introspection, primitive types, geometry error policy, path reads, and rewrite
failure safety. The release contract is documented in
[release.md](release.md), and the final scope is recorded in
[limitations.md](limitations.md).
