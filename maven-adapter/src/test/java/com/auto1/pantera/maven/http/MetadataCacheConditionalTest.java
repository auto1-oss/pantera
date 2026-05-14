/*
 * Copyright (c) 2025-2026 Auto1 Group
 * Maintainers: Auto1 DevOps Team
 * Lead Maintainer: Ayd Asraf
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License v3.0.
 *
 * Originally based on Artipie (https://github.com/artipie/artipie), MIT License.
 */
package com.auto1.pantera.maven.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.maven.http.MetadataCache.ConditionalRequest;
import com.auto1.pantera.maven.http.MetadataCache.MetadataFetchResult;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.IsNot;
import org.hamcrest.core.IsNull;
import org.junit.jupiter.api.Test;

/**
 * Tests for T-P10: conditional GET in MetadataCache.
 *
 * <p>Verifies that:
 * <ul>
 *   <li>Cold miss: loader is called with empty validators, 200 bytes are
 *       cached.</li>
 *   <li>Stale refresh: loader is called with stored ETag + Last-Modified.</li>
 *   <li>304 response: lastVerified advances, blob bytes are byte-for-byte
 *       identical.</li>
 *   <li>200 response with new bytes: cache is replaced.</li>
 *   <li>404 response: cache entry is cleared.</li>
 * </ul>
 */
final class MetadataCacheConditionalTest {

    private static final Key KEY = new Key.From("org/example/foo/maven-metadata.xml");

    private static final byte[] V1 = "<v1/>".getBytes(StandardCharsets.UTF_8);

    private static final byte[] V2 = "<v2/>".getBytes(StandardCharsets.UTF_8);

    @Test
    void coldMissCallsLoaderWithEmptyValidatorsAndCachesBytes() {
        final AtomicReference<ConditionalRequest> captured = new AtomicReference<>();
        final MetadataCache cache = new MetadataCache(
            Duration.ofMillis(50), Duration.ofMinutes(1)
        );
        final Optional<Content> result = cache.load(
            KEY,
            req -> {
                captured.set(req);
                return CompletableFuture.completedFuture(
                    MetadataFetchResult.modified(V1, "\"abc\"", "Wed, 01 Jan 2025 00:00:00 GMT")
                );
            }
        ).join();
        MatcherAssert.assertThat(
            "Cold-miss validators must be empty",
            captured.get().isEmpty(), new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "Cold-miss returns the upstream bytes",
            result.isPresent(), new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "Cold-miss returns the exact upstream bytes",
            Arrays.equals(result.get().asBytes(), V1), new IsEqual<>(true)
        );
    }

    @Test
    void staleRefreshSendsStoredValidatorsToLoader() {
        final MutableClock clock = MutableClock.at(Instant.parse("2025-01-01T00:00:00Z"));
        final AtomicInteger calls = new AtomicInteger();
        final AtomicReference<ConditionalRequest> capturedSecond = new AtomicReference<>();
        final MetadataCache cache = new MetadataCache(
            Duration.ofSeconds(1), Duration.ofMinutes(1),
            10_000, null, "test", clock
        );
        // Seed: cold miss with validators.
        cache.load(KEY, req -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(
                MetadataFetchResult.modified(V1, "\"v1-etag\"", "Wed, 01 Jan 2025 00:00:00 GMT")
            );
        }).join();
        MatcherAssert.assertThat(calls.get(), new IsEqual<>(1));
        // Advance time past soft TTL but within hard TTL.
        clock.advance(Duration.ofSeconds(30));
        // Second load: should serve cached AND fire a single-flighted
        // background refresh that supplies the stored validators.
        cache.load(KEY, req -> {
            capturedSecond.set(req);
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(MetadataFetchResult.unmodified());
        }).join();
        // Wait briefly for the background refresh to complete.
        waitForCalls(calls, 2, Duration.ofSeconds(2));
        MatcherAssert.assertThat(
            "Background refresh observed loader call count",
            calls.get(), new IsEqual<>(2)
        );
        MatcherAssert.assertThat(
            "Background refresh sees the stored ETag",
            capturedSecond.get().etag().orElse(null), new IsEqual<>("\"v1-etag\"")
        );
        MatcherAssert.assertThat(
            "Background refresh sees the stored Last-Modified",
            capturedSecond.get().lastModified().orElse(null),
            new IsEqual<>("Wed, 01 Jan 2025 00:00:00 GMT")
        );
    }

    @Test
    void notModifiedDoesNotRewriteBlobButBumpsLastVerified() {
        final MutableClock clock = MutableClock.at(Instant.parse("2025-01-01T00:00:00Z"));
        // Use small soft TTL + large hard TTL so a single load() after time
        // advance falls into the stale window without exiting the soft
        // window prematurely.
        final MetadataCache cache = new MetadataCache(
            Duration.ofSeconds(1), Duration.ofMinutes(10),
            10_000, null, "test", clock
        );
        // Seed.
        final byte[] sentBytes = V1.clone();
        cache.load(KEY, req ->
            CompletableFuture.completedFuture(
                MetadataFetchResult.modified(sentBytes, "\"v1\"", "lm-1")
            )
        ).join();
        // Sanity: first serve.
        final byte[] firstServe = cache.load(
            KEY, req -> CompletableFuture.completedFuture(MetadataFetchResult.notFound())
        ).join().get().asBytes();
        // Move past the soft TTL into the stale window. Hard-TTL fall-through
        // would block on the loader; the stale window does the background
        // refresh — but here we want a deterministic synchronous unmodified
        // result, so push past hard TTL.
        clock.advance(Duration.ofHours(1));
        final AtomicInteger calls = new AtomicInteger();
        final byte[] secondServe = cache.load(KEY, req -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(MetadataFetchResult.unmodified());
        }).join().get().asBytes();
        MatcherAssert.assertThat(
            "Hard-TTL fall-through invokes the loader once",
            calls.get(), new IsEqual<>(1)
        );
        MatcherAssert.assertThat(
            "304 response preserves the cached bytes byte-for-byte",
            Arrays.equals(firstServe, secondServe), new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "304 response preserves the original bytes",
            Arrays.equals(secondServe, V1), new IsEqual<>(true)
        );
        // Advance time again — would have fired another refresh if
        // lastVerified hadn't bumped. Within softTtl of the 304, no
        // upstream call should be made.
        clock.advance(Duration.ofMillis(100));
        cache.load(KEY, req -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(MetadataFetchResult.notFound());
        }).join();
        MatcherAssert.assertThat(
            "lastVerified bump puts the entry back into the fresh window — no extra loader calls",
            calls.get(), new IsEqual<>(1)
        );
    }

    @Test
    void modifiedResponseReplacesCachedBytes() {
        final MutableClock clock = MutableClock.at(Instant.parse("2025-01-01T00:00:00Z"));
        final MetadataCache cache = new MetadataCache(
            Duration.ofSeconds(1), Duration.ofMinutes(10),
            10_000, null, "test", clock
        );
        cache.load(KEY, req ->
            CompletableFuture.completedFuture(
                MetadataFetchResult.modified(V1, "\"v1\"", "lm-1")
            )
        ).join();
        // Move past hard TTL so the loader is called synchronously.
        clock.advance(Duration.ofMinutes(20));
        final AtomicReference<ConditionalRequest> capturedRefresh = new AtomicReference<>();
        final byte[] served = cache.load(KEY, req -> {
            capturedRefresh.set(req);
            return CompletableFuture.completedFuture(
                MetadataFetchResult.modified(V2, "\"v2\"", "lm-2")
            );
        }).join().get().asBytes();
        MatcherAssert.assertThat(
            "200 with new bytes replaces the cache",
            Arrays.equals(served, V2), new IsEqual<>(true)
        );
        // The loader saw the previous ETag — confirming a conditional GET
        // would have been sent on the wire.
        MatcherAssert.assertThat(
            "Refresh observed the prior ETag",
            capturedRefresh.get().etag().orElse(null), new IsEqual<>("\"v1\"")
        );
        // Next call should serve the new bytes from cache directly.
        clock.advance(Duration.ofMillis(100));
        final byte[] secondServe = cache.load(KEY, req ->
            CompletableFuture.completedFuture(MetadataFetchResult.notFound())
        ).join().get().asBytes();
        MatcherAssert.assertThat(
            "Subsequent serve returns V2 from cache",
            Arrays.equals(secondServe, V2), new IsEqual<>(true)
        );
    }

    @Test
    void notFoundClearsCache() {
        final MutableClock clock = MutableClock.at(Instant.parse("2025-01-01T00:00:00Z"));
        final MetadataCache cache = new MetadataCache(
            Duration.ofSeconds(1), Duration.ofMinutes(10),
            10_000, null, "test", clock
        );
        cache.load(KEY, req ->
            CompletableFuture.completedFuture(
                MetadataFetchResult.modified(V1, "\"v1\"", "lm-1")
            )
        ).join();
        MatcherAssert.assertThat(
            "Cache populated after first miss",
            cache.size(), new IsEqual<>(1L)
        );
        // Move past hard TTL so the loader is called synchronously.
        clock.advance(Duration.ofMinutes(20));
        final Optional<Content> result = cache.load(KEY, req ->
            CompletableFuture.completedFuture(MetadataFetchResult.notFound())
        ).join();
        MatcherAssert.assertThat(
            "404 surfaces as empty Optional",
            result.isEmpty(), new IsEqual<>(true)
        );
        // After cleanup the L1 reflects the invalidation.
        cache.cleanup();
        MatcherAssert.assertThat(
            "404 cleared the cache entry",
            cache.size(), new IsEqual<>(0L)
        );
    }

    @Test
    void cachedMetadataDefensivelyCopiesBytesAndCarriesValidators() {
        final MutableClock clock = MutableClock.at(Instant.parse("2025-01-01T00:00:00Z"));
        final MetadataCache cache = new MetadataCache(
            Duration.ofSeconds(1), Duration.ofMinutes(10),
            10_000, null, "test", clock
        );
        final byte[] mutable = "<m/>".getBytes(StandardCharsets.UTF_8);
        cache.load(KEY, req ->
            CompletableFuture.completedFuture(
                MetadataFetchResult.modified(mutable, "\"m\"", "lm-m")
            )
        ).join();
        Arrays.fill(mutable, (byte) 'X');
        clock.advance(Duration.ofMillis(100));
        final byte[] served = cache.load(KEY, req ->
            CompletableFuture.completedFuture(MetadataFetchResult.unmodified())
        ).join().get().asBytes();
        MatcherAssert.assertThat(
            "Mutating the caller's byte[] after modified() must not poison the cache",
            new String(served, StandardCharsets.UTF_8), new IsEqual<>("<m/>")
        );
        MatcherAssert.assertThat(
            "Cache must not return null bytes",
            served, new IsNot<>(new IsNull<>())
        );
    }

    /**
     * Spin until {@code calls} reaches {@code target} or the deadline
     * expires. Used to observe single-flighted background refreshes.
     */
    private static void waitForCalls(
        final AtomicInteger calls, final int target, final Duration deadline
    ) {
        final long end = System.currentTimeMillis() + deadline.toMillis();
        while (System.currentTimeMillis() < end && calls.get() < target) {
            try {
                Thread.sleep(5);
            } catch (final InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * Mutable clock for time-travel tests. Wraps an {@link Instant} that
     * callers can advance.
     */
    private static final class MutableClock extends Clock {

        private final AtomicReference<Instant> now;

        private MutableClock(final Instant initial) {
            this.now = new AtomicReference<>(initial);
        }

        static MutableClock at(final Instant initial) {
            return new MutableClock(initial);
        }

        void advance(final Duration delta) {
            this.now.updateAndGet(cur -> cur.plus(delta));
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(final java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return this.now.get();
        }
    }
}
