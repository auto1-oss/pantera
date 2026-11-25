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
package com.auto1.pantera.cooldown.metadata;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * The L2 side of a by-package invalidation of {@link FilteredMetadataCache}
 * against an in-memory {@link EnvelopeL2}: it must cost the envelopes of
 * that package, never a walk over the keyspace. Until 2.2.13 every metadata
 * refresh ran {@code SCAN MATCH metadata:*:<package>} over all keys of the
 * Valkey; the command timed out as the keyspace grew and the stale envelope
 * stayed. Runs without a Valkey; {@code FilteredMetadataCacheL2SweepTest}
 * covers the same behaviour on a real one.
 *
 * @since 2.2.14
 */
final class FilteredMetadataCacheIndexedInvalidationTest {

    @Test
    void refreshInvalidationDropsTheEnvelopesWithoutWalkingTheKeyspace() throws Exception {
        final FakeL2 shared = new FakeL2();
        final FilteredMetadataCache writer = cache(shared);
        load(writer, "npm-proxy", "npm_proxy", "full", "openai");
        load(writer, "npm-proxy", "npm_proxy", "abbreviated", "openai");
        load(writer, "npm-group", "npm_group", "full", "openai");
        load(writer, "npm-proxy", "npm_proxy", "full", "axios");
        // A cold instance: its L1 never held any of them, as on the node
        // whose background refresh fires the invalidation.
        final FilteredMetadataCache cold = cache(shared);
        final int[] counts = cold.invalidatePackageAwaiting("openai").get(5, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "all three envelopes of the package are deleted from L2",
            counts[1], new IsEqual<>(3)
        );
        MatcherAssert.assertThat(
            "only the other package's envelope is left",
            shared.values.keySet(),
            new IsEqual<>(Set.of(FilteredMetadataCache.cacheKey(
                "npm-proxy", "npm_proxy", "full", "axios"
            )))
        );
        MatcherAssert.assertThat(
            "the invalidation is one index operation",
            shared.drops.get(), new IsEqual<>(1)
        );
        MatcherAssert.assertThat(
            "the keyspace is never walked",
            shared.walks.get(), new IsEqual<>(0)
        );
    }

    @Test
    void fireAndForgetInvalidationUsesTheIndexToo() throws Exception {
        final FakeL2 shared = new FakeL2();
        load(cache(shared), "maven-proxy", "central", "default", "com.a.b");
        cache(shared).invalidateByPackageName("com.a.b");
        MatcherAssert.assertThat(
            "the envelope is gone",
            shared.values.isEmpty(), new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "the keyspace is never walked",
            shared.walks.get(), new IsEqual<>(0)
        );
    }

    @Test
    void droppedKeysArePublishedToPeers() throws Exception {
        final FakeL2 shared = new FakeL2();
        load(cache(shared), "npm-proxy", "npm_proxy", "full", "openai");
        final FilteredMetadataCache cold = cache(shared);
        final List<String> published = new CopyOnWriteArrayList<>();
        cold.setInvalidationPublisher(published::add);
        cold.invalidatePackageAwaiting("openai").get(5, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            published,
            new IsEqual<>(List.of("metadata:npm-proxy:npm_proxy:full.v2:openai"))
        );
    }

    @Test
    void repositoryScopedInvalidationLeavesOtherRepositories() throws Exception {
        final FakeL2 shared = new FakeL2();
        final FilteredMetadataCache writer = cache(shared);
        load(writer, "npm-proxy", "repo_a", "full", "openai");
        load(writer, "npm-proxy", "repo_a", "abbreviated", "openai");
        load(writer, "npm-proxy", "repo_b", "full", "openai");
        cache(shared).invalidate("npm-proxy", "repo_a", "openai");
        MatcherAssert.assertThat(
            "only the other repository's envelope is left",
            shared.values.keySet(),
            new IsEqual<>(Set.of(FilteredMetadataCache.cacheKey(
                "npm-proxy", "repo_b", "full", "openai"
            )))
        );
        MatcherAssert.assertThat(
            "the keyspace is never walked",
            shared.walks.get(), new IsEqual<>(0)
        );
    }

    @Test
    void envelopeOfThePreviousKeyGenerationIsNeverServed() throws Exception {
        final FakeL2 shared = new FakeL2();
        // What a 2.2.13 node wrote: no generation marker, no index entry.
        shared.values.put(
            "metadata:npm-proxy:npm_proxy:full:openai",
            "stale".getBytes(StandardCharsets.UTF_8)
        );
        final AtomicInteger loads = new AtomicInteger();
        final byte[] served = cache(shared).getEntry(
            "npm-proxy", "npm_proxy", "full", "openai",
            () -> {
                loads.incrementAndGet();
                return CompletableFuture.completedFuture(
                    FilteredMetadataCache.CacheEntry.noBlockedVersions(
                        "fresh".getBytes(StandardCharsets.UTF_8), Duration.ofMinutes(5)
                    )
                );
            }
        ).get(5, TimeUnit.SECONDS).data();
        MatcherAssert.assertThat(
            "an envelope no index lists cannot be invalidated, so it must not be read",
            new String(served, StandardCharsets.UTF_8), new IsEqual<>("fresh")
        );
        MatcherAssert.assertThat(
            "the envelope was computed",
            loads.get(), new IsEqual<>(1)
        );
    }

    @Test
    void invalidationDeletesPreviousGenerationKeysWhileAnUpgradeCanBeRunning()
        throws Exception {
        final FakeL2 shared = new FakeL2();
        final String legacy = "metadata:npm-proxy:npm_proxy:full:openai";
        shared.values.put(legacy, "stale".getBytes(StandardCharsets.UTF_8));
        final FilteredMetadataCache cache = cache(shared);
        final AtomicLong now = new AtomicLong();
        cache.nanoClock(now::get);
        final List<String> published = new CopyOnWriteArrayList<>();
        cache.setInvalidationPublisher(published::add);
        load(cache, "npm-proxy", "npm_proxy", "full", "openai");
        cache.invalidatePackageAwaiting("openai").get(5, TimeUnit.SECONDS);
        MatcherAssert.assertThat(
            "the envelope a 2.2.13 node may still serve is deleted",
            shared.values.containsKey(legacy), new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "its key is published, so that node drops its L1 copy",
            published.contains(legacy), new IsEqual<>(true)
        );
        now.addAndGet(Duration.ofMinutes(11).toNanos());
        MatcherAssert.assertThat(
            "two L2 TTLs later no such envelope can exist and the cleanup stops",
            cache.legacyKeys("openai", ""), new IsEqual<>(List.of())
        );
    }

    @Test
    void keyPublishedByANodeOfThePreviousGenerationDropsTheLocalEnvelope() throws Exception {
        final FilteredMetadataCache cache = cache(null);
        final AtomicInteger loads = new AtomicInteger();
        final java.util.function.Supplier<CompletableFuture<FilteredMetadataCache.CacheEntry>>
            loader = () -> {
                loads.incrementAndGet();
                return CompletableFuture.completedFuture(
                    FilteredMetadataCache.CacheEntry.noBlockedVersions(
                        new byte[] {1}, Duration.ofMinutes(5)
                    )
                );
            };
        cache.getEntry("npm-proxy", "npm_proxy", "full", "openai", loader)
            .get(5, TimeUnit.SECONDS);
        cache.invalidate("metadata:npm-proxy:npm_proxy:full:openai");
        cache.getEntry("npm-proxy", "npm_proxy", "full", "openai", loader)
            .get(5, TimeUnit.SECONDS);
        MatcherAssert.assertThat(loads.get(), new IsEqual<>(2));
    }

    @Test
    void probeReadsThePackageIndex() throws Exception {
        final FakeL2 shared = new FakeL2();
        load(cache(shared), "npm-proxy", "npm_proxy", "full", "openai");
        final FilteredMetadataCache cold = cache(shared);
        MatcherAssert.assertThat(
            "the repository's L2 envelope is seen",
            cold.probe("npm_proxy", "openai").get(5, TimeUnit.SECONDS).l2Present(),
            new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "another repository has none",
            cold.probe("npm_group", "openai").get(5, TimeUnit.SECONDS).l2Present(),
            new IsEqual<>(false)
        );
        MatcherAssert.assertThat(
            "the keyspace is never walked",
            shared.walks.get(), new IsEqual<>(0)
        );
    }

    @Test
    void repositoryWideInvalidationIsTheOnlyKeyspaceWalk() {
        final FakeL2 shared = new FakeL2();
        cache(shared).invalidateAll("npm-proxy", "npm_proxy");
        MatcherAssert.assertThat(shared.walks.get(), new IsEqual<>(1));
    }

    /**
     * Cache over the given L2.
     *
     * @param shared L2 tier, may be null
     * @return Cache with a 5 minute L1 and L2 TTL
     */
    private static FilteredMetadataCache cache(final EnvelopeL2 shared) {
        return new FilteredMetadataCache(
            shared, 100, 1024L * 1024L, Duration.ofMinutes(5), Duration.ofMinutes(5)
        );
    }

    /**
     * Compute an envelope through the cache.
     *
     * @param cache Cache
     * @param type Repository type
     * @param repo Repository name
     * @param variant Variant
     * @param pkg Package name
     * @throws Exception On failure
     */
    private static void load(
        final FilteredMetadataCache cache, final String type, final String repo,
        final String variant, final String pkg
    ) throws Exception {
        cache.getEntry(
            type, repo, variant, pkg,
            () -> CompletableFuture.completedFuture(
                FilteredMetadataCache.CacheEntry.noBlockedVersions(
                    (repo + pkg).getBytes(StandardCharsets.UTF_8), Duration.ofMinutes(5)
                )
            )
        ).get(5, TimeUnit.SECONDS);
    }

    /**
     * In-memory L2 that counts index drops and keyspace walks.
     */
    private static final class FakeL2 implements EnvelopeL2 {

        /**
         * Envelopes by key.
         */
        private final Map<String, byte[]> values = new ConcurrentHashMap<>();

        /**
         * Index members by index key.
         */
        private final Map<String, Set<String>> indexes = new ConcurrentHashMap<>();

        /**
         * Calls of {@link #drop}.
         */
        private final AtomicInteger drops = new AtomicInteger();

        /**
         * Calls of {@link #deleteMatching}.
         */
        private final AtomicInteger walks = new AtomicInteger();

        @Override
        public CompletableFuture<byte[]> get(final String key) {
            return CompletableFuture.completedFuture(this.values.get(key));
        }

        @Override
        public CompletableFuture<Void> put(
            final String index, final String key, final long ttlSeconds, final byte[] value
        ) {
            this.indexes.computeIfAbsent(index, ignored -> ConcurrentHashMap.newKeySet())
                .add(key);
            this.values.put(key, value);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Dropped> drop(final String index, final String prefix) {
            this.drops.incrementAndGet();
            final List<String> keys = new ArrayList<>();
            int deleted = 0;
            final Set<String> members = this.indexes.getOrDefault(index, Set.of());
            for (final String key : List.copyOf(members)) {
                if (key.startsWith(prefix)) {
                    keys.add(key);
                    members.remove(key);
                    if (this.values.remove(key) != null) {
                        deleted = deleted + 1;
                    }
                }
            }
            return CompletableFuture.completedFuture(new Dropped(keys, deleted));
        }

        @Override
        public CompletableFuture<List<String>> members(final String index) {
            return CompletableFuture.completedFuture(
                List.copyOf(this.indexes.getOrDefault(index, Set.of()))
            );
        }

        @Override
        public CompletableFuture<Long> pttl(final String key) {
            return CompletableFuture.completedFuture(
                this.values.containsKey(key) ? 60_000L : -2L
            );
        }

        @Override
        public CompletableFuture<Long> delete(final List<String> keys) {
            long count = 0L;
            for (final String key : keys) {
                if (this.values.remove(key) != null) {
                    count = count + 1L;
                }
            }
            return CompletableFuture.completedFuture(count);
        }

        @Override
        public CompletableFuture<Long> deleteMatching(final String pattern) {
            this.walks.incrementAndGet();
            return CompletableFuture.completedFuture(0L);
        }
    }
}
