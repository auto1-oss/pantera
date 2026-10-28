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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.IsNot;
import org.hamcrest.core.IsNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * WS1.5 cross-node coherence tests (spec {@code WS1-storage-for-scale.md}
 * &sect;3.E, acceptance #6): a write on one node publishes an invalidation
 * that drops a peer's stale disk+index entry, a node never acts on its OWN
 * publish (self-filter), a message superseded by a newer local write is
 * ignored, a {@code PENDING_WRITE} entry is never evicted by a peer message
 * regardless of what it claims, and a message for a different repository's
 * namespace sharing the same process-wide bus is ignored. All proved with
 * invocation counts, never wall-clock (CLAUDE.md testing doctrine).
 */
@Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
final class CachedBlobStorageInvalidationTest {

    private static final Duration FRESHNESS_TTL = Duration.ofMinutes(5);
    private static final Duration NEGATIVE_TTL = Duration.ofSeconds(30);

    @Test
    void busNeverDeliversANodesOwnPublishBackToItself() {
        final RecordingStorageInvalidationBus bus = new RecordingStorageInvalidationBus();
        final RecordingStorageInvalidationBus.Node nodeA = bus.newNode();
        final RecordingStorageInvalidationBus.Node nodeB = bus.newNode();
        final AtomicInteger selfDeliveries = new AtomicInteger();
        final AtomicInteger peerDeliveries = new AtomicInteger();
        nodeA.onInvalidate((key, token) -> selfDeliveries.incrementAndGet());
        nodeB.onInvalidate((key, token) -> peerDeliveries.incrementAndGet());

        nodeA.publish(new Key.From("k"), "some-token");

        MatcherAssert.assertThat(
            "a node must never receive its own publish (self-message filter)",
            selfDeliveries.get(), new IsEqual<>(0)
        );
        MatcherAssert.assertThat("a peer node must receive the publish", peerDeliveries.get(), new IsEqual<>(1));
    }

    @Test
    void saveThenDeleteOnOneInstancePublishInvalidationsObservableByAPeer(@TempDir final Path tmp) {
        final RecordingStorageInvalidationBus bus = new RecordingStorageInvalidationBus();
        final RecordingStorageInvalidationBus.Node observer = bus.newNode();
        final List<Key> observedKeys = new ArrayList<>();
        final List<String> observedTokens = new ArrayList<>();
        observer.onInvalidate((key, token) -> {
            observedKeys.add(key);
            observedTokens.add(token);
        });
        final CachedBlobStorage storage =
            CachedBlobStorageInvalidationTest.writeThroughStorage(new RecordingBlobStore(), tmp, bus.newNode());
        final Key key = new Key.From("observed.jar");

        storage.save(key, new Content.From("bytes".getBytes(StandardCharsets.UTF_8))).join();
        MatcherAssert.assertThat("save() must publish exactly one commit invalidation", observedKeys.size(), new IsEqual<>(1));
        MatcherAssert.assertThat(observedKeys.get(0), new IsEqual<>(key));
        final StorageInvalidationToken commitToken = StorageInvalidationToken.decode(observedTokens.get(0)).orElseThrow();
        MatcherAssert.assertThat(
            "a commit token's namespace must match this storage's own diskRoot",
            commitToken.namespace(), new IsEqual<>(tmp.toString())
        );
        MatcherAssert.assertThat(
            "a commit token must carry the just-written content's digest, not a delete tombstone's null",
            commitToken.digest(), new IsNot<>(new IsNull<>())
        );

        storage.delete(key).join();
        MatcherAssert.assertThat("delete() must publish exactly one tombstone invalidation", observedKeys.size(), new IsEqual<>(2));
        final StorageInvalidationToken deleteToken = StorageInvalidationToken.decode(observedTokens.get(1)).orElseThrow();
        MatcherAssert.assertThat("a delete tombstone must carry a null digest", deleteToken.digest(), new IsNull<>());
    }

    @Test
    void writeOnOneNodeInvalidatesPeersStaleDiskAndIndexEntry(@TempDir final Path tmp) {
        final RecordingStorageInvalidationBus bus = new RecordingStorageInvalidationBus();
        // "Node A" here is a bare peer on the shared bus (not a second real
        // CachedBlobStorage over the SAME physical directory -- two live
        // FileStorage instances concurrently addressing one filesystem path
        // is a test-harness artifact with no production analogue, since real
        // cluster nodes each have their OWN physical disk; see
        // saveThenDeleteOnOneInstancePublishInvalidationsObservableByAPeer
        // above for proof that a real save()/delete() publishes correctly
        // via the SAME token format used here). It publishes a token
        // identical in shape to what a peer's real commit would encode.
        final RecordingStorageInvalidationBus.Node peerA = bus.newNode();
        final RecordingBlobStore blobB = new RecordingBlobStore();
        final Key key = new Key.From("shared-artifact.jar");
        blobB.seed(key.string(), "stale-on-b".getBytes(StandardCharsets.UTF_8));
        final CachedBlobStorage storageB = CachedBlobStorageInvalidationTest.writeThroughStorage(blobB, tmp, bus.newNode());

        // Node B: cold-fills the key from ITS OWN blob store first, caching
        // the (soon-to-be-stale) local copy acceptance #6 describes.
        storageB.value(key).join();
        MatcherAssert.assertThat("sanity: B's first read is a cold fill", blobB.getCalls(), new IsEqual<>(1));

        // Node A commits a newer version and publishes -- must drop B's
        // now-stale local entry.
        final String commitToken =
            new StorageInvalidationToken(tmp.toString(), "fresh-digest", System.currentTimeMillis() + 1_000_000L).encode();
        peerA.publish(key, commitToken);

        MatcherAssert.assertThat(
            "B's stale disk+index entry must be dropped by A's invalidation",
            storageB.isCachedOnDisk(key), new IsEqual<>(false)
        );
        final byte[] resolved = storageB.value(key).join().asBytesFuture().join();
        MatcherAssert.assertThat(
            "B's next value() must re-resolve via a fresh cold fill from ITS OWN blob store",
            blobB.getCalls(), new IsEqual<>(2)
        );
        MatcherAssert.assertThat(
            "the re-fetched bytes must come from B's own blob store",
            resolved, new IsEqual<>("stale-on-b".getBytes(StandardCharsets.UTF_8))
        );
    }

    @Test
    void supersededOlderInvalidationIsIgnoredWhenLocalEntryIsNewer(@TempDir final Path tmp) {
        final RecordingStorageInvalidationBus bus = new RecordingStorageInvalidationBus();
        final RecordingStorageInvalidationBus.Node peer = bus.newNode();
        final RecordingBlobStore fake = new RecordingBlobStore();
        final CachedBlobStorage storage = CachedBlobStorageInvalidationTest.writeThroughStorage(fake, tmp, bus.newNode());
        final Key key = new Key.From("k.jar");
        storage.save(key, new Content.From("current".getBytes(StandardCharsets.UTF_8))).join();

        // A message claiming a commit at epoch millisecond 1 -- unambiguously
        // BEFORE the local write() just performed (real "now") -- must be
        // recognised as superseded and ignored.
        final String staleToken = new StorageInvalidationToken(tmp.toString(), "old-digest", 1L).encode();
        peer.publish(key, staleToken);

        MatcherAssert.assertThat(
            "a message older than the local entry's own commit time must be ignored",
            storage.isCachedOnDisk(key), new IsEqual<>(true)
        );
        final byte[] stillLocal = storage.value(key).join().asBytesFuture().join();
        MatcherAssert.assertThat(stillLocal, new IsEqual<>("current".getBytes(StandardCharsets.UTF_8)));
        MatcherAssert.assertThat(
            "serving the surviving entry must not re-contact the blob store",
            fake.getCalls(), new IsEqual<>(0)
        );
    }

    @Test
    void invalidationForAPendingWriteKeyIsIgnoredRegardlessOfToken(@TempDir final Path tmp) throws Exception {
        final RecordingStorageInvalidationBus bus = new RecordingStorageInvalidationBus();
        final RecordingStorageInvalidationBus.Node peer = bus.newNode();
        final RecordingBlobStore fake = new RecordingBlobStore();
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch neverReleases = new CountDownLatch(1);
        fake.gatePut(entered, neverReleases);
        final CachedBlobStorage storage = new CachedBlobStorage(
            fake, tmp, FRESHNESS_TTL, NEGATIVE_TTL, false,
            CachedBlobStorage.WriteBackConfig.defaults(), CachedBlobStorage.EvictionConfig.defaults(), bus.newNode()
        );
        final Key key = new Key.From("pending.jar");
        storage.save(key, new Content.From("in-flight".getBytes(StandardCharsets.UTF_8))).join();
        MatcherAssert.assertThat(entered.await(10, TimeUnit.SECONDS), new IsEqual<>(true));
        MatcherAssert.assertThat(storage.isPendingWrite(key), new IsEqual<>(true));

        // A peer publishes an invalidation about this same key claiming a
        // commit time far in the future -- if the PENDING_WRITE guard were
        // missing, the superseded-timestamp check ALONE would NOT save this
        // entry (this proves the guard is doing real, distinct work).
        final String token =
            new StorageInvalidationToken(tmp.toString(), "other-digest", System.currentTimeMillis() + 1_000_000L).encode();
        peer.publish(key, token);

        MatcherAssert.assertThat(
            "a PENDING_WRITE entry must never be dropped by a peer message, however new it claims to be",
            storage.isCachedOnDisk(key), new IsEqual<>(true)
        );
        MatcherAssert.assertThat(storage.isPendingWrite(key), new IsEqual<>(true));
    }

    @Test
    void invalidationForADifferentNamespaceIsIgnored(@TempDir final Path tmp) {
        final RecordingStorageInvalidationBus bus = new RecordingStorageInvalidationBus();
        final RecordingStorageInvalidationBus.Node peer = bus.newNode();
        final RecordingBlobStore fake = new RecordingBlobStore();
        final CachedBlobStorage storage = CachedBlobStorageInvalidationTest.writeThroughStorage(fake, tmp, bus.newNode());
        final Key key = new Key.From("k.jar");
        storage.save(key, new Content.From("mine".getBytes(StandardCharsets.UTF_8))).join();

        // A message for a DIFFERENT repository's namespace (a different
        // diskRoot) sharing the SAME process-wide bus/channel must not
        // affect this instance -- see CachedBlobStorage#onPeerInvalidate's
        // multi-repo namespace-scoping javadoc.
        final String foreignToken =
            new StorageInvalidationToken("/some/other/repo/cache", null, System.currentTimeMillis() + 1_000_000L).encode();
        peer.publish(key, foreignToken);

        MatcherAssert.assertThat(
            "a message for a different repository's namespace must be ignored",
            storage.isCachedOnDisk(key), new IsEqual<>(true)
        );
    }

    @Test
    void malformedInvalidationMessageIsDroppedWithoutAffectingTheLocalEntry(@TempDir final Path tmp) {
        final RecordingStorageInvalidationBus bus = new RecordingStorageInvalidationBus();
        final RecordingStorageInvalidationBus.Node peer = bus.newNode();
        final RecordingBlobStore fake = new RecordingBlobStore();
        final CachedBlobStorage storage = CachedBlobStorageInvalidationTest.writeThroughStorage(fake, tmp, bus.newNode());
        final Key key = new Key.From("k.jar");
        storage.save(key, new Content.From("mine".getBytes(StandardCharsets.UTF_8))).join();

        peer.publish(key, "not-a-well-formed-token");

        MatcherAssert.assertThat(
            "a malformed message must be dropped defensively, never risk an incorrect eviction",
            storage.isCachedOnDisk(key), new IsEqual<>(true)
        );
    }

    private static CachedBlobStorage writeThroughStorage(
        final RecordingBlobStore fake, final Path tmp, final StorageInvalidationBus bus
    ) {
        return new CachedBlobStorage(
            fake, tmp, FRESHNESS_TTL, NEGATIVE_TTL, true,
            CachedBlobStorage.WriteBackConfig.defaults(), CachedBlobStorage.EvictionConfig.defaults(), bus
        );
    }
}
