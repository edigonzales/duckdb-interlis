# Fehlerbehebung

## Extension lädt nicht

DuckDB-Version und Extension müssen zusammenpassen. Projektartefakte benötigen
`-unsigned` und einen absoluten Pfad:

```sql
LOAD '/absolute/path/interlis.duckdb_extension';
SELECT interlis_version();
```

Bei Community-Installation prüfen, ob der aktive Deskriptor für die verwendete
DuckDB-Version gebaut wurde.

## Modellkompilation schlägt fehl

Jeder Eintrag in `model_sources` muss eine lokale `.ili`-Datei oder ein
nichtrekursives Verzeichnis mit `.ili`-Dateien sein. Leere Verzeichnisse und
URLs werden abgewiesen. Bei nicht zusammengehörigen Modellen eine explizite
Dateiliste verwenden.

## XTF-Scan schlägt fehl

Den vollständig qualifizierten Klassennamen aus `ili_classes` verwenden und
prüfen, ob XTF und Modelle denselben Stand beschreiben. Für Geometriediagnosen
vorübergehend `geometry_errors := 'null'` setzen und `_unsupported_json`
auswerten.

## XTF-Rewrite schlägt fehl

`xtf_set` verlangt einen anderen Zielpfad, eine passende TID und genau ein
primitives Ziel. Rollen, Geometrien, Collections, transiente Werte, Wildcards
und mehrdeutige Treffer werden abgewiesen. `expected` dient der
Konflikterkennung; `overwrite := true` nur bewusst für ein vorhandenes Ziel
verwenden.
