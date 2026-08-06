# SQL examples

The domain-oriented SQL files in this directory are retained as historical
INTERLIS examples and fixtures. Files `02`–`09` target the pre-native API and
are not part of the native MVP test contract. Use the native functions documented
in [docs/functions.md](../../docs/functions.md) for current queries.

The maintained native example is:

| File | What it shows |
|---|---|
| `10-native-mvp.sql` | Components, model introspection, `xtf_scan`, `xtf_values`, and `xtf_set` |

Run it against a locally built extension:

```sh
scripts/dev-duckdb.sh < sql/examples/10-native-mvp.sql
```

The older files and synthetic test data remain available for domain comparison;
they are not loaded by CI and do not imply validator, remote-model, or import
support in the native MVP.
