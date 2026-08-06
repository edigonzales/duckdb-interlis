# Native extension build notes

The root build follows the pinned DuckDB C++ extension template. The exact
DuckDB 1.5.3 and extension-ci-tools revisions are recorded by the submodules;
ilic-fork and iox-cpp are selected through the CMake source-directory overrides
or their pinned FetchContent fallback revisions.

The extension registers one explicit function seam per native source file.
`CompiledModel` owns ilic compilation and the iox model index. The XTF functions
share a fixed-buffer iox stream feeder, while `xtf_set` adds a transactional
file-backed writer. See [native-architecture.md](native-architecture.md) and
[development.md](development.md) for the maintained build contract.
