# Native SQL-Funktionen

Alle modellabhängigen Funktionen erhalten ein `VARCHAR[]` mit lokalen
Modellquellen. Zulässig sind reguläre `.ili`-Dateien und nichtrekursive
Verzeichnisse; siehe [Modellquellen](model-sources.md).

## Version und Komponenten

```sql
SELECT interlis_version();
SELECT * FROM interlis_components();
```

`interlis_components()` liefert `component`, `version` und `revision` für
Extension, ilic, iox-cpp, GEOS und DuckDB. Eigene Komponenten melden den
vollständigen Git-SHA; lokale Änderungen ergänzen `-dirty`. Der GEOS-Eintrag
meldet im Standardbuild `disabled`, andernfalls die Paketversion.

## Modellintrospektion

```sql
SELECT * FROM ili_models(['/models/base.ili']);
SELECT * FROM ili_classes(['/models/base.ili'], model := 'MyModel');
SELECT * FROM ili_properties('MyModel.Data.Feature', ['/models/base.ili']);
SELECT * FROM ili_geometry_properties('MyModel.Data.Feature', ['/models/base.ili']);
```

Klassen folgen Modell-, Topic- und Deklarationsreihenfolge, Properties der
Transferreihenfolge. Geometriezeilen enthalten Typ, Koordinatendomäne,
Dimension, `MAX OVERLAPS`, Linienformen und native Fähigkeitsflags.

## `xtf_scan`

```sql
SELECT _tid, Name, Geometry
FROM xtf_scan('/data/input.xtf',
              'MyModel.Data.Feature',
              ['/models/base.ili'],
              geometry_errors := 'null');
```

```text
xtf_scan(path, class_name, model_sources,
         geometry_errors := 'error', arc_tolerance_override := NULL)
```

Das Resultat beginnt mit `_bid`, `_tid`, `_class`, `_operation` und
`_unsupported_json`; danach folgen unterstützte primitive und geometrische
Properties in Transferreihenfolge. Fehlende Werte sind SQL-`NULL`.
Nichtrelationale Rollen, Strukturen, Collections und Diagnosen bleiben in
`_unsupported_json` sichtbar.

`geometry_errors := 'null'` behält die Zeile mit NULL-Geometrie und Diagnose;
`error` bricht ab. Strukturelle Konvertierung wird immer geprüft. Topologische
Validierung erfolgt nur im GEOS-Build oder nachträglich mit DuckDB Spatial.

## `xtf_values`

```sql
SELECT *
FROM xtf_values('/data/input.xtf',
                'MyModel.Data.Feature',
                'DetailsValue.Label',
                ['/models/base.ili'],
                tid := 'F1');
```

Das Resultat lautet `(bid, tid, class_name, occurrence, value)`. Pfade
unterstützen den ersten Wert, einen einsbasierten Index wie `Tags[2].Value`
und Wildcards wie `Tags[*].Value`. Primitive lexikalische Werte bleiben in
Transferreihenfolge.

## `xtf_set`

```sql
SELECT *
FROM xtf_set('/data/input.xtf', '/data/output.xtf',
             'MyModel.Data.Feature',
             'F1', 'Name', 'updated',
             ['/models/base.ili'],
             expected := 'old');
```

Die Funktion ändert genau einen primitiven Wert und gibt BID, TID, Klasse,
Pfad, alten/neuen Wert und Status zurück. Eingabe und Ausgabe müssen
verschieden sein. Eine temporäre Datei wird erst nach erfolgreichem Writer-
Close verschoben; `overwrite := true` erlaubt das bewusste Ersetzen einer
existierenden Ausgabe. Es entsteht kein In-place- oder UPDATE-Transfer.
