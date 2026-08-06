# Model sources

The native MVP resolves model sources deterministically and locally.

```sql
SELECT *
FROM ili_models([
  '/models/base.ili',
  '/models/domain'
]);
```

Accepted sources are:

- a regular `.ili` file, matched case-insensitively by extension;
- a regular directory, scanned one level deep for `.ili` files and sorted
  lexicographically;
- a list containing both forms, with duplicate normalized paths removed.

Paths become normalized absolute file URIs before compilation. Empty directories,
missing paths, non-regular files, invalid model text, and empty source lists are
errors. Directories are not searched recursively.

HTTP(S) URLs and repository names are rejected with
`Remote model sources are not supported by the native MVP`. The extension does
not access the network to resolve models and has no default model repository.

Prefer explicit file lists when a directory contains unrelated models: every
resolved source is a compilation root, so one invalid file can reject the whole
model compilation. A compiled model is owned by the statement bind and is
released after the query.
