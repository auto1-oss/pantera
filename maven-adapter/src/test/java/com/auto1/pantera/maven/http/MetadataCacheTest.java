/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.maven.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test for {@link MetadataCache}.
 */
class MetadataCacheTest {

    @Test
    void cachesMetadataAndReuses() {
        final MetadataCache cache = new MetadataCache(Duration.ofHours(1));
        final Key key = new Key.From("maven-metadata.xml");
        final AtomicInteger remoteCallCount = new AtomicInteger(0);
        
        // First call - cache miss
        Optional<Content> result1 = cache.load(
            key,
            () -> {
                remoteCallCount.incrementAndGet();
                return CompletableFuture.completedFuture(
                    Optional.of(new Content.From("test".getBytes(StandardCharsets.UTF_8)))
                );
            }
        ).join();
        
        MatcherAssert.assertThat("First call returns content", result1.isPresent(), Matchers.is(true));
        MatcherAssert.assertThat("Remote called once", remoteCallCount.get(), Matchers.is(1));
        
        // Second call - cache hit
        Optional<Content> result2 = cache.load(
            key,
            () -> {
                remoteCallCount.incrementAndGet();
                return CompletableFuture.completedFuture(Optional.empty());
            }
        ).join();
        
        MatcherAssert.assertThat("Second call returns cached content", result2.isPresent(), Matchers.is(true));
        MatcherAssert.assertThat("Remote not called again", remoteCallCount.get(), Matchers.is(1));
    }

    @Test
    void expiresAfterTtl() throws Exception {
        final MetadataCache cache = new MetadataCache(Duration.ofMillis(100));
        final Key key = new Key.From("maven-metadata.xml");
        final AtomicInteger remoteCallCount = new AtomicInteger(0);

        // First call
        cache.load(
            key,
            () -> {
                remoteCallCount.incrementAndGet();
                return CompletableFuture.completedFuture(
                    Optional.of(new Content.From("test".getBytes(StandardCharsets.UTF_8)))
                );
            }
        ).join();

        MatcherAssert.assertThat("First call made", remoteCallCount.get(), Matchers.is(1));

        // Wait past soft TTL (100ms) but before hard expiry (200ms)
        // Stale-while-revalidate: should return stale and trigger background refresh
        Thread.sleep(150);

        Optional<Content> staleResult = cache.load(
            key,
            () -> {
                remoteCallCount.incrementAndGet();
                return CompletableFuture.completedFuture(
                    Optional.of(new Content.From("new".getBytes(StandardCharsets.UTF_8)))
                );
            }
        ).join();

        MatcherAssert.assertThat("Stale content returned immediately", staleResult.isPresent(), Matchers.is(true));
        // Background refresh triggered — wait for it to complete
        Thread.sleep(100);
        MatcherAssert.assertThat("Remote called again via background refresh", remoteCallCount.get(), Matchers.is(2));

        // Wait for hard expiry (2x TTL = 200ms total, need another 150ms from last call)
        Thread.sleep(250);
        cache.cleanup();

        // After hard expiry, cache is empty — remote called again synchronously
        cache.load(
            key,
            () -> {
                remoteCallCount.incrementAndGet();
                return CompletableFuture.completedFuture(
                    Optional.of(new Content.From("newest".getBytes(StandardCharsets.UTF_8)))
                );
            }
        ).join();

        MatcherAssert.assertThat("Remote called after hard expiry", remoteCallCount.get(), Matchers.is(3));
    }

    @Test
    void invalidateRemovesEntry() {
        final MetadataCache cache = new MetadataCache(Duration.ofHours(1));
        final Key key = new Key.From("maven-metadata.xml");
        final AtomicInteger remoteCallCount = new AtomicInteger(0);
        
        // Cache entry
        cache.load(
            key,
            () -> {
                remoteCallCount.incrementAndGet();
                return CompletableFuture.completedFuture(
                    Optional.of(new Content.From("test".getBytes(StandardCharsets.UTF_8)))
                );
            }
        ).join();
        
        // Invalidate
        cache.invalidate(key);
        
        // Load again
        cache.load(
            key,
            () -> {
                remoteCallCount.incrementAndGet();
                return CompletableFuture.completedFuture(
                    Optional.of(new Content.From("new".getBytes(StandardCharsets.UTF_8)))
                );
            }
        ).join();
        
        MatcherAssert.assertThat("Remote called twice", remoteCallCount.get(), Matchers.is(2));
    }

    @Test
    void statsReturnsCorrectCounts() {
        final MetadataCache cache = new MetadataCache(Duration.ofMillis(100));
        
        // Add some entries
        cache.load(new Key.From("test1.xml"), () ->
            CompletableFuture.completedFuture(Optional.of(new Content.From("1".getBytes(StandardCharsets.UTF_8))))
        ).join();
        cache.load(new Key.From("test2.xml"), () ->
            CompletableFuture.completedFuture(Optional.of(new Content.From("2".getBytes(StandardCharsets.UTF_8))))
        ).join();
        
        com.github.benmanes.caffeine.cache.stats.CacheStats stats = cache.stats();
        MatcherAssert.assertThat("Cache size", cache.size(), Matchers.is(2L));
        MatcherAssert.assertThat("Hit rate", stats.hitRate(), Matchers.greaterThanOrEqualTo(0.0));
    }
}
