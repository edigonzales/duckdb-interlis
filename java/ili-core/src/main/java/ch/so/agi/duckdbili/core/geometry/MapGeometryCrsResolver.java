package ch.so.agi.duckdbili.core.geometry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * CRS resolver backed by explicit mappings from environment variable
 * or properties file. Never guesses based on coordinate values.
 *
 * Configuration sources (checked in order):
 * 1. Environment variable: ILI_GEOMETRY_CRS_MAP
 *    Format: DomainFqn=AUTH:CODE;DomainFqn2=AUTH:CODE
 * 2. Environment variable: ILI_GEOMETRY_CRS_FILE
 *    Points to a Java properties file with DomainFqn=AUTH:CODE pairs.
 */
public final class MapGeometryCrsResolver implements GeometryCrsResolver {

    private static final String ENV_CRS_MAP = "ILI_GEOMETRY_CRS_MAP";
    private static final String ENV_CRS_FILE = "ILI_GEOMETRY_CRS_FILE";

    private final Map<String, CrsIdentifier> byDomainFqn;

    public MapGeometryCrsResolver() {
        this(loadMappings());
    }

    public MapGeometryCrsResolver(Map<String, CrsIdentifier> byDomainFqn) {
        this.byDomainFqn = Map.copyOf(Objects.requireNonNull(byDomainFqn));
    }

    @Override
    public Optional<CrsIdentifier> resolve(GeometryMetadataContext context) {
        if (context == null || context.coordinateDomainFqn() == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(byDomainFqn.get(context.coordinateDomainFqn()));
    }

    private static Map<String, CrsIdentifier> loadMappings() {
        Map<String, CrsIdentifier> map = new LinkedHashMap<>();

        String envMap = System.getenv(ENV_CRS_MAP);
        if (envMap != null && !envMap.isBlank()) {
            for (String entry : envMap.split(";")) {
                entry = entry.trim();
                if (entry.isEmpty()) continue;
                int eq = entry.indexOf('=');
                if (eq <= 0 || eq == entry.length() - 1) continue;
                String domain = entry.substring(0, eq).trim();
                String value = entry.substring(eq + 1).trim();
                CrsIdentifier crs = parseCrs(value);
                if (crs != null) map.put(domain, crs);
            }
        }

        String envFile = System.getenv(ENV_CRS_FILE);
        if (envFile != null && !envFile.isBlank()) {
            Path path = Path.of(envFile);
            if (Files.isRegularFile(path)) {
                try {
                    Properties props = new Properties();
                    props.load(Files.newBufferedReader(path));
                    for (String key : props.stringPropertyNames()) {
                        CrsIdentifier crs = parseCrs(props.getProperty(key));
                        if (crs != null) map.put(key.trim(), crs);
                    }
                } catch (IOException e) {
                    // ignore file errors – resolver should not crash
                }
            }
        }

        return map;
    }

    private static CrsIdentifier parseCrs(String value) {
        if (value == null || value.isBlank()) return null;
        int colon = value.indexOf(':');
        if (colon <= 0 || colon == value.length() - 1) return null;
        String auth = value.substring(0, colon).trim();
        String code = value.substring(colon + 1).trim();
        if (auth.isEmpty() || code.isEmpty()) return null;
        Integer srid = null;
        try { srid = Integer.parseInt(code); } catch (NumberFormatException ignored) {}
        return new CrsIdentifier(auth, code, srid);
    }
}
