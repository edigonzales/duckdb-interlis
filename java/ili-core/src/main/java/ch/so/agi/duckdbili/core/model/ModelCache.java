package ch.so.agi.duckdbili.core.model;

import ch.interlis.ili2c.metamodel.TransferDescription;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

public final class ModelCache {

    private static final ModelCache INSTANCE = new ModelCache();
    private static final int DEFAULT_MAX_SIZE = 64;

    private final ConcurrentHashMap<CacheKey, TransferDescription> map;
    private final ConcurrentLinkedDeque<CacheKey> accessOrder;
    private final int maxSize;

    private final LongAdder hits = new LongAdder();
    private final LongAdder misses = new LongAdder();
    private final LongAdder evictions = new LongAdder();
    private final ConcurrentHashMap<CacheKey, Long> compileTimesMs = new ConcurrentHashMap<>();

    private static final boolean DEBUG = "1".equals(System.getenv("DUCKDB_ILI_DEBUG"));

    private ModelCache() {
        String envSize = System.getenv("DUCKDB_ILI_CACHE_SIZE");
        int size = DEFAULT_MAX_SIZE;
        if (envSize != null) {
            try {
                int parsed = Integer.parseInt(envSize.trim());
                if (parsed > 0) size = parsed;
            } catch (NumberFormatException ignored) {}
        }
        this.maxSize = size;
        this.map = new ConcurrentHashMap<>();
        this.accessOrder = new ConcurrentLinkedDeque<>();
    }

    public static ModelCache getInstance() {
        return INSTANCE;
    }

    public TransferDescription getOrCompile(CacheKey key, Supplier<TransferDescription> compiler) {
        TransferDescription td = map.get(key);
        if (td != null) {
            hits.increment();
            touchAccessOrder(key);
            return td;
        }
        misses.increment();
        long start = System.currentTimeMillis();
        td = compiler.get();
        long elapsed = System.currentTimeMillis() - start;
        TransferDescription existing = map.putIfAbsent(key, td);
        if (existing != null) {
            td = existing;
        } else {
            addAccessOrder(key);
            evictIfNeeded();
        }
        if (DEBUG) compileTimesMs.put(key, elapsed);
        return td;
    }

    public void invalidateAll() {
        map.clear();
        accessOrder.clear();
    }

    public void invalidate(String modelDirPrefix) {
        if (modelDirPrefix == null) return;
        for (Iterator<CacheKey> it = map.keySet().iterator(); it.hasNext(); ) {
            CacheKey key = it.next();
            if (key.normalizedModelDir().contains(modelDirPrefix)) {
                it.remove();
                accessOrder.remove(key);
            }
        }
    }

    public CacheMetrics getMetrics() {
        return new CacheMetrics(
                hits.sum(),
                misses.sum(),
                evictions.sum(),
                map.size(),
                maxSize,
                new HashMap<>(compileTimesMs)
        );
    }

    public int size() {
        return map.size();
    }

    public void clear() {
        invalidateAll();
    }

    // ---------------------------------------------------------------
    // Cache key
    // ---------------------------------------------------------------

    public record CacheKey(
            String normalizedModelDir,
            Set<String> modelNames,
            String sha256Fingerprint
    ) {}

    // ---------------------------------------------------------------
    // Fingerprint computation
    // ---------------------------------------------------------------

    public static String computeFingerprint(String modelDir) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (String repo : ModelRepositoryResolver.resolve(modelDir, "")) {
                if (repo.startsWith("http://") || repo.startsWith("https://")) {
                    md.update(repo.getBytes(StandardCharsets.UTF_8));
                    continue;
                }
                Path dir = Path.of(repo);
                if (!Files.isDirectory(dir)) continue;
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.ili")) {
                    List<Path> sorted = new ArrayList<>();
                    for (Path f : ds) sorted.add(f);
                    sorted.sort(Comparator.comparing(Path::toString));
                    for (Path f : sorted) {
                        String abs = f.toAbsolutePath().toString();
                        long size = Files.size(f);
                        long mtime = Files.getLastModifiedTime(f).toMillis();
                        String entry = abs + ":" + size + ":" + mtime;
                        md.update(entry.getBytes(StandardCharsets.UTF_8));
                    }
                }
            }
            byte[] hash = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (IOException | NoSuchAlgorithmException e) {
            return "error:" + e.getMessage();
        }
    }

    // ---------------------------------------------------------------
    // LRU eviction (approximate)
    // ---------------------------------------------------------------

    private void touchAccessOrder(CacheKey key) {
        accessOrder.remove(key);
        accessOrder.addFirst(key);
    }

    private void addAccessOrder(CacheKey key) {
        accessOrder.addFirst(key);
    }

    private void evictIfNeeded() {
        while (map.size() > maxSize) {
            CacheKey oldest = accessOrder.pollLast();
            if (oldest == null) break;
            if (map.remove(oldest) != null) {
                evictions.increment();
                if (DEBUG) compileTimesMs.remove(oldest);
            }
        }
    }

    // ---------------------------------------------------------------
    // Metrics
    // ---------------------------------------------------------------

    public record CacheMetrics(
            long hits,
            long misses,
            long evictions,
            int currentSize,
            int maxSize,
            Map<CacheKey, Long> compileTimesMs
    ) {}
}
