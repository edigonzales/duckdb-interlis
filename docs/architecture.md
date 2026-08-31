# Architektur

Die Extension wird mit dem für DuckDB 1.5.5 gepinnten Extension-Template als
C++17-Modul gebaut.

```text
DuckDB-Table-Function
        │
        ├── ModelSourceResolver → ilic::ModelCompilation
        │                         └── iox::ilic::IlicModelIndex
        └── iox::xtf::IlicXtfReader / iox::xtf::XtfWriter
                              │
                              └── optionale GEOS-WKB-Prüfung
```

`CompiledModel` besitzt ilic-Kompilation und iox-Modellindex. Der Bind-Schritt
kompiliert lokale Modellquellen einmal; die Ausführung kompiliert nicht erneut.
Es gibt keinen prozessweiten Modellcache.

`xtf_scan` und `xtf_values` lesen mit einem festen 64-KiB-Puffer und genau
einem Reader. Ein Ausführungsthread hält Reihenfolge und Reader-Lebensdauer
deterministisch. `xtf_set` schreibt sequenziell in eine temporäre Datei im
Zielverzeichnis und verschiebt sie erst nach erfolgreichem Writer-Close an den
Zielpfad.

Geometriemetadaten stammen aus iox-Deskriptoren. iox erzeugt WKB auch ohne
GEOS; GEOS ist eine optionale zusätzliche Validierung. DuckDB Spatial ist nur
ein möglicher SQL-Konsument und keine C++-Abhängigkeit der Extension.

Die Runtime enthält weder Java noch GraalVM, eingebettete Shared Library,
Texttransport oder C-ABI-Brücke. Externe Quellen sind über Submodule und
`release/dependencies.lock.json` festgelegt.
