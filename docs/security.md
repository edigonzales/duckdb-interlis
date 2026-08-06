# Security

The native MVP only reads model and XTF paths explicitly supplied by the SQL
caller. It does not resolve remote model repositories or make network requests.
Model inputs must be regular local files or non-recursive directories.

`xtf_set` never edits its input in place. It writes a uniquely named temporary
file in the destination directory, closes and checks the XTF writer, then moves
the result into the requested output path. A failed rewrite removes the
temporary file and leaves the input untouched. Existing output is rejected
unless `overwrite := true` is specified.

The extension is unsigned in the current distribution workflow, so loading it
requires DuckDB's `-unsigned` option. Release artifacts should be distributed
with their generated SHA-256 sidecar and verified by the deployment system.

No Java process, GraalVM isolate, embedded shared library, or runtime code
download is involved. Native dependencies are linked at build time and their
component revisions are visible through `interlis_components()`.
