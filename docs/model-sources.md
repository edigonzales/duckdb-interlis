# Modellquellen

Modellquellen werden deterministisch und ausschliesslich lokal aufgelöst:

```sql
SELECT * FROM ili_models(['/models/base.ili', '/models/domain']);
```

Zulässig sind:

- eine reguläre `.ili`-Datei, Gross-/Kleinschreibung der Endung ignoriert;
- ein reguläres Verzeichnis, eine Ebene tief lexikografisch nach `.ili`
  durchsucht;
- eine Liste beider Formen; normalisierte Duplikate werden entfernt.

Vor der Kompilation werden Pfade zu normalisierten absoluten Datei-URIs.
Fehlende Pfade, leere Verzeichnisse, andere Dateitypen, ungültige Modelle und
leere Listen sind Fehler. Unterverzeichnisse werden nicht durchsucht.

HTTP(S)-URLs und Repository-Namen sind nicht zulässig. Die Extension besitzt
kein Default-Modell-Repository und greift zur Modellauflösung nicht auf das
Netz zu.

Bei gemischten Verzeichnisinhalten ist eine explizite Dateiliste vorzuziehen:
Jede gefundene Quelle ist Kompilationswurzel, sodass eine ungültige Datei die
gesamte Kompilation abweist. Das kompilierte Modell gehört zum Statement-Bind
und wird nach der Query freigegeben.
