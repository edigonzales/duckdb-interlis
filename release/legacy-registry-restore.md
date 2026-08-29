# Restore the historical iox vcpkg registry

DuckDB release `v0.2.0` used the former iox-owned registry commit
`d3a33862ee4c744a0dbc085ce68b746c1ac57bdc`. It was verified locally to contain
iox `0.2.0-snapshot.600d191e` at source
`600d191e387405b3e957617f7a1e6dd7a29a1d94`.

An authorized maintainer can recreate the deleted branch without rewriting any
commit from the canonical `iox-cpp` checkout:

```sh
git fetch --no-tags origin d3a33862ee4c744a0dbc085ce68b746c1ac57bdc
test "$(git rev-parse FETCH_HEAD)" = d3a33862ee4c744a0dbc085ce68b746c1ac57bdc
git push origin d3a33862ee4c744a0dbc085ce68b746c1ac57bdc:refs/heads/vcpkg-registry
```

Protect the restored branch as read-only immediately afterward. New registry
versions belong only to `ilic-fork/vcpkg-registry`; this legacy branch exists
solely so historical consumers can resolve `v0.2.0`.
