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
package com.auto1.pantera.asto.blob;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link CachedBlobStorage}'s WS1.6 cache-tier metrics hooks
 * (spec {@code WS1-storage-for-scale.md} &sect;3.G): disk hit/miss, eviction
 * bytes, and cross-node invalidation apply/ignore outcomes -- proved against
 * a recording {@link CachedBlobStorageMetrics} fake installed via {@link
 * CachedBlobStorageMetricsRegistry}, never a real {@code MicrometerMetrics}
 * (that bridge, {@code CachedBlobStorageMetricsBinder}, is a thin,
 * un-branching forward covered by this seam contract instead).
 */
@Timeout(15)
final class CachedBlobStorageMetricsHooksTest {

    private static final Duration FRESHNESS_TTL = Duration.ofMinutes(5);
    private static final Duration NEGATIVE_TTL = Duration.ofSeconds(30);

    @AfterEach
    void resetRegistry() {
        // Static registry -- must not leak a fake into other test classes
        // sharing this JVM.
        CachedBlobStorageMetricsRegistry.uninstall();
    }

    @Test
    void constructingStorageBindsMetricsExactlyOnceWithCacheIdEqualToDiskRoot(@TempDir final Path tmp) {
        final RecordingMetrics metrics = new RecordingMetrics();
        CachedBlobStorageMetricsRegistry.install(metrics);
        final CachedBlobStorage storage = CachedBlobStorageMetricsHooksTest.writeThroughStorage(new RecordingBlobStore(), tmp);
        MatcherAssert.assertThat("bind() must run exactly once, from the constructor", metrics.bound.size(), new IsEqual<>(1));
        MatcherAssert.assertThat(metrics.bound.get(0), new IsEqual<>(storage));
        MatcherAssert.assertThat("cacheId must be this instance's disk-cache root", storage.cacheId(), new IsEqual<>(tmp.toString()));
    }

    @Test
    void noMetricsInstalledIsANoOp(@TempDir final Path tmp) {
        // Default (NOOP) -- must never throw, exactly as the pre-WS1.6 behaviour.
        final RecordingBlobStore fake = new RecordingBlobStore();
        fake.seed("k.jar", "v".getBytes(StandardCharsets.UTF_8));
        final CachedBlobStorage storage = CachedBlobStorageMetricsHooksTest.writeThroughStorage(fake, tmp);
        final byte[] read = storage.value(new Key.From("k.jar")).join().asBytesFuture().join();
        MatcherAssert.assertThat(read, new IsEqual<>("v".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void coldValueRecordsDiskMissThenWarmValueRecordsDiskHit(@TempDir final Path tmp) {
        final RecordingMetrics metrics = new RecordingMetrics();
        CachedBlobStorageMetricsRegistry.install(metrics);
        final RecordingBlobStore fake = new RecordingBlobStore();
        fake.seed("k.jar", "v".getBytes(StandardCharsets.UTF_8));
        final CachedBlobStorage storage = CachedBlobStorageMetricsHooksTest.writeThroughStorage(fake, tmp);
        final Key key = new Key.From("k.jar");

        storage.value(key).join();
        MatcherAssert.assertThat("cold fill must record exactly one disk miss", metrics.diskMisses.get(), new IsEqual<>(1));
        MatcherAssert.assertThat(metrics.diskHits.get(), new IsEqual<>(0));

        storage.value(key).join();
        MatcherAssert.assertThat("a disk hit must record exactly one disk hit", metrics.diskHits.get(), new IsEqual<>(1));
        MatcherAssert.assertThat("a disk hit must not record a second miss", metrics.diskMisses.get(), new IsEqual<>(1));
    }

    @Test
    void watermarkEvictionRecordsBytesFreedForEachEvictedEntry(@TempDir final Path tmp) {
        final RecordingMetrics metrics = new RecordingMetrics();
        CachedBlobStorageMetricsRegistry.install(metrics);
        final CachedBlobStorage.EvictionConfig eviction =
            new CachedBlobStorage.EvictionConfig(1000L, 80, 40, CachedBlobStorage.EvictionPolicy.LFU);
        final RecordingBlobStore fake = new RecordingBlobStore();
        final CachedBlobStorage storage = new CachedBlobStorage(
            fake, tmp, FRESHNESS_TTL, NEGATIVE_TTL, true, CachedBlobStorage.WriteBackConfig.defaults(), eviction
        );
        final byte[] payload = new byte[150];
        for (int i = 1; i <= 5; i++) {
            storage.save(new Key.From("k" + i + ".jar"), new Content.From(payload)).join();
        }
        for (int i = 1; i <= 5; i++) {
            for (int hit = 0; hit < 6 - i; hit++) {
                storage.value(new Key.From("k" + i + ".jar")).join();
            }
        }
        // Crosses the high watermark; evicts k5, k4, k3 (150 bytes each) down
        // toward the low watermark -- same scenario CachedBlobStorageTest's
        // watermarkEviction... test proves the SELECTION for.
        storage.save(new Key.From("k6.jar"), new Content.From(payload)).join();

        MatcherAssert.assertThat("three 150-byte entries evicted", metrics.evictionBytes.size(), new IsEqual<>(3));
        final long total = metrics.evictionBytes.stream().mapToLong(Long::longValue).sum();
        MatcherAssert.assertThat("total bytes freed across the three evictions", total, new IsEqual<>(450L));
    }

    @Test
    void crossNodeApplyRecordsAppliedOutcome(@TempDir final Path tmp) {
        final RecordingMetrics metrics = new RecordingMetrics();
        CachedBlobStorageMetricsRegistry.install(metrics);
        final RecordingStorageInvalidationBus bus = new RecordingStorageInvalidationBus();
        final RecordingStorageInvalidationBus.Node peer = bus.newNode();
        final RecordingBlobStore blobB = new RecordingBlobStore();
        final Key key = new Key.From("shared.jar");
        blobB.seed(key.string(), "stale".getBytes(StandardCharsets.UTF_8));
        final CachedBlobStorage storageB = new CachedBlobStorage(
            blobB, tmp, FRESHNESS_TTL, NEGATIVE_TTL, true,
            CachedBlobStorage.WriteBackConfig.defaults(), CachedBlobStorage.EvictionConfig.defaults(), bus.newNode()
        );
        storageB.value(key).join();

        final String freshToken =
            new StorageInvalidationToken(tmp.toString(), "fresh-digest", System.currentTimeMillis() + 1_000_000L).encode();
        peer.publish(key, freshToken);

        MatcherAssert.assertThat("a stale entry actually dropped must record one applied", metrics.invalidationApplied.get(), new IsEqual<>(1));
        MatcherAssert.assertThat(metrics.invalidationIgnored, new IsEqual<>(List.of()));
    }

    @Test
    void crossNodeIgnoreRecordsSupersededOutcome(@TempDir final Path tmp) {
        final RecordingMetrics metrics = new RecordingMetrics();
        CachedBlobStorageMetricsRegistry.install(metrics);
        final RecordingStorageInvalidationBus bus = new RecordingStorageInvalidationBus();
        final RecordingStorageInvalidationBus.Node peer = bus.newNode();
        final CachedBlobStorage storage = new CachedBlobStorage(
            new RecordingBlobStore(), tmp, FRESHNESS_TTL, NEGATIVE_TTL, true,
            CachedBlobStorage.WriteBackConfig.defaults(), CachedBlobStorage.EvictionConfig.defaults(), bus.newNode()
        );
        final Key key = new Key.From("k.jar");
        storage.save(key, new Content.From("current".getBytes(StandardCharsets.UTF_8))).join();

        final String staleToken = new StorageInvalidationToken(tmp.toString(), "old-digest", 1L).encode();
        peer.publish(key, staleToken);

        MatcherAssert.assertThat(metrics.invalidationApplied.get(), new IsEqual<>(0));
        MatcherAssert.assertThat(metrics.invalidationIgnored, new IsEqual<>(List.of("superseded_by_local_write")));
    }

    @Test
    void crossNodeIgnoreRecordsNotCachedOutcomeForAnUnknownKey(@TempDir final Path tmp) {
        final RecordingMetrics metrics = new RecordingMetrics();
        CachedBlobStorageMetricsRegistry.install(metrics);
        final RecordingStorageInvalidationBus bus = new RecordingStorageInvalidationBus();
        final RecordingStorageInvalidationBus.Node peer = bus.newNode();
        // Constructing this storage is enough to register the listener --
        // the storage never learns about "never-seen.jar" at all.
        new CachedBlobStorage(
            new RecordingBlobStore(), tmp, FRESHNESS_TTL, NEGATIVE_TTL, true,
            CachedBlobStorage.WriteBackConfig.defaults(), CachedBlobStorage.EvictionConfig.defaults(), bus.newNode()
        );
        final String token = new StorageInvalidationToken(tmp.toString(), "d", System.currentTimeMillis()).encode();

        peer.publish(new Key.From("never-seen.jar"), token);

        MatcherAssert.assertThat(metrics.invalidationApplied.get(), new IsEqual<>(0));
        MatcherAssert.assertThat(metrics.invalidationIgnored, new IsEqual<>(List.of("not_cached")));
    }

    private static CachedBlobStorage writeThroughStorage(final RecordingBlobStore fake, final Path tmp) {
        return new CachedBlobStorage(fake, tmp, FRESHNESS_TTL, NEGATIVE_TTL);
    }

    /** Recording {@link CachedBlobStorageMetrics} test fake. */
    private static final class RecordingMetrics implements CachedBlobStorageMetrics {
        private final List<CachedBlobStorage> bound = new CopyOnWriteArrayList<>();
        private final AtomicInteger diskHits = new AtomicInteger();
        private final AtomicInteger diskMisses = new AtomicInteger();
        private final List<Long> evictionBytes = new CopyOnWriteArrayList<>();
        private final AtomicInteger invalidationApplied = new AtomicInteger();
        private final List<String> invalidationIgnored = new CopyOnWriteArrayList<>();

        @Override
        public void bind(final CachedBlobStorage storage) {
            this.bound.add(storage);
        }

        @Override
        public void recordDiskHit() {
            this.diskHits.incrementAndGet();
        }

        @Override
        public void recordDiskMiss() {
            this.diskMisses.incrementAndGet();
        }

        @Override
        public void recordEvictionBytes(final long bytes) {
            this.evictionBytes.add(bytes);
        }

        @Override
        public void recordInvalidationApplied() {
            this.invalidationApplied.incrementAndGet();
        }

        @Override
        public void recordInvalidationIgnored(final String reason) {
            this.invalidationIgnored.add(reason);
        }
    }
}
