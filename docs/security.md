# Sicherheit

Die Extension liest nur Modell- und XTF-Pfade, die der SQL-Aufrufer explizit
übergibt. Sie löst keine entfernten Modell-Repositories auf und führt keine
Netzwerkzugriffe aus. Modellquellen müssen reguläre lokale Dateien oder
nichtrekursive Verzeichnisse sein.

`xtf_set` verändert die Eingabe nie direkt. Es schreibt eine eindeutig
benannte temporäre Datei im Zielverzeichnis, schliesst und prüft den Writer und
verschiebt sie erst danach. Bei Fehlern wird die temporäre Datei entfernt; ein
vorhandenes Ziel wird ohne `overwrite := true` abgewiesen.

Projektartefakte sind unsigniert und benötigen DuckDB `-unsigned`; ihre
SHA-256-Datei muss geprüft werden. DuckDB Community baut und signiert seinen
eigenen Kanal separat.

Es gibt keinen Java-Prozess, GraalVM-Isolate, Runtime-Code-Download oder eine
eingebettete dynamische Fremdlaufzeit. Native Abhängigkeiten werden beim Build
gebunden und sind über `interlis_components()` nachvollziehbar.
