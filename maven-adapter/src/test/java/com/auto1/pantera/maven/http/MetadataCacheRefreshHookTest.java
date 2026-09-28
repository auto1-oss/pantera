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

import com.auto1.pantera.asto.Key;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Tests for the {@code onModified} refreshed-content hook added to
 * {@link MetadataCache#load(Key, MetadataCache.ConditionalRemote, Runnable)}
 * in 2.2.7 — the maven half of the stale-envelope fix: the hook is what
 * lets {@code CachedProxySlice} drop the cooldown filtered-metadata
 * envelope when upstream {@code maven-metadata.xml} actually changes.
 *
 * <p>Contract proven here: the hook fires exactly when a 200 replaces the
 * cached bytes (cold miss and hard-TTL refetch alike) and does NOT fire on
 * a 304 validator match. A throwing hook must not break the load.</p>
 *
 * @since 2.2.7
 */
final class MetadataCacheRefreshHookTest {

    @Test
    @Timeout(10)
    void firesOnContentReplacementAndNotOn304() throws Exception {
        final MutableClock clock = MutableClock.at(Instant.parse("2025-01-01T00:00:00Z"));
        final MetadataCache cache = new MetadataCache(
            Duration.ofSeconds(30), Duration.ofMinutes(2), 100, null, "hook-test", clock
        );
        final Key key = new Key.From("com/example/lib/maven-metadata.xml");
        final AtomicInteger hook = new AtomicInteger();
        final AtomicReference<MetadataCache.MetadataFetchResult> scripted =
            new AtomicReference<>(MetadataCache.MetadataFetchResult.modified(
                "<metadata v1/>".getBytes(StandardCharsets.UTF_8), "\"e1\"", null
            ));
        final MetadataCache.ConditionalRemote remote =
            request -> CompletableFuture.completedFuture(scripted.get());

        // Cold miss → 200 commits bytes → hook fires.
        cache.load(key, remote, hook::incrementAndGet).get(5, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "a cold-miss 200 replaces content and must fire the hook",
            hook.get(), new IsEqual<>(1)
        );

        // Fresh window → pure cache hit, no upstream, no hook.
        cache.load(key, remote, hook::incrementAndGet).get(5, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "a fresh-window cache hit must not fire the hook",
            hook.get(), new IsEqual<>(1)
        );

        // Past hard TTL with a 304 → lastVerified bump only, no hook.
        scripted.set(MetadataCache.MetadataFetchResult.unmodified());
        clock.advance(Duration.ofMinutes(3));
        cache.load(key, remote, hook::incrementAndGet).get(5, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "a 304 validator match must NOT fire the hook — bytes did not change",
            hook.get(), new IsEqual<>(1)
        );

        // Past hard TTL again with changed content → hook fires again.
        scripted.set(MetadataCache.MetadataFetchResult.modified(
            "<metadata v2/>".getBytes(StandardCharsets.UTF_8), "\"e2\"", null
        ));
        clock.advance(Duration.ofMinutes(3));
        final Optional<com.auto1.pantera.asto.Content> served =
            cache.load(key, remote, hook::incrementAndGet).get(5, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "a hard-TTL refetch that replaces content must fire the hook",
            hook.get(), new IsEqual<>(2)
        );
        MatcherAssert.assertThat(
            "the refetched bytes must be the ones served",
            new String(
                served.get().asBytesFuture().get(5, TimeUnit.SECONDS),
                StandardCharsets.UTF_8
            ),
            new IsEqual<>("<metadata v2/>")
        );
    }

    @Test
    @Timeout(10)
    void throwingHookDoesNotBreakTheLoad() throws Exception {
        final MutableClock clock = MutableClock.at(Instant.parse("2025-01-01T00:00:00Z"));
        final MetadataCache cache = new MetadataCache(
            Duration.ofSeconds(30), Duration.ofMinutes(2), 100, null, "hook-test", clock
        );
        final Key key = new Key.From("com/example/other/maven-metadata.xml");
        final MetadataCache.ConditionalRemote remote =
            request -> CompletableFuture.completedFuture(
                MetadataCache.MetadataFetchResult.modified(
                    "<metadata/>".getBytes(StandardCharsets.UTF_8), null, null
                )
            );
        final Optional<com.auto1.pantera.asto.Content> served = cache.load(
            key, remote,
            () -> {
                throw new IllegalStateException("hook exploded");
            }
        ).get(5, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "a throwing hook must be swallowed — the load still serves the bytes",
            served.isPresent(), new IsEqual<>(true)
        );
    }

    /**
     * Mutable clock for time-travel — mirrors {@code MetadataCacheSwrTest}.
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
