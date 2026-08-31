# Einschränkungen

Nicht implementiert sind:

- vollständige INTERLIS-Datenvalidierung oder Validator-SQL-Funktion;
- entfernte Modell-Repositories, URLs und Repository-Namen;
- `ATTACH`-Integration;
- Linienattribute, geclippte Geometrien und eigene `LINE FORM`-Projektion;
- In-place-, Geometrie- und Multiobjekt-Updates;
- Erzeugung eines UPDATE-Transfers;
- Java- oder GraalVM-Abhängigkeiten.

Weitere verbindliche Grenzen:

- `xtf_scan` und `xtf_values` arbeiten mit einem Stream-Thread und lokalen
  regulären Dateien.
- Nicht unterstützte Rollen, Strukturen, Collections und Diagnosen landen in
  `_unsupported_json`; es wird keine relationale Form erfunden.
- `xtf_set` schreibt eine primitive Property eines Objekts um, optional durch
  eine einwertige Struktur. Es prüft XTF/IOM strukturell, nicht vollständig
  semantisch.
- Modellverzeichnisse sind nichtrekursiv; alle gefundenen `.ili`-Dateien
  nehmen an der Kompilation teil.
- Geometriefehler brechen je nach `geometry_errors` ab oder erzeugen NULL plus
  Diagnose.
- Der GEOS-freie Standardbuild prüft Struktur, aber keine native topologische
  Gültigkeit. Dafür DuckDB Spatial `ST_IsValid` verwenden oder mit
  `INTERLIS_ENABLE_GEOS=ON` bauen.

Diese Grenzen sind sichtbarer Produktvertrag und kein stiller Fallback.
