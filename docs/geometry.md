# Geometrie

ilic liefert die Metadaten für `ili_geometry_properties`; `xtf_scan` projiziert
Geometrien über iox-WKB in native DuckDB-`GEOMETRY`-Werte.

```sql
SELECT property_name, geometry_kind, dimension,
       max_overlap_lexical, line_forms
FROM ili_geometry_properties('MyModel.Data.Feature', ['/models/base.ili']);
```

Unterstützt werden die vom iox-Deskriptor dargestellten geraden Segmente,
Bögen, Flächen, Areas, Polylines und Multi-Geometrien. Ein
`arc_tolerance_override` muss positiv sein; sonst gilt `MAX OVERLAPS` aus dem
Modell oder der native Default.

Linienattribute, geclippte Geometrien und eigene `LINE FORM` werden nicht
projiziert; `xtf_set` ändert keine Geometrien. Mit
`geometry_errors := 'null'` ergibt eine Konvertierungsstörung NULL plus Eintrag
in `_unsupported_json`, mit `error` bricht die Query ab.

Der GEOS-freie Standardbuild erzeugt WKB und prüft die Struktur, nicht die
Topologie. DuckDB Spatial kann anschließend `ST_IsValid` ausführen. Ein
GEOS-Build ergänzt native topologische Prüfung, ohne das Ausgabe-WKB zu ändern.
Die Fixture `testdata/synthetic/geometries/invalid-topology.xtf` deckt den
Unterschied ab.
