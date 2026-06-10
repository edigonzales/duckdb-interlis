# Implementierungsspezifikation: Robuste Geometrieunterstützung für `duckdb-interlis`

## Ziel: saubere INTERLIS-Geometriekonvertierung, DuckDB-Spatial-Kompatibilität, CRS-Metadaten und belastbare Tests

**Zielgruppe:** LLM-Coding-Agent  
**Repository:** `https://github.com/edigonzales/duckdb-interlis`  
**Basis:** aktueller `main`-Stand  
**Primäre Entwicklungsplattform:** macOS ARM64  
**Weitere Zielplattformen:** Linux x86_64, Linux ARM64, Windows x86_64  
**DuckDB-Zielversion:** gemäss Repository-Konfiguration  
**INTERLIS-Abhängigkeiten:** bestehende Versionen von ili2c, iox-ili und ilivalidator  
**Spatial-Integration:** DuckDB `spatial` Extension, aber keine harte Laufzeitabhängigkeit für `duckdb-interlis`

---

# 1. Zweck dieser Spezifikation

Diese Spezifikation beschreibt die vollständige Überarbeitung und Härtung des Geometriepfads von `duckdb-interlis`.

Der Coding-Agent soll die Geometrieunterstützung so verbessern, dass:

1. INTERLIS-Geometrieattribute zuverlässig erkannt werden.
2. Geometrien korrekt in ein OGC-kompatibles Austauschformat konvertiert werden.
3. das Zusammenspiel mit der DuckDB-Spatial-Extension eindeutig, dokumentiert und getestet ist.
4. Geometrie-Metadaten wie Typ, Dimension, Koordinatendomäne und optional CRS separat verfügbar sind.
5. bestehende SQL-Abfragen nicht unnötig gebrochen werden.
6. bisher still ignorierte oder nur teilweise unterstützte Geometrietypen explizit behandelt werden.
7. AREA, MULTIAREA, Innenringe, 3D, Bögen und Fehlerfälle testbar werden.
8. der Geometriecode aus `XtfObjectReader` herausgelöst und fachlich sauber gekapselt wird.
9. die spätere Cursor-/Streaming-Implementierung denselben Geometriepfad verwenden kann.
10. Dokumentation und Tests dem tatsächlichen Verhalten entsprechen.

Diese Spezifikation ist auf Klassen- und Methodenebene verbindlich.

---

# 2. Aktueller Zustand

Der aktuelle Pfad ist sinngemäss:

```text
INTERLIS IomObject
→ Geometrie-Unterobjekt
→ Iox2jts / Iox2jtsext
→ JTS Geometry
→ OGC WKB
→ HEX-String
→ TSV
→ DuckDB VARCHAR
→ optional ST_GeomFromHEXWKB()
```

Aktuell werden in `XtfObjectReader` Geometrieattribute erkannt und als Spalten mit Suffix `_wkb` ausgegeben.

Diese Spalten enthalten jedoch kein binäres WKB, sondern einen hexadezimalen WKB-String.

Beispiel:

```text
Lage_wkb VARCHAR
```

In DuckDB Spatial muss derzeit verwendet werden:

```sql
ST_GeomFromHEXWKB(Lage_wkb)
```

Nicht korrekt ist:

```sql
ST_GeomFromWKB(Lage_wkb)
```

weil `ST_GeomFromWKB()` einen binären BLOB beziehungsweise WKB-BLOB erwartet.

---

# 3. Wichtigste aktuelle Probleme

## 3.1 Missverständliche Datentypen

Die Spalten heissen `_wkb`, enthalten aber HEX-WKB als `VARCHAR`.

Das ist technisch nutzbar, aber semantisch unklar.

## 3.2 Keine belastbare Spatial-Integrationstests

Die vorhandenen Geometrie-Testdaten decken mehrere Grundtypen ab, aber es fehlt ein echter Test mit:

```sql
INSTALL spatial;
LOAD spatial;
ST_GeomFromHEXWKB(...)
ST_GeometryType(...)
ST_AsText(...)
ST_IsValid(...)
```

## 3.3 CRS fehlt

Weder die Geometriespalte noch eine Begleitspalte enthält:

- EPSG-Code,
- Koordinatensystemname,
- INTERLIS-Koordinatendomäne,
- SRID.

Die Extension darf das CRS nicht anhand von Wertebereichen erraten.

## 3.4 3D ist nicht spezifiziert

INTERLIS-COORD kann C3 enthalten. Der aktuelle WKB-Writerpfad garantiert jedoch nicht dokumentiert, dass Z erhalten bleibt.

## 3.5 AREA und MULTIAREA sind nicht explizit abgesichert

Die Modelltyp-Erkennung kennt AREA-artige Typen, der Tag-Dispatch behandelt aber nur bestimmte konkrete Geometrie-Tags.

## 3.6 Bögen und Linienformen sind nicht sauber definiert

Es existiert keine dokumentierte Policy für:

- ARC,
- custom line forms,
- clipped polylines,
- line attributes,
- Linearisierungstoleranz.

## 3.7 Der generische Reader liefert keine echte Geometrie

`geom_json` enthält aktuell primär ein Vorhandenseinsflag.

Für Analysen ist dies unzureichend.

## 3.8 Geometriecode ist in `XtfObjectReader` eingebettet

`XtfObjectReader` übernimmt gleichzeitig:

- Modellkompilierung,
- IOX-Reader-Lifecycle,
- Tabellenbildung,
- JSON,
- TSV,
- Geometrieerkennung,
- Geometriekonvertierung,
- Strukturen,
- Assoziationen.

Dies erschwert Tests und Wiederverwendung.

---

# 4. Verbindliche Architekturentscheidung

## 4.1 Keine harte Abhängigkeit von DuckDB Spatial

`duckdb-interlis` darf weiterhin ohne installierte oder geladene Spatial-Extension funktionieren.

Die neutrale Austauschgrenze bleibt:

```text
OGC WKB
```

Die Extension darf nicht verlangen, dass beim Laden von `duckdb-interlis` auch `spatial` geladen wird.

## 4.2 Kurzfristige Kompatibilitätsstrategie

Die bestehenden dynamischen Geometriespalten bleiben zunächst erhalten:

```text
<Attributname>_wkb VARCHAR
```

Inhalt:

```text
uppercase hexadecimal OGC WKB
```

Diese Entscheidung verhindert einen sofortigen Breaking Change.

Die Dokumentation muss klar sagen:

> `_wkb` enthält vorerst HEX-WKB als VARCHAR.

## 4.3 Neue explizite Geometrie-Metadaten

Zusätzlich wird eine neue Introspektionsfunktion eingeführt:

```sql
ili_geometry_attributes(...)
```

Diese liefert fachliche Geometrie-Metadaten, nicht die Geometrieobjekte selbst.

## 4.4 Vorbereitung für spätere BLOB-Ausgabe

Die interne Java-API darf nicht nur Strings kennen.

Intern soll Geometrie als Byte-Array modelliert werden:

```java
byte[] wkb
```

HEX-Encoding erfolgt erst an der Transportgrenze.

Damit kann später ohne erneute Fachlogik eine echte BLOB-Spalte eingeführt werden.

## 4.5 Keine direkte GEOMETRY-Spalte in Version 1

Die erste Umsetzung erzeugt nicht direkt DuckDB-`GEOMETRY`.

Begründung:

- `GEOMETRY` gehört zur Spatial-Extension.
- Die Spatial-Extension ist optional.
- WKB ist die stabile neutrale Grenze.
- Extension-Typen als Rückgabetyp einer anderen Extension erhöhen Kopplung.

---

# 5. Zielbild

Nach Umsetzung gilt:

```text
INTERLIS Geometry Attribute
→ InterlisGeometryTypeResolver
→ GeometryMetadata
→ InterlisGeometryEncoder
→ GeometryValue(byte[] WKB)
→ HexWkbCodec
→ TSV / Native ABI
→ DuckDB VARCHAR
→ ST_GeomFromHEXWKB()
```

Später optional:

```text
GeometryValue(byte[] WKB)
→ Native typed batch
→ DuckDB BLOB
→ ST_GeomFromWKB()
```

---

# 6. Verbindliche Package-Struktur

Neue Java-Package-Struktur:

```text
java/ili-core/src/main/java/ch/so/agi/duckdbili/core/geometry/
```

Neue Klassen:

```text
GeometryKind.java
GeometryEncoding.java
GeometryDimension.java
GeometryConversionOptions.java
GeometryMetadata.java
GeometryValue.java
GeometryConversionException.java
UnsupportedGeometryException.java
InterlisGeometryTypeResolver.java
InterlisGeometryEncoder.java
InterlisGeometryExtractor.java
GeometryCrsResolver.java
GeometryAttributeMetadataService.java
HexWkbCodec.java
GeometryJsonEncoder.java
GeometryTestSupport.java
```

Optional:

```text
ArcHandlingMode.java
GeometryValidationMode.java
```

---

# 7. Neue Enums und Value Objects

## 7.1 `GeometryKind`

### Datei

```text
java/ili-core/src/main/java/ch/so/agi/duckdbili/core/geometry/GeometryKind.java
```

### Inhalt

```java
public enum GeometryKind {
    POINT,
    MULTIPOINT,
    LINESTRING,
    MULTILINESTRING,
    POLYGON,
    MULTIPOLYGON,
    AREA,
    MULTIAREA,
    UNKNOWN
}
```

### Semantik

- `POINT` entspricht INTERLIS `COORD`.
- `MULTIPOINT` entspricht `MULTICOORD`.
- `LINESTRING` entspricht `POLYLINE`.
- `MULTILINESTRING` entspricht `MULTIPOLYLINE`.
- `POLYGON` entspricht `SURFACE`.
- `MULTIPOLYGON` entspricht `MULTISURFACE`.
- `AREA` wird separat geführt, auch wenn die resultierende OGC-Geometrie ein Polygon ist.
- `MULTIAREA` wird separat geführt.
- `UNKNOWN` darf nur in Metadaten vorkommen, nicht als erfolgreich konvertierter Geometriewert.

## 7.2 `GeometryEncoding`

```java
public enum GeometryEncoding {
    HEX_WKB,
    WKB
}
```

Die erste öffentliche SQL-Version verwendet `HEX_WKB`.

## 7.3 `GeometryDimension`

```java
public enum GeometryDimension {
    XY(2),
    XYZ(3),
    UNKNOWN(0);

    private final int coordinateDimension;
}
```

## 7.4 `ArcHandlingMode`

```java
public enum ArcHandlingMode {
    FAIL,
    LINEARIZE
}
```

Default:

```text
LINEARIZE
```

## 7.5 `GeometryConversionOptions`

### Datei

```text
GeometryConversionOptions.java
```

### Vorschlag

```java
public record GeometryConversionOptions(
        ArcHandlingMode arcHandlingMode,
        double strokeTolerance,
        boolean preserveZ,
        boolean rejectMultipleAttributeValues,
        boolean validateWkbRoundtrip) {

    public static GeometryConversionOptions defaults() {
        return new GeometryConversionOptions(
            ArcHandlingMode.LINEARIZE,
            0.0,
            true,
            true,
            false
        );
    }
}
```

### Regeln

- `strokeTolerance < 0` ist ungültig.
- `0.0` bedeutet: bestehendes Verhalten der IOX-Library verwenden.
- `rejectMultipleAttributeValues=true` verhindert stilles Ignorieren weiterer Werte.
- `preserveZ=true` ist Zielverhalten; falls technisch nicht möglich, muss ein Test dies zeigen und die Limitierung dokumentiert werden.
- `validateWkbRoundtrip=true` ist nur für Tests oder Debugging gedacht.

---

# 8. `GeometryMetadata`

## 8.1 Datei

```text
GeometryMetadata.java
```

## 8.2 Vorschlag

```java
public record GeometryMetadata(
        String modelName,
        String topicName,
        String className,
        String attributeName,
        String attributeFqn,
        GeometryKind geometryKind,
        GeometryDimension dimension,
        String coordinateDomainName,
        String coordinateDomainFqn,
        String crsAuthName,
        String crsCode,
        Integer srid,
        boolean mandatory,
        long cardinalityMin,
        long cardinalityMax,
        boolean supportsArcs,
        boolean isAreaType,
        boolean isMultiType) {
}
```

## 8.3 Regeln

- `attributeFqn` ist immer vollständig qualifiziert.
- `coordinateDomainFqn` ist null, wenn keine benannte Domain bestimmbar ist.
- `crsAuthName`, `crsCode`, `srid` sind null, wenn keine explizite Zuordnung vorhanden ist.
- Das CRS darf nicht geraten werden.
- `geometryKind` muss aus dem INTERLIS-Modell bestimmt werden.
- `dimension` basiert auf der Koordinatendomäne, nicht auf dem ersten Datenobjekt.

---

# 9. `GeometryValue`

## 9.1 Datei

```text
GeometryValue.java
```

## 9.2 Vorschlag

```java
public final class GeometryValue {

    private final GeometryMetadata metadata;
    private final byte[] wkb;
    private final GeometryDimension actualDimension;
    private final boolean empty;

    public GeometryValue(
            GeometryMetadata metadata,
            byte[] wkb,
            GeometryDimension actualDimension,
            boolean empty) {

        this.metadata = Objects.requireNonNull(metadata);
        this.wkb = wkb != null ? wkb.clone() : null;
        this.actualDimension = Objects.requireNonNull(actualDimension);
        this.empty = empty;
    }

    public GeometryMetadata metadata() {
        return metadata;
    }

    public byte[] wkb() {
        return wkb != null ? wkb.clone() : null;
    }

    public String hexWkb() {
        return wkb == null ? null : HexWkbCodec.encode(wkb);
    }

    public GeometryDimension actualDimension() {
        return actualDimension;
    }

    public boolean isEmpty() {
        return empty;
    }

    public boolean isNull() {
        return wkb == null;
    }
}
```

## 9.3 Regeln

- fehlendes Attribut → `GeometryValue` darf null sein oder `wkb=null`.
- ein leerer OGC-Geometriewert ist nicht dasselbe wie SQL-NULL.
- Byte-Arrays defensiv kopieren.

---

# 10. Exceptions

## 10.1 `GeometryConversionException`

```java
public class GeometryConversionException extends RuntimeException {

    private final String attributeFqn;
    private final String objectTid;
    private final GeometryKind geometryKind;

    // Konstruktoren + Getter
}
```

Verwendung für:

- ungültige Geometriestruktur,
- IOX-Konvertierungsfehler,
- unerwartete Nullwerte,
- WKB-Erzeugungsfehler,
- nicht unterstützte Linienform.

## 10.2 `UnsupportedGeometryException`

```java
public final class UnsupportedGeometryException
        extends GeometryConversionException {
}
```

Verwendung für:

- custom line forms,
- nicht unterstützte Tagkombinationen,
- explizit nicht unterstützte AREA-Form,
- mehrere Geometriewerte, falls Reject-Option aktiv.

---

# 11. `InterlisGeometryTypeResolver`

## 11.1 Zweck

Diese Klasse kapselt die vollständige Modelltyp-Erkennung.

Die bestehende Logik aus `XtfObjectReader` wird hierhin verschoben.

## 11.2 Datei

```text
InterlisGeometryTypeResolver.java
```

## 11.3 Öffentliche API

```java
public final class InterlisGeometryTypeResolver {

    public boolean isGeometryAttribute(AttributeDef attribute);

    public GeometryKind resolveKind(AttributeDef attribute);

    public GeometryDimension resolveDeclaredDimension(AttributeDef attribute);

    public GeometryMetadata resolveMetadata(
        Model model,
        Topic topic,
        AbstractClassDef classDef,
        AttributeDef attribute
    );

    public Type resolveBaseType(AttributeDef attribute);
}
```

## 11.4 `resolveBaseType()`

Die Methode muss:

1. `getDomainResolvingAll()` verwenden.
2. auf `getDomain()` zurückfallen.
3. `TypeAlias` rekursiv auflösen.
4. zyklische Aliasstrukturen erkennen.
5. keine magische maximale Schleifenanzahl wie `5` verwenden.

Beispiel:

```java
public Type resolveBaseType(AttributeDef attribute) {
    Type type = attribute.getDomainResolvingAll();

    if (type == null) {
        type = attribute.getDomain();
    }

    Set<Object> visited =
        Collections.newSetFromMap(
            new IdentityHashMap<>()
        );

    while (type instanceof TypeAlias alias) {
        if (!visited.add(type)) {
            throw new IllegalStateException(
                "Cyclic INTERLIS type alias for "
                    + attribute.getScopedName(null)
            );
        }

        Domain domain = alias.getAliasing();

        if (domain == null) {
            break;
        }

        type = domain.getType();
    }

    return type;
}
```

## 11.5 `resolveKind()`

Muss mindestens erkennen:

```text
AbstractCoordType       -> POINT
MultiCoordType          -> MULTIPOINT
LineType                -> LINESTRING
MultiPolylineType       -> MULTILINESTRING
SurfaceType             -> POLYGON
AreaType                -> AREA
MultiSurfaceType        -> MULTIPOLYGON
MultiAreaType           -> MULTIAREA
```

Die genaue ili2c-Klassenhierarchie ist anhand der tatsächlich verwendeten Bibliotheksversion zu prüfen.

Keine Stringerkennung auf Klassenname, solange eine Metamodellklasse verfügbar ist.

## 11.6 `resolveDeclaredDimension()`

Die Koordinatendimension muss aus der Koordinatendomäne ermittelt werden.

Ziel:

```text
2 Achsen -> XY
3 Achsen -> XYZ
sonst    -> UNKNOWN
```

Nicht anhand eines Datenobjekts ableiten.

## 11.7 Tests

Neue Testklasse:

```text
InterlisGeometryTypeResolverTest.java
```

Testfälle:

- COORD,
- MULTICOORD,
- POLYLINE,
- MULTIPOLYLINE,
- SURFACE,
- AREA,
- MULTISURFACE,
- MULTIAREA,
- TypeAlias auf Koordinatendomäne,
- mehrfach verschachtelte Aliase,
- Nicht-Geometrieattribut,
- zyklischer Alias, soweit synthetisch testbar.

---

# 12. `GeometryCrsResolver`

## 12.1 Ziel

CRS wird nicht geraten.

Der Resolver darf nur explizite, konfigurierbare oder modellbasierte Zuordnungen liefern.

## 12.2 Datei

```text
GeometryCrsResolver.java
```

## 12.3 API

```java
public interface GeometryCrsResolver {

    Optional<CrsIdentifier> resolve(
        GeometryMetadataContext context
    );
}
```

Neue Value Objects:

```java
public record CrsIdentifier(
    String authority,
    String code,
    Integer srid
) {
}
```

```java
public record GeometryMetadataContext(
    String modelName,
    String topicName,
    String className,
    String attributeName,
    String coordinateDomainFqn
) {
}
```

## 12.4 Implementierungen

### `NoopGeometryCrsResolver`

```java
public final class NoopGeometryCrsResolver
        implements GeometryCrsResolver {

    @Override
    public Optional<CrsIdentifier> resolve(...) {
        return Optional.empty();
    }
}
```

### `MapGeometryCrsResolver`

```java
public final class MapGeometryCrsResolver
        implements GeometryCrsResolver {

    private final Map<String, CrsIdentifier> byDomainFqn;
}
```

Schlüssel:

```text
Model.Domain
```

Beispiel:

```text
SO_AGI_Model.LV95 -> EPSG:2056
```

## 12.5 Konfigurationsquelle

Erste Version:

```text
ILI_GEOMETRY_CRS_MAP
```

Format:

```text
DomainFqn=AUTH:CODE;DomainFqn2=AUTH:CODE
```

Beispiel:

```bash
export ILI_GEOMETRY_CRS_MAP='SO_AGI_Geometries_20260605.Koord=EPSG:2056'
```

Optional Datei:

```text
ILI_GEOMETRY_CRS_FILE=/path/to/crs.properties
```

Properties:

```properties
SO_AGI_Geometries_20260605.Koord=EPSG:2056
```

## 12.6 Regeln

- keine numerische Heuristik,
- keine automatische Annahme von EPSG:2056,
- ungültige Mappingwerte führen beim Laden zu einem klaren Fehler,
- Mapping wird einmal geparst und unveränderlich gehalten.

## 12.7 Tests

```text
GeometryCrsResolverTest.java
```

Fälle:

- bekannte Domain,
- unbekannte Domain,
- doppelte Zuordnung,
- ungültige Authority,
- ungültiger numerischer SRID,
- Leerzeichen,
- mehrere Domains.

---

# 13. `HexWkbCodec`

## 13.1 Datei

```text
HexWkbCodec.java
```

## 13.2 API

```java
public final class HexWkbCodec {

    public static String encode(byte[] wkb);

    public static byte[] decode(String hex);

    public static boolean isValidHexWkb(String hex);
}
```

## 13.3 Regeln

- Ausgabe immer uppercase oder immer lowercase; verbindlich auswählen.
- Empfehlung: uppercase, passend zu vorhandenen IOX-Helfern.
- ungerade Zeichenzahl ist ungültig.
- Nicht-Hex-Zeichen sind ungültig.
- `null` bleibt `null`.
- leerer String ist kein gültiges WKB.

## 13.4 Tests

- Point-WKB,
- leeres Bytearray,
- null,
- lowercase Input,
- ungültiges Zeichen,
- ungerade Länge,
- Roundtrip.

---

# 14. `InterlisGeometryExtractor`

## 14.1 Zweck

Trennt das Auslesen des Geometrie-Unterobjekts von der eigentlichen Konvertierung.

## 14.2 Datei

```text
InterlisGeometryExtractor.java
```

## 14.3 API

```java
public final class InterlisGeometryExtractor {

    public Optional<IomObject> extractSingle(
        IomObject parent,
        AttributeDef attribute,
        GeometryConversionOptions options
    );

    public List<IomObject> extractAll(
        IomObject parent,
        AttributeDef attribute
    );
}
```

## 14.4 Regeln

`extractSingle()`:

1. `getattrvaluecount(attributeName)` bestimmen.
2. bei 0 → `Optional.empty()`.
3. bei 1 → Objekt liefern.
4. bei >1:
   - mit `rejectMultipleAttributeValues=true` Fehler,
   - sonst erstes Element nur mit dokumentierter Warnung.
5. `getattrobj()` darf nicht still null liefern, wenn Count > 0.
6. Fehlermeldung enthält:
   - Objekt-TID,
   - Klasse,
   - Attribut-FQN,
   - Anzahl Werte.

---

# 15. `InterlisGeometryEncoder`

## 15.1 Zweck

Zentrale Konvertierung von IOX-Geometrien zu WKB.

## 15.2 Datei

```text
InterlisGeometryEncoder.java
```

## 15.3 Konstruktor

```java
public final class InterlisGeometryEncoder {

    private final InterlisGeometryTypeResolver typeResolver;
    private final InterlisGeometryExtractor extractor;
    private final GeometryConversionOptions options;

    public InterlisGeometryEncoder(
        InterlisGeometryTypeResolver typeResolver,
        InterlisGeometryExtractor extractor,
        GeometryConversionOptions options
    ) {
        ...
    }
}
```

## 15.4 Öffentliche Methoden

```java
public Optional<GeometryValue> encodeAttribute(
    IomObject parent,
    AttributeDef attribute,
    GeometryMetadata metadata
);

public GeometryValue encodeGeometry(
    IomObject geometryObject,
    GeometryMetadata metadata
);

public byte[] encodeToWkb(
    IomObject geometryObject,
    GeometryKind kind
);
```

## 15.5 Keine direkte Stringausgabe

Nicht zulässig:

```java
public String buildGeometryWkb(...)
```

Die fachliche Konvertierung muss `byte[]` liefern.

HEX-String erst an Transportgrenze:

```java
geometryValue.hexWkb()
```

## 15.6 Dispatch

Der Dispatch darf primär auf `GeometryKind` beruhen, nicht nur auf Tagtext.

Tagtext wird zusätzlich validiert.

Beispiel:

```java
private byte[] encodeToWkb(
        IomObject geometry,
        GeometryKind kind) {

    return switch (kind) {
        case POINT ->
            hexToBytes(
                Iox2jtsext.coord2hexwkb(
                    geometry
                )
            );

        case LINESTRING ->
            hexToBytes(
                Iox2jtsext.polyline2hexwkb(
                    geometry,
                    options.strokeTolerance()
                )
            );

        case POLYGON ->
            hexToBytes(
                Iox2jtsext.surface2hexwkb(
                    geometry,
                    options.strokeTolerance()
                )
            );

        case MULTIPOINT ->
            hexToBytes(
                Iox2jts.multicoord2hexwkb(
                    geometry
                )
            );

        case MULTILINESTRING ->
            hexToBytes(
                Iox2jts.multipolyline2hexwkb(
                    geometry,
                    options.strokeTolerance()
                )
            );

        case MULTIPOLYGON ->
            hexToBytes(
                Iox2jts.multisurface2hexwkb(
                    geometry,
                    options.strokeTolerance()
                )
            );

        case AREA ->
            encodeArea(geometry);

        case MULTIAREA ->
            encodeMultiArea(geometry);

        case UNKNOWN ->
            throw new UnsupportedGeometryException(...);
    };
}
```

Die exakten API-Aufrufe sind anhand der eingebundenen `iox-ili`-Version zu verifizieren.

## 15.7 Tag-Validierung

Vor der Konvertierung:

```java
validateGeometryTag(
    geometryObject.getobjecttag(),
    metadata.geometryKind()
);
```

Zulässige Tags sollen zentral konfiguriert sein:

```java
Map<GeometryKind, Set<String>>
```

Beispiele:

```text
POINT          -> COORD
LINESTRING     -> POLYLINE
POLYGON        -> SURFACE
MULTIPOINT     -> MULTICOORD
MULTILINESTRING-> MULTIPOLYLINE
MULTIPOLYGON   -> MULTISURFACE
AREA           -> AREA oder bibliotheksspezifische Struktur
MULTIAREA      -> MULTIAREA oder bibliotheksspezifische Struktur
```

Keine stillen `default -> null`.

## 15.8 AREA und MULTIAREA

Der Agent muss zunächst anhand von:

- `iox-ili`-Quellcode,
- bestehenden Tests,
- tatsächlichen `IomObject`-Tags

ermitteln, wie AREA und MULTIAREA intern repräsentiert werden.

Danach:

```java
private byte[] encodeArea(IomObject geometry);
private byte[] encodeMultiArea(IomObject geometry);
```

Falls die Library AREA bereits mit `surface2...` oder `multisurface2...` abbildet, dies explizit dokumentieren und testen.

Falls AREA ohne Netzaufbau nicht direkt konvertierbar ist:

- nicht still NULL liefern,
- `UnsupportedGeometryException` mit klarer Begründung,
- Limitierung dokumentieren.

## 15.9 ARC-Policy

Wenn `ArcHandlingMode.FAIL`:

- Geometrie vor Konvertierung auf ARC-Segmente untersuchen.
- bei ARC harter Fehler.

Wenn `LINEARIZE`:

- definierte Stroke-Tolerance verwenden.
- `0.0` nur dann zulässig, wenn die Bibliothekssemantik dokumentiert und getestet ist.

Neue private Methode:

```java
private boolean containsArc(IomObject geometry);
```

## 15.10 Custom Line Forms

Nicht unterstützte custom line forms:

```java
throw new UnsupportedGeometryException(
    "Custom line form is not supported for "
        + metadata.attributeFqn()
);
```

Nie:

```java
return null;
```

## 15.11 Clipped Polylines

Wenn IOX `IOM_INCOMPLETE` meldet:

- harter Fehler,
- vollständige Fehlermeldung,
- Testfall.

## 15.12 Line Attributes

Falls IOX line attributes nicht unterstützt:

- harter `UnsupportedGeometryException`,
- Dokumentation,
- Test.

## 15.13 3D

Der Agent muss prüfen, ob die verwendeten `Iox2jts`/`Iox2jtsext`-Methoden tatsächlich 3D-WKB erzeugen.

Wenn nicht:

1. Geometrie in JTS konvertieren.
2. einen WKBWriter mit Output-Dimension 3 verwenden.
3. Z-Ordinaten erhalten.
4. Tests mit DuckDB Spatial `ST_HasZ()`.

Falls die alte JTS-Version keinen geeigneten Writer bietet:

- klar dokumentieren,
- vorerst `preserveZ=false` erzwingen,
- bei 3D-Modell mit `preserveZ=true` einen Fehler statt stiller Z-Verlust.

Stiller Dimensionsverlust ist nicht zulässig.

---

# 16. `GeometryAttributeMetadataService`

## 16.1 Zweck

Liefert Geometrieattribute aus einem INTERLIS-Modell als strukturierte Metadaten.

## 16.2 Datei

```text
GeometryAttributeMetadataService.java
```

## 16.3 API

```java
public final class GeometryAttributeMetadataService {

    private final InterlisGeometryTypeResolver typeResolver;
    private final GeometryCrsResolver crsResolver;

    public List<GeometryMetadata> listGeometryAttributes(
        TransferDescription td,
        String modelFilter,
        String classFilter
    );

    public List<GeometryMetadata> listGeometryAttributes(
        String modelDir,
        String modelFilter,
        String classFilter
    );
}
```

## 16.4 Filter

- `modelFilter` optional.
- `classFilter` optional.
- FQN und Kurzname nur dann erlauben, wenn eindeutig.
- Mehrdeutiger Kurzname → Fehler.

## 16.5 Sortierung

Deterministisch:

```text
modelName
topicName
className
attributeName
```

---

# 17. Neue SQL-Funktion `ili_geometry_attributes`

## 17.1 Signatur

```sql
ili_geometry_attributes(
    modeldir VARCHAR,
    model => VARCHAR,
    class => VARCHAR
) → TABLE(...)
```

## 17.2 Rückgabespalten

```text
model_name             VARCHAR
topic_name             VARCHAR
class_name             VARCHAR
class_fqn              VARCHAR
attribute_name         VARCHAR
attribute_fqn          VARCHAR
geometry_kind          VARCHAR
dimension              INTEGER
coordinate_domain      VARCHAR
coordinate_domain_fqn  VARCHAR
crs_auth_name          VARCHAR
crs_code               VARCHAR
srid                    INTEGER
is_mandatory           BOOLEAN
card_min                BIGINT
card_max                BIGINT
supports_arcs           BOOLEAN
is_area_type            BOOLEAN
is_multi_type           BOOLEAN
transport_encoding      VARCHAR
duckdb_spatial_function VARCHAR
```

## 17.3 Beispiel

```sql
SELECT
    class_fqn,
    attribute_name,
    geometry_kind,
    coordinate_domain_fqn,
    crs_auth_name,
    crs_code
FROM ili_geometry_attributes(
    'testdata/synthetic/geometries',
    model := 'SO_AGI_Geometries_20260605'
);
```

## 17.4 Native API

Neue Capability und Entry Point:

```text
ILI_CAP_GEOMETRY_ATTRIBUTES
ili_native_geometry_attributes
```

Oder vorhandene `ili_native_model_info`-Funktion um Command erweitern:

```text
cmd = geometry_attributes
```

Bevorzugung:

- für geringe ABI-Komplexität bestehendes `model_info` erweitern,
- aber SQL-Funktion separat registrieren.

## 17.5 TSV-Typen

Wenn `model_info` weiterhin nur Stringspalten kann, ist eine explizite typed Bind-Funktion erforderlich.

Die C-Seite soll korrekte DuckDB-Typen registrieren:

- INTEGER,
- BIGINT,
- BOOLEAN,
- VARCHAR.

Keine künstliche Stringifizierung von Booleans und Zahlen.

---

# 18. Integration in `XtfObjectReader`

## 18.1 Neue Felder

```java
private final InterlisGeometryTypeResolver geometryTypeResolver;
private final InterlisGeometryEncoder geometryEncoder;
private final GeometryAttributeMetadataService geometryMetadataService;
```

## 18.2 Konstruktoren

Default-Konstruktor:

```java
public XtfObjectReader() {
    this(
        new InterlisGeometryTypeResolver(),
        defaultGeometryEncoder(),
        defaultMetadataService()
    );
}
```

Testbarer Konstruktor:

```java
XtfObjectReader(
    InterlisGeometryTypeResolver geometryTypeResolver,
    InterlisGeometryEncoder geometryEncoder,
    GeometryAttributeMetadataService geometryMetadataService
)
```

## 18.3 Zu entfernende Methoden

Aus `XtfObjectReader` entfernen:

```java
isGeometryDomain(...)
isMultiGeometryDomain(...)
resolveToBaseType(...)
buildGeometryWkb(...)
geomToHexWkb(...)
buildGeom(...)
```

Die Funktionalität wandert in neue Geometrieklassen.

## 18.4 Schemaerzeugung

Die bestehende Spalte bleibt:

```text
<attribute>_wkb
```

Die Metadaten dafür kommen aus `GeometryMetadata`.

Optional zusätzliche Begleitspalten nur über neuen Modus:

```text
<attribute>_crs
<attribute>_geometry_kind
```

Nicht standardmässig, um bestehende dynamische Schemas nicht zu brechen.

## 18.5 Datenzeile

```java
Optional<GeometryValue> geometry =
    geometryEncoder.encodeAttribute(
        obj,
        attributeDef,
        metadata
    );

String hex =
    geometry.map(GeometryValue::hexWkb)
        .orElse(null);

sb.append('\t')
  .append(TsvCodec.encodeNullable(hex));
```

---

# 19. Verbesserung des generischen Readers

## 19.1 Problem

`geom_json` enthält derzeit zu wenig Information.

## 19.2 Neuer JSON-Vertrag

`geom_json` soll ein JSON-Objekt pro Geometrieattribut enthalten.

Beispiel:

```json
{
  "Lage": {
    "geometry_kind": "POINT",
    "encoding": "HEX_WKB",
    "hex_wkb": "0101000000...",
    "dimension": 2,
    "coordinate_domain": "Koord",
    "coordinate_domain_fqn": "SO_AGI_Geometries_20260605.Koord",
    "crs_auth_name": "EPSG",
    "crs_code": "2056",
    "srid": 2056
  }
}
```

## 19.3 `GeometryJsonEncoder`

### API

```java
public final class GeometryJsonEncoder {

    public String encode(
        Map<String, GeometryValue> geometries
    );

    public String encodeValue(
        GeometryValue geometry
    );
}
```

## 19.4 JSON-Erzeugung

Keine manuelle Stringverkettung, wenn bereits eine JSON-Library verfügbar ist.

Falls bewusst keine JSON-Library verwendet wird:

- zentraler JSON-Writer,
- korrekte Escapes,
- Tests.

## 19.5 Kompatibilität

Dies ist eine semantische Erweiterung.

Falls Benutzer auf exakt:

```json
{"_has_geometry":true}
```

angewiesen sind, optionaler Übergangsparameter:

```sql
geometry_json := 'detailed'
```

Da `read_xtf_objects` bereits experimentell wirkt, ist ein direkter Wechsel wahrscheinlich vertretbar, muss aber im Changelog stehen.

---

# 20. DuckDB-Spatial-Kompatibilität

## 20.1 Dokumentierter Standardpfad

```sql
INSTALL spatial;
LOAD spatial;

SELECT
    ST_GeomFromHEXWKB(Lage_wkb) AS geom
FROM read_xtf_class(...);
```

## 20.2 Hilfs-View-Beispiel

```sql
CREATE VIEW punktobjekt_spatial AS
SELECT
    * EXCLUDE (Lage_wkb),
    ST_GeomFromHEXWKB(Lage_wkb) AS Lage
FROM read_xtf_class(
    '...',
    class := 'Model.Topic.PunktObjekt',
    modeldir := '...'
);
```

## 20.3 CRS-Transformation

Nur mit explizitem CRS:

```sql
SELECT
    ST_Transform(
        ST_GeomFromHEXWKB(Lage_wkb),
        'EPSG:2056',
        'EPSG:4326',
        always_xy := true
    )
FROM ...;
```

## 20.4 Keine implizite Transformation

`duckdb-interlis` transformiert Geometrien nicht automatisch.

Die Extension liefert Daten im originalen INTERLIS-Koordinatensystem.

---

# 21. Optionaler neuer Parameter `geometry_format`

## 21.1 Signatur

Optional:

```sql
read_xtf_class(
    input VARCHAR,
    class => VARCHAR,
    modeldir => VARCHAR,
    nested => VARCHAR,
    geometry_format => VARCHAR
)
```

Zulässige Werte:

```text
hexwkb
```

Später:

```text
wkb
```

## 21.2 Erste Version

Nur:

```text
hexwkb
```

Andere Werte:

```text
INVALID_ARGUMENT
```

Vorteil:

- API ist für später vorbereitet.
- Verhalten ist explizit.

Nachteil:

- zusätzlicher Parameter ohne unmittelbaren Mehrwert.

Der Agent soll diesen Parameter nur einführen, wenn keine unnötige Komplexität entsteht.

---

# 22. BLOB-Unterstützung als zweite Phase

## 22.1 Ziel

Neue optionale Tabellenfunktion oder Modus:

```text
<attribute>_wkb BLOB
```

## 22.2 Transport

Da der aktuelle Native-Transport TSV ist, gibt es zwei Möglichkeiten:

### Variante A – HEX im Native-Transport, Decode in C

Java:

```text
HEX-WKB in TSV
```

C:

```text
Hex decode
→ duckdb_vector BLOB
```

Dies ist für eine erste BLOB-Ausgabe akzeptabel.

### Variante B – Typed Native Batch

Erst mit Meilenstein B:

```text
binary batch payload
```

Diese Variante ist langfristig besser.

## 22.3 Empfehlung

Vor Meilenstein B:

- bestehende VARCHAR-Ausgabe beibehalten,
- interne Java-Repräsentation auf `byte[]` umstellen,
- BLOB erst zusammen mit typed Cursor-Batches umsetzen.

---

# 23. Geometrie-Testdaten

## 23.1 Neue Verzeichnisstruktur

```text
testdata/synthetic/geometries/
├── SO_AGI_Geometries_20260605.ili
├── valid.xtf
├── valid-3d.xtf
├── valid-holes.xtf
├── valid-arcs.xtf
├── valid-area.xtf
├── invalid-custom-line-form.xtf
├── invalid-clipped-polyline.xtf
└── README.md
```

## 23.2 Modell erweitern

Mindestens:

- 2D COORD,
- 3D COORD,
- MULTICOORD,
- POLYLINE mit Geraden,
- POLYLINE mit ARC,
- MULTIPOLYLINE,
- SURFACE,
- SURFACE mit Innenring,
- AREA,
- MULTISURFACE,
- MULTIAREA,
- optionale Geometrie,
- mehrere Geometrieattribute in einer Klasse,
- Geometrie über TypeAlias,
- Geometrie in STRUCTURE.

## 23.3 Prüfung

Jedes neue `.ili`:

```text
ili2c 5.6.8
```

Bevorzugt Maven/Gradle, lokaler Fallback:

```text
/Users/stefan/apps/ili2c-5.6.8/ili2c.jar
```

Jede gültige XTF:

```text
ilivalidator 1.15.0
```

Lokaler Fallback:

```text
/Users/stefan/apps/ilivalidator-1.15.0/ilivalidator-1.15.0.jar
```

Ungültige Testdateien müssen:

- klar als negativ markiert sein,
- erwarteten Fehler dokumentieren.

---

# 24. Java-Unit-Tests

## 24.1 `InterlisGeometryEncoderTest`

Testfälle:

```text
encodePoint2d
encodePoint3d
encodeMultiPoint
encodeLineString
encodeMultiLineString
encodePolygon
encodePolygonWithHole
encodeMultiPolygon
encodeArea
encodeMultiArea
missingGeometryReturnsEmpty
multipleValuesAreRejected
unsupportedTagFails
customLineFormFails
clippedPolylineFails
lineAttributesFail
arcFailsInFailMode
arcLinearizesInLinearizeMode
```

## 24.2 WKB-Inhaltsprüfung

Nicht nur „nicht leer“.

WKB muss mit JTS oder DuckDB Spatial gelesen werden.

Java-seitig:

```java
Geometry geometry =
    new WKBReader().read(value.wkb());

assertEquals("Point", geometry.getGeometryType());
```

Bei alter JTS-Package-Version die passende Importklasse verwenden.

## 24.3 Koordinaten prüfen

Beispiel:

```java
assertEquals(2605000.0, point.getX(), 0.000001);
assertEquals(1203000.0, point.getY(), 0.000001);
```

## 24.4 3D prüfen

```java
assertFalse(Double.isNaN(point.getCoordinate().getZ()));
```

Falls nicht unterstützt:

- Test erwartet definierte Exception,
- keine stille 2D-Ausgabe.

---

# 25. DuckDB-Spatial-Integrationstests

## 25.1 Neue Datei

```text
sql/spatial.sql
```

## 25.2 Vorbereitung

```sql
INSTALL spatial;
LOAD spatial;
LOAD interlis;
```

CI muss sicherstellen, dass `spatial` installierbar ist oder ein vorinstalliertes Extension-Verzeichnis verwenden.

## 25.3 Typprüfungen

### POINT

```sql
SELECT
    ST_GeometryType(
        ST_GeomFromHEXWKB(Lage_wkb)
    ) = 'POINT'
FROM read_xtf_class(...);
```

### MULTIPOINT

```sql
... = 'MULTIPOINT'
```

### LINESTRING

```sql
... = 'LINESTRING'
```

### MULTILINESTRING

```sql
... = 'MULTILINESTRING'
```

### POLYGON

```sql
... = 'POLYGON'
```

### MULTIPOLYGON

```sql
... = 'MULTIPOLYGON'
```

Die exakten Rückgabestrings von `ST_GeometryType()` sind anhand der verwendeten DuckDB-Spatial-Version zu prüfen.

## 25.4 Validität

```sql
SELECT
    bool_and(
        ST_IsValid(
            ST_GeomFromHEXWKB(geom_wkb)
        )
    )
FROM ...;
```

## 25.5 Innenring

```sql
SELECT
    ST_NumInteriorRings(
        ST_GeomFromHEXWKB(Flaeche_wkb)
    )
FROM ...;
```

Erwartet:

```text
1
```

## 25.6 Z-Dimension

```sql
SELECT
    ST_HasZ(
        ST_GeomFromHEXWKB(Lage3d_wkb)
    )
FROM ...;
```

## 25.7 Transformation

Nur wenn CRS-Mapping konfiguriert:

```sql
SELECT
    ST_AsText(
        ST_Transform(
            ST_GeomFromHEXWKB(Lage_wkb),
            'EPSG:2056',
            'EPSG:4326',
            always_xy := true
        )
    )
FROM ...;
```

Nicht auf exakten langen WKT-Text prüfen, sondern auf plausiblen Koordinatenbereich.

---

# 26. C-/Native-Tests

## 26.1 Schema

Prüfen:

```text
Lage_wkb ist VARCHAR
```

und nicht fälschlich BLOB.

## 26.2 NULL

Fehlende Geometrie:

```sql
Lage_wkb IS NULL
```

muss `true` sein.

Nicht:

```text
''
```

## 26.3 HEX

Prüfen:

- gerade Länge,
- nur Hex-Zeichen,
- WKB-Header plausibel.

## 26.4 Mehrere Geometrieattribute

Dynamisches Schema muss jede Geometriespalte genau einmal enthalten.

---

# 27. Import-SQL und Geometrien

## 27.1 Bestehendes Verhalten

Die generierte Tabelle enthält weiterhin:

```text
<attribute>_wkb VARCHAR
```

## 27.2 Dokumentation

`ili_generate_import_sql()` muss klar dokumentieren:

> Geometriespalten enthalten HEX-WKB als VARCHAR. Zur Nutzung mit DuckDB Spatial ist `ST_GeomFromHEXWKB()` erforderlich.

## 27.3 Optionaler Importmodus

Später optional:

```text
geometry_mapping = hexwkb | blob | spatial
```

Für diese Spezifikation nicht erforderlich.

## 27.4 Beispiel

```sql
CREATE TABLE spatial_copy AS
SELECT
    * EXCLUDE (Flaeche_wkb),
    ST_GeomFromHEXWKB(Flaeche_wkb) AS Flaeche
FROM imported.topic__flaechenobjekt;
```

---

# 28. Dokumentation

## 28.1 Neue Datei `docs/geometry.md`

Pflichtinhalte:

1. unterstützte INTERLIS-Geometrietypen,
2. aktuelle Ausgabe als HEX-WKB,
3. SQL-Datentyp `VARCHAR`,
4. Laden der Spatial-Extension,
5. `ST_GeomFromHEXWKB`,
6. CRS-Verhalten,
7. 3D-Verhalten,
8. ARC-Verhalten,
9. AREA/MULTIAREA,
10. NULL-Verhalten,
11. Beispiele,
12. bekannte Limitierungen.

## 28.2 `docs/functions.md`

Korrigieren:

- `ST_GeomFromWKB` → `ST_GeomFromHEXWKB`,
- `INSTALL spatial; LOAD spatial;`,
- `_wkb` exakt erklären,
- CRS-Beispiel,
- Geometrietyp-Beispiele,
- `geom_json`-Vertrag,
- `ili_geometry_attributes()` ergänzen.

## 28.3 `docs/limitations.md`

Ergänzen:

- kein eingebettetes SRID,
- keine automatische CRS-Erkennung,
- aktueller HEX-WKB-Transport,
- ARC-Linearisierung,
- custom line forms,
- clipped polylines,
- line attributes,
- 3D-Status.

## 28.4 `docs/architecture.md`

Neuer Abschnitt:

```text
Geometry Pipeline
```

Diagramm:

```text
INTERLIS model metadata
→ GeometryMetadata

IomObject geometry
→ InterlisGeometryEncoder
→ WKB bytes
→ HEX transport
→ DuckDB VARCHAR
→ DuckDB Spatial
```

---

# 29. JavaDocs

## 29.1 `InterlisGeometryEncoder`

Pflicht-Javadoc:

- unterstützte Typen,
- WKB-Format,
- Z-Verhalten,
- ARC-Policy,
- Exceptions.

## 29.2 `GeometryMetadata`

Jedes Feld dokumentieren.

## 29.3 `GeometryCrsResolver`

Explizit dokumentieren:

> CRS is resolved only from explicit mappings. No coordinate-value heuristics are used.

## 29.4 `XtfObjectReader`

Klassen-Javadoc aktualisieren:

- Geometriepfad,
- HEX-WKB,
- NULL,
- keine automatische Spatial-Konvertierung.

---

# 30. Fehlerbehandlung

## 30.1 Kein stilles NULL bei Konvertierungsfehler

Nicht zulässig:

```java
default -> null;
```

Zulässig nur für tatsächlich fehlendes Geometrieattribut.

## 30.2 Fehlermeldung

Beispiel:

```text
Geometry conversion failed:
class=Model.Topic.Class
attribute=Model.Topic.Class.Geometry
tid=123
declaredKind=AREA
iomTag=AREA
reason=custom line form not supported
```

## 30.3 Native Status

- ungültiger Parameter → `INVALID_ARGUMENT`
- nicht unterstützte Geometrieform → `UNSUPPORTED`
- kaputte Transfergeometrie → `PARSE_ERROR`
- unerwarteter Fehler → `INTERNAL_ERROR`

Der Java-Native-Entry-Point muss Exceptions differenziert abbilden.

---

# 31. Performance

## 31.1 Keine doppelte Konvertierung

Eine Geometrie darf pro Objekt und Attribut nur einmal konvertiert werden.

## 31.2 Byte-Array intern

Kein mehrfaches:

```text
JTS → HEX → bytes → HEX
```

Intern:

```text
JTS → byte[]
```

Einmaliges HEX-Encoding an Transportgrenze.

## 31.3 Keine WKB-Roundtrip-Validierung im Produktionsdefault

`validateWkbRoundtrip=false`.

Nur Tests/Debugging.

## 31.4 Modellmetadaten cachen

`GeometryMetadata` pro Klasse und Attribut zusammen mit dem bestehenden Modellcache wiederverwenden.

Optional:

```java
ConcurrentHashMap<String, List<GeometryMetadata>>
```

aber nur, wenn der bestehende Modellcache nicht sauber erweitert werden kann.

---

# 32. Vorbereitung auf Meilenstein B

Die Geometriekomponenten dürfen keinen vollständigen Resultset benötigen.

`InterlisGeometryEncoder` arbeitet rein pro Objekt/Attribut:

```java
Optional<GeometryValue> encodeAttribute(...)
```

Damit ist die Klasse direkt in einem späteren Cursor verwendbar.

Nicht zulässig:

```java
List<GeometryValue> encodeWholeFile(...)
```

---

# 33. Nicht umsetzen

Nicht Bestandteil:

1. eigene XML-Geometrieparser,
2. eigene INTERLIS-2.3-/2.4-Unterscheidung,
3. Fork von iox-ili,
4. automatische CRS-Heuristik,
5. automatische Reprojektion,
6. direkte harte Abhängigkeit auf DuckDB Spatial,
7. vollständige BLOB-/typed-batch-ABI vor Meilenstein B,
8. GeoParquet-Export,
9. räumliche Indizes,
10. Topologieaufbau ausserhalb bestehender INTERLIS-Libraries.

---

# 34. Verbindliche Umsetzungsphasen

## Phase G1 – Dokumentations- und Testkorrektur

- Spatial-Beispiele korrigieren.
- vorhandene sechs Grundtypen mit DuckDB Spatial testen.
- aktuelle Limitierungen dokumentieren.

## Phase G2 – Geometriecode extrahieren

- neue Geometry-Packages,
- TypeResolver,
- Metadata,
- Encoder,
- Extractor,
- HexCodec.

## Phase G3 – CRS-Metadaten

- Resolver,
- Mapping,
- `ili_geometry_attributes`.

## Phase G4 – schwierige Geometrien

- AREA,
- MULTIAREA,
- Innenringe,
- ARC,
- custom line forms,
- clipped polylines,
- line attributes,
- 3D.

## Phase G5 – generischer Reader

- detailliertes `geom_json`.

## Phase G6 – Dokumentation und Release-Härtung

- vollständige Benutzer- und Entwickler-Doku,
- Changelog,
- CI-Matrix.

---

# 35. Empfohlene Commit-Struktur

```text
docs(geometry): correct DuckDB Spatial usage examples
test(spatial): verify basic INTERLIS geometry types in DuckDB
refactor(geometry): add geometry metadata and type resolver
refactor(geometry): extract geometry encoder from XtfObjectReader
feat(geometry): add explicit CRS metadata resolver
feat(model): expose ili_geometry_attributes table function
feat(geometry): support and test AREA and MULTIAREA
feat(geometry): define arc and unsupported line-form behavior
feat(geometry): preserve or explicitly reject 3D loss
feat(xtf): expose detailed geometry JSON in generic reader
test(geometry): add holes, arcs, 3D and negative fixtures
docs(geometry): document formats, CRS, limitations and examples
```

Jeder Commit muss bauen und die betroffenen Tests bestehen.

---

# 36. Definition of Done

## Grundtypen

- [ ] COORD
- [ ] MULTICOORD
- [ ] POLYLINE
- [ ] MULTIPOLYLINE
- [ ] SURFACE
- [ ] MULTISURFACE

## Erweiterte Typen

- [ ] AREA explizit behandelt
- [ ] MULTIAREA explizit behandelt
- [ ] Innenring getestet
- [ ] mehrere Geometrieattribute getestet
- [ ] TypeAlias getestet
- [ ] Geometrie in Struktur getestet oder dokumentiert abgelehnt

## Format

- [ ] intern `byte[] WKB`
- [ ] öffentliche bestehende Ausgabe HEX-WKB
- [ ] Suffix und Format dokumentiert
- [ ] NULL korrekt
- [ ] kein stilles leeres Stringresultat

## Spatial

- [ ] `LOAD spatial`
- [ ] `ST_GeomFromHEXWKB`
- [ ] Geometrietyp geprüft
- [ ] Validität geprüft
- [ ] WKT geprüft
- [ ] Innenringe geprüft
- [ ] 3D geprüft
- [ ] Transformationsbeispiel dokumentiert

## CRS

- [ ] keine Heuristik
- [ ] explizites Mapping
- [ ] Metadatenfunktion
- [ ] unbekanntes CRS bleibt NULL
- [ ] EPSG:2056-Beispiel

## Fehler

- [ ] unknown tag → Fehler
- [ ] custom line form → definierter Fehler
- [ ] clipped polyline → definierter Fehler
- [ ] line attributes → definierter Fehler
- [ ] multiple values → definierter Fehler
- [ ] 3D-Verlust nicht still

## Codequalität

- [ ] Geometriecode aus `XtfObjectReader` entfernt
- [ ] Klassen-Javadoc
- [ ] Methoden-Javadoc
- [ ] Unit Tests
- [ ] Integrationstests
- [ ] deterministische Ausgabe

## Testdaten

- [ ] alle `.ili` mit ili2c geprüft
- [ ] alle gültigen `.xtf` mit ilivalidator geprüft
- [ ] Negativdateien dokumentiert

---

# 37. Abschlussbericht des Coding-Agenten

Der Agent muss am Ende liefern:

1. Liste aller geänderten Dateien.
2. Liste aller neuen Klassen.
3. Liste unterstützter Geometrietypen.
4. Liste explizit nicht unterstützter Geometriekonstrukte.
5. Beschreibung des WKB-/HEX-WKB-Vertrags.
6. Beschreibung des CRS-Vertrags.
7. Beschreibung des 3D-Verhaltens.
8. Beschreibung des ARC-Verhaltens.
9. SQL-Beispiele für DuckDB Spatial.
10. Java-Testresultate.
11. DuckDB-Spatial-Testresultate.
12. ili2c-Prüfresultate.
13. ilivalidator-Prüfresultate.
14. bekannte Restlimitationen.
15. Bestätigung, dass keine automatische CRS-Heuristik implementiert wurde.
16. Bestätigung, dass DuckDB Spatial optional bleibt.

Der Agent darf nicht behaupten:

```text
full geometry support
```

wenn nicht mindestens folgende Fälle nachgewiesen sind:

- alle sechs OGC-Grundtypen,
- AREA/MULTIAREA entweder unterstützt oder klar abgelehnt,
- Innenringe,
- NULL,
- ARC-Policy,
- 3D-Policy,
- Spatial-Integration,
- CRS-Metadaten.
