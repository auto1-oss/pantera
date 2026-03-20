/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.http.cache;

import com.auto1.pantera.asto.Key;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.greaterThan;

/**
 * Tests for {@link NegativeCache}.
 */
class NegativeCacheTest {

    @Test
    void cachesNotFoundKeys() {
        final NegativeCache cache = new NegativeCache(Duration.ofHours(1), true);
        final Key key = new Key.From("test/artifact.jar");
        
        // Initially not cached
        assertThat(cache.isNotFound(key), is(false));
        
        // Cache as not found
        cache.cacheNotFound(key);
        
        // Should be cached now
        assertThat(cache.isNotFound(key), is(true));
    }

    @Test
    void respectsTtlExpiry() throws InterruptedException {
        // Cache with very short TTL
        final NegativeCache cache = new NegativeCache(Duration.ofMillis(100), true);
        final Key key = new Key.From("test/missing.jar");
        
        // Cache as 404
        cache.cacheNotFound(key);
        assertThat("Should be cached immediately", cache.isNotFound(key), is(true));
        
        // Wait for expiry
        TimeUnit.MILLISECONDS.sleep(150);
        
        // Should not be cached anymore
        assertThat("Should expire after TTL", cache.isNotFound(key), is(false));
    }

    @Test
    void invalidatesSingleKey() {
        final NegativeCache cache = new NegativeCache(Duration.ofHours(1), true);
        final Key key = new Key.From("test/artifact.jar");
        
        cache.cacheNotFound(key);
        assertThat(cache.isNotFound(key), is(true));
        
        // Invalidate the key
        cache.invalidate(key);
        
        // Should not be cached anymore
        assertThat(cache.isNotFound(key), is(false));
    }

    @Test
    void invalidatesWithPrefix() {
        final NegativeCache cache = new NegativeCache(Duration.ofHours(1), true);
        final Key key1 = new Key.From("org/example/artifact-1.0.jar");
        final Key key2 = new Key.From("org/example/artifact-2.0.jar");
        final Key key3 = new Key.From("com/other/library-1.0.jar");
        
        // Cache all three
        cache.cacheNotFound(key1);
        cache.cacheNotFound(key2);
        cache.cacheNotFound(key3);
        
        // Invalidate by prefix
        cache.invalidatePrefix("org/example");
        
        // org/example keys should be gone, com/other should remain
        assertThat(cache.isNotFound(key1), is(false));
        assertThat(cache.isNotFound(key2), is(false));
        assertThat(cache.isNotFound(key3), is(true));
    }

    @Test
    void clearsAllEntries() {
        final NegativeCache cache = new NegativeCache(Duration.ofHours(1), true);
        cache.cacheNotFound(new Key.From("artifact1.jar"));
        cache.cacheNotFound(new Key.From("artifact2.jar"));
        cache.cacheNotFound(new Key.From("artifact3.jar"));
        
        assertThat(cache.size(), is(3L));
        
        cache.clear();
        
        assertThat(cache.size(), is(0L));
        assertThat(cache.isNotFound(new Key.From("artifact1.jar")), is(false));
    }

    @Test
    void doesNotCacheWhenDisabled() {
        final NegativeCache cache = new NegativeCache(Duration.ofHours(1), false);
        final Key key = new Key.From("test/artifact.jar");
        
        cache.cacheNotFound(key);
        
        // Should not be cached when disabled
        assertThat(cache.isNotFound(key), is(false));
    }

    @Test
    void tracksCacheStatistics() {
        final NegativeCache cache = new NegativeCache(Duration.ofHours(1), true);
        final Key key = new Key.From("test/artifact.jar");
        
        // Cache a key
        cache.cacheNotFound(key);
        
        // Hit the cache
        cache.isNotFound(key);
        cache.isNotFound(key);
        
        // Miss the cache
        cache.isNotFound(new Key.From("other/artifact.jar"));
        
        final com.github.benmanes.caffeine.cache.stats.CacheStats stats = cache.stats();
        assertThat("Should have 2 hits", stats.hitCount(), is(2L));
        assertThat("Should have 1 miss", stats.missCount(), is(1L));
        assertThat("Should have hit rate > 0", stats.hitRate(), greaterThan(0.0));
    }

    @Test
    void respectsMaxSizeLimit() {
        // Cache with max size of 10 (single-tier, no Valkey)
        final NegativeCache cache = new NegativeCache(Duration.ofHours(1), true, 10, null);
        
        // Add many more entries to trigger eviction
        for (int i = 0; i < 100; i++) {
            cache.cacheNotFound(new Key.From("artifact" + i + ".jar"));
        }
        
        // Force cleanup to apply size limit
        cache.cleanup();
        
        // Size should be close to max (Caffeine uses approximate sizing)
        assertThat("Size should be around max", cache.size() <= 15, is(true));
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void handlesHighConcurrency() throws InterruptedException {
        final NegativeCache cache = new NegativeCache(Duration.ofHours(1), true);
        final int threadCount = 100;
        final int opsPerThread = 100;
        final Thread[] threads = new Thread[threadCount];
        
        // Start threads that concurrently cache and check
        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            threads[i] = new Thread(() -> {
                for (int j = 0; j < opsPerThread; j++) {
                    final Key key = new Key.From(
                        String.format("thread%d/artifact%d.jar", threadId, j)
                    );
                    cache.cacheNotFound(key);
                    cache.isNotFound(key);
                }
            });
            threads[i].start();
        }
        
        // Wait for all threads
        for (Thread thread : threads) {
            thread.join();
        }
        
        // Should have all entries (or up to max size if eviction occurred)
        assertThat("Should have cached entries", cache.size(), greaterThan(0L));
    }

    @Test
    void isEnabledReflectsConfiguration() {
        final NegativeCache enabled = new NegativeCache(Duration.ofHours(1), true);
        final NegativeCache disabled = new NegativeCache(Duration.ofHours(1), false);
        
        assertThat("Enabled cache should report enabled", enabled.isEnabled(), is(true));
        assertThat("Disabled cache should report disabled", disabled.isEnabled(), is(false));
    }

    @Test
    void returnsCorrectCacheSize() {
        final NegativeCache cache = new NegativeCache(Duration.ofHours(1), true);
        
        assertThat(cache.size(), is(0L));
        
        cache.cacheNotFound(new Key.From("artifact1.jar"));
        assertThat(cache.size(), is(1L));
        
        cache.cacheNotFound(new Key.From("artifact2.jar"));
        assertThat(cache.size(), is(2L));
        
        cache.invalidate(new Key.From("artifact1.jar"));
        assertThat(cache.size(), is(1L));
    }

    @Test
    void tracksEvictionsCorrectly() {
        // Cache with small size to trigger evictions (single-tier, no Valkey)
        final NegativeCache cache = new NegativeCache(Duration.ofHours(1), true, 5, null);
        
        // Cache many more entries than max size
        for (int i = 0; i < 100; i++) {
            cache.cacheNotFound(new Key.From("artifact" + i + ".jar"));
            // Access some to trigger Window TinyLFU eviction
            if (i % 10 == 0) {
                cache.isNotFound(new Key.From("artifact" + i + ".jar"));
            }
        }
        
        // Force cleanup
        cache.cleanup();
        
        // Size should be controlled (approximate)
        assertThat("Size should be controlled", cache.size() <= 10, is(true));
        // Should have processed entries
        assertThat("Should have processed entries", cache.size(), greaterThan(0L));
    }

    @Test
    void performanceIsAcceptable() {
        final NegativeCache cache = new NegativeCache(Duration.ofHours(1), true);
        final int operations = 10000;
        
        final long startTime = System.nanoTime();
        
        // Perform many operations
        for (int i = 0; i < operations; i++) {
            final Key key = new Key.From("artifact" + i + ".jar");
            cache.cacheNotFound(key);
            cache.isNotFound(key);
        }
        
        final long duration = System.nanoTime() - startTime;
        final double opsPerMs = operations / (duration / 1_000_000.0);
        
        // Should be able to handle at least 200 ops/ms (conservative for CI environments)
        assertThat("Should have good performance", opsPerMs, greaterThan(200.0));
    }
}
