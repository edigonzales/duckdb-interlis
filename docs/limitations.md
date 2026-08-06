# Native MVP limitations

The MVP is intentionally narrow. The following behavior is not implemented:

- No full INTERLIS data validation or validator SQL function.
- No remote model repositories, URLs, or repository-name resolution.
- No ATTACH integration.
- No line attributes.
- No clipped geometries.
- No custom LINE FORM geometry projection.
- No in-place update.
- No geometry updates.
- No multi-object patching.
- No UPDATE transfer generation.
- No Java runtime or GraalVM dependency.

Additional constraints are part of the function contracts:

- `xtf_scan` and `xtf_values` use one stream execution thread and local regular
  files;
- `xtf_scan` puts unsupported roles, structures, collections, and diagnostics
  into `_unsupported_json` rather than inventing a relational shape;
- `xtf_set` rewrites one primitive property of one object, optionally nested
  through a single-valued structure, and performs structural XTF/IOM checks but
  not complete semantic validation;
- `xtf_set` requires a separate output path and never edits its input in place;
- model directories are non-recursive and all resolved `.ili` files participate
  in compilation;
- geometry conversion errors either abort the scan or produce NULL plus a
  diagnostic, according to `geometry_errors`.

These restrictions are deliberate compatibility boundaries for the native MVP,
not silently enabled fallback behavior.
