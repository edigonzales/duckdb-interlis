package ch.so.agi.duckdbili.core.model;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.Collections.emptySet;
import static org.junit.jupiter.api.Assertions.*;

class ModelCacheTest {

    private static Path TMP_DIR;
    private static Path TEST_ILI;

    @BeforeAll
    static void resolvePaths() {
        Path cwd = Path.of("").toAbsolutePath();
        Path testDir = cwd.resolve("testdata/synthetic/simple");
        if (!Files.isRegularFile(testDir.resolve("SO_AGI_Simple_20260605.ili"))) {
            testDir = cwd.getParent().resolve("testdata/synthetic/simple");
        }
        if (!Files.isRegularFile(testDir.resolve("SO_AGI_Simple_20260605.ili"))) {
            testDir = cwd.getParent().getParent().resolve("testdata/synthetic/simple");
        }
        TEST_ILI = testDir.resolve("SO_AGI_Simple_20260605.ili");
    }

    @BeforeEach
    @AfterEach
    void cleanup() {
        ModelCache.getInstance().invalidateAll();
    }

    // ---------------------------------------------------------------
    // Cache hit/miss
    // ---------------------------------------------------------------

    @Test
    @DisplayName("Cache hit returns same TransferDescription object")
    void cacheHitReturnsSameObject() {
        ModelCache cache = ModelCache.getInstance();
        String modelDir = TEST_ILI.getParent().toString();
        String fingerprint = ModelCache.computeFingerprint(modelDir);
        ModelCache.CacheKey key = new ModelCache.CacheKey(modelDir, emptySet(), fingerprint);

        var td1 = cache.getOrCompile(key, () -> {
            IliModelService svc = new IliModelService();
            return callPrivateCompile(svc, modelDir);
        });
        var td2 = cache.getOrCompile(key, () -> {
            fail("Should not call compiler on cache hit");
            return null;
        });

        assertSame(td1, td2, "Cache hit should return same TransferDescription instance");
        assertEquals(1, cache.size());
    }

    @Test
    @DisplayName("Different modelDir produces cache miss")
    void differentModelDirIsCacheMiss() {
        ModelCache cache = ModelCache.getInstance();
        String modelDir = TEST_ILI.getParent().toString();
        String fingerprint = ModelCache.computeFingerprint(modelDir);
        ModelCache.CacheKey key1 = new ModelCache.CacheKey(modelDir, emptySet(), fingerprint);
        ModelCache.CacheKey key2 = new ModelCache.CacheKey(modelDir + "/nonexistent", emptySet(), "no-fingerprint");

        var td1 = cache.getOrCompile(key1, () -> {
            IliModelService svc = new IliModelService();
            return callPrivateCompile(svc, modelDir);
        });

        // key2 should be a cache miss; it fails because dir doesn't exist
        RuntimeException ex = assertThrows(RuntimeException.class, () ->
            cache.getOrCompile(key2, () -> {
                throw new RuntimeException("dir not found");
            })
        );
        assertTrue(ex.getMessage().contains("dir not found"));

        assertEquals(1, cache.size());
        assertNotNull(td1);
    }

    @Test
    @DisplayName("Failed compilation is not cached")
    void failedCompilationNotCached() {
        ModelCache cache = ModelCache.getInstance();
        ModelCache.CacheKey key = new ModelCache.CacheKey("/nonexistent", emptySet(), "fingerprint1");

        AtomicInteger calls = new AtomicInteger(0);
        RuntimeException ex1 = assertThrows(RuntimeException.class, () ->
            cache.getOrCompile(key, () -> {
                calls.incrementAndGet();
                throw new RuntimeException("compilation failed");
            })
        );
        assertEquals(1, calls.get());

        RuntimeException ex2 = assertThrows(RuntimeException.class, () ->
            cache.getOrCompile(key, () -> {
                calls.incrementAndGet();
                throw new RuntimeException("compilation failed again");
            })
        );
        assertEquals(2, calls.get(), "Failed compilation should not be cached; compiler called again");
        assertEquals(0, cache.size());
    }

    // ---------------------------------------------------------------
    // Fingerprint
    // ---------------------------------------------------------------

    @Test
    @DisplayName("Fingerprint changes trigger cache miss")
    void fingerprintChangeTriggersMiss() {
        ModelCache cache = ModelCache.getInstance();
        String modelDir = TEST_ILI.getParent().toString();

        String fp1 = ModelCache.computeFingerprint(modelDir);
        ModelCache.CacheKey key1 = new ModelCache.CacheKey(modelDir, emptySet(), fp1);

        var td1 = cache.getOrCompile(key1, () -> {
            IliModelService svc = new IliModelService();
            return callPrivateCompile(svc, modelDir);
        });

        // Key with different fingerprint (simulating file change)
        ModelCache.CacheKey key2 = new ModelCache.CacheKey(modelDir, emptySet(), "different_fingerprint");

        AtomicInteger called = new AtomicInteger(0);
        var td2 = cache.getOrCompile(key2, () -> {
            called.incrementAndGet();
            IliModelService svc = new IliModelService();
            return callPrivateCompile(svc, modelDir);
        });

        assertEquals(1, called.get(), "Different fingerprint should trigger recompilation");
        assertNotSame(td1, td2, "Different fingerprints should not return same cached value");
    }

    // ---------------------------------------------------------------
    // Invalidation
    // ---------------------------------------------------------------

    @Test
    @DisplayName("invalidateAll clears entire cache")
    void invalidateAllClearsCache() {
        ModelCache cache = ModelCache.getInstance();
        String modelDir = TEST_ILI.getParent().toString();
        String fingerprint = ModelCache.computeFingerprint(modelDir);
        ModelCache.CacheKey key = new ModelCache.CacheKey(modelDir, emptySet(), fingerprint);

        cache.getOrCompile(key, () -> {
            IliModelService svc = new IliModelService();
            return callPrivateCompile(svc, modelDir);
        });
        assertEquals(1, cache.size());

        cache.invalidateAll();
        assertEquals(0, cache.size());

        AtomicInteger called = new AtomicInteger(0);
        cache.getOrCompile(key, () -> {
            called.incrementAndGet();
            IliModelService svc = new IliModelService();
            return callPrivateCompile(svc, modelDir);
        });
        assertEquals(1, called.get(), "After invalidation, compiler should be called again");
    }

    @Test
    @DisplayName("invalidate by prefix removes matching entries")
    void invalidateByPrefix() {
        ModelCache cache = ModelCache.getInstance();
        String modelDir = TEST_ILI.getParent().toString();
        String fingerprint = ModelCache.computeFingerprint(modelDir);

        ModelCache.CacheKey key1 = new ModelCache.CacheKey(modelDir, emptySet(), fingerprint);
        ModelCache.CacheKey key2 = new ModelCache.CacheKey(modelDir + "/extra", emptySet(), "other");

        cache.getOrCompile(key1, () -> {
            IliModelService svc = new IliModelService();
            return callPrivateCompile(svc, modelDir);
        });
        cache.getOrCompile(key2, () -> {
            IliModelService svc = new IliModelService();
            return callPrivateCompile(svc, modelDir);
        });
        assertEquals(2, cache.size());

        cache.invalidate(modelDir);
        assertEquals(0, cache.size(), "Both entries should be invalidated (prefix match)");
    }

    // ---------------------------------------------------------------
    // Thread safety
    // ---------------------------------------------------------------

    @Test
    @DisplayName("Concurrent access does not corrupt cache")
    void concurrentAccessIsSafe() throws Exception {
        ModelCache cache = ModelCache.getInstance();
        String modelDir = TEST_ILI.getParent().toString();
        String fingerprint = ModelCache.computeFingerprint(modelDir);
        ModelCache.CacheKey key = new ModelCache.CacheKey(modelDir, emptySet(), fingerprint);

        int threads = 8;
        CountDownLatch latch = new CountDownLatch(threads);
        AtomicReference<Exception> error = new AtomicReference<>();

        for (int t = 0; t < threads; t++) {
            new Thread(() -> {
                try {
                    for (int i = 0; i < 5; i++) {
                        var td = cache.getOrCompile(key, () -> {
                            IliModelService svc = new IliModelService();
                            return callPrivateCompile(svc, modelDir);
                        });
                        assertNotNull(td);
                    }
                } catch (Exception e) {
                    error.set(e);
                } finally {
                    latch.countDown();
                }
            }).start();
        }

        latch.await();
        assertNull(error.get(), "No concurrent access exceptions: " +
                (error.get() != null ? error.get().getMessage() : ""));
        assertTrue(cache.size() >= 1, "Cache should have entries");
    }

    // ---------------------------------------------------------------
    // Metrics
    // ---------------------------------------------------------------

    @Test
    @DisplayName("Metrics track hits and misses")
    void metricsTrackHitsAndMisses() {
        ModelCache cache = ModelCache.getInstance();
        String modelDir = TEST_ILI.getParent().toString();
        String fingerprint = ModelCache.computeFingerprint(modelDir);

        var metricsBefore = cache.getMetrics();
        long initialMisses = metricsBefore.misses();

        ModelCache.CacheKey key1 = new ModelCache.CacheKey(modelDir, emptySet(), fingerprint);
        cache.getOrCompile(key1, () -> {
            IliModelService svc = new IliModelService();
            return callPrivateCompile(svc, modelDir);
        });

        var metricsAfterFirst = cache.getMetrics();
        assertTrue(metricsAfterFirst.misses() > initialMisses, "Miss count should increase after first access");

        cache.getOrCompile(key1, () -> {
            fail("Should not recompile on hit");
            return null;
        });

        var metricsAfterSecond = cache.getMetrics();
        assertTrue(metricsAfterSecond.hits() > 0, "Hit count should increase on second access");
    }

    // ---------------------------------------------------------------
    // Helper: invoke private compileIli via reflection
    // ---------------------------------------------------------------

    private static ch.interlis.ili2c.metamodel.TransferDescription callPrivateCompile(
            IliModelService svc, String modelDir) {
        try {
            var method = IliModelService.class.getDeclaredMethod("doCompileIli", String.class);
            method.setAccessible(true);
            return (ch.interlis.ili2c.metamodel.TransferDescription) method.invoke(svc, modelDir);
        } catch (Exception e) {
            if (e.getCause() instanceof RuntimeException re) throw re;
            throw new RuntimeException("Reflection error", e);
        }
    }
}
