# Performance

`xtf_scan` und `xtf_values` streamen lokale XTF-Dateien mit einem festen
64-KiB-Puffer durch einen iox-Reader. Ein Table-Function-Thread erhält
Reihenfolge und Reader-Lebensdauer. Das Modell wird einmal beim Bind kompiliert
und während der Statement-Ausführung behalten.

`xtf_set` schreibt ein Objekt um und durchläuft deshalb die gesamte Eingabe
sequenziell. Die temporäre Datei vor dem finalen Move priorisiert
Fehlersicherheit gegenüber In-place-Geschwindigkeit.

Es gibt keinen globalen Modellcache. Anwendungen mit vielen unabhängigen
Statements sollten Vorbereitung und XTF-Scan getrennt messen und stabile,
explizite Modellquelllisten verwenden.
