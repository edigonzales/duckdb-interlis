# Release and publishing

The extension targets DuckDB 1.5.5. CI builds one unsigned binary per supported
platform below the DuckDB product-version directory and produces unambiguous
GitHub assets:

```text
v1.5.5/{linux_amd64,osx_arm64,windows_amd64}/interlis.duckdb_extension
interlis-{linux-x86_64,osx-aarch64,windows-x86_64}.duckdb_extension
```

Each binary has a SHA-256 sidecar and a platform-specific
`interlis-<classifier>.release.json` containing the full extension, DuckDB,
ilic, and iox identities plus vcpkg baselines.

## Normal release

Only a new `vX.Y.Z` tag can deploy or create a GitHub Release. CI checks that:

- `vX.Y.Z` exactly matches `VERSION`;
- the checkout, tag target, and workflow source SHA are identical;
- no GitHub Release already exists for the tag;
- native binary-cache restores, builds, and SQLLogicTests pass on all platforms.

The workflow never creates or moves a release tag, deletes assets, or replaces
an existing release. Ordinary `main` pushes and manual CI dispatches only test;
they publish nothing.

## Repair and comparison

`repair-release.yml` accepts only an existing `vX.Y.Z` tag. It checks out that
tag's commit and builds from the dependency contract committed at that tag.
Results are retained as short-lived workflow artifacts labelled
`comparison-only`; the workflow has read-only repository permission and cannot
change a tag, GitHub Release, deployed repository, or existing asset.

This separation prevents a repair run from silently compiling current `main`
under an old release number. Historical `v0.2.0` dependencies are frozen as a
regression fixture in `release/history/v0.2.0.json`.

## Local verification

```sh
source scripts/env.sh
python3 scripts/release_metadata.py check
scripts/build-all.sh
```

Before creating a release tag, update `VERSION` and `CHANGELOG.md`, commit the
dependency lock, inspect `interlis_components()`, and verify the extension plus
checksum on every platform. Local runs never publish. Project-hosted builds are
unsigned and require DuckDB's `-unsigned`; DuckDB Community Extensions signs
and publishes through its separate infrastructure.
