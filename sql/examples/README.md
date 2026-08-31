# SQL-Beispiele

Die domänenspezifischen SQL-Dateien `02` bis `09` bleiben als historische
INTERLIS-Fixtures erhalten. Sie verwenden die frühere API und gehören nicht
zum aktuellen Testvertrag.

Das gepflegte Beispiel ist `10-native.sql`. Es zeigt Version und Komponenten,
alle Modellintrospektionsfunktionen sowie `xtf_scan`, `xtf_values` und
`xtf_set`:

```sh
scripts/dev-duckdb.sh < sql/examples/10-native.sql
```

Die älteren Dateien und synthetischen Daten werden nicht von CI geladen und
begründen keine Validator-, Remote-Modell- oder Importfunktion.
