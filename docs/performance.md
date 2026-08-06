# Performance characteristics

`xtf_scan` and `xtf_values` use a fixed 64 KiB input buffer and stream local XTF
files through one iox reader. They are intentionally constrained to one table-
function execution thread in the MVP. Model compilation happens once during
bind and is retained for the statement execution.

`xtf_set` is a single-object rewrite and therefore performs a complete
sequential pass over the input. It writes to a temporary file before the final
move; this favors failure safety over in-place speed.

The extension does not maintain a global model cache. Applications that execute
many independent statements should keep model source lists explicit and measure
statement preparation versus XTF scan time separately.
