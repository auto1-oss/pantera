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
import com.auto1.pantera.asto.Meta;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link CachedBlobStorage}: the WS1.1 acceptance criteria --
 * zero blob-store round trips on a hit, single-flighted cold fills, and
 * correct behaviour after a boot rebuild -- proved with a recording {@link
 * BlobStore} fake and invocation counts, never wall-clock timing (CLAUDE.md
 * testing doctrine).
 */
@Timeout(15)
final class CachedBlobStorageTest {

    private static final Duration FRESHNESS_TTL = Duration.ofMinutes(5);
    private static final Duration NEGATIVE_TTL = Duration.ofSeconds(30);

    @Test
    void valueOnDiskHitIssuesZeroBlobStoreCalls(@TempDir final Path tmp) {
        final RecordingBlobStore fake = new RecordingBlobStore();
        fake.seed("a/lib.jar", "hello-world".getBytes(StandardCharsets.UTF_8));
        final CachedBlobStorage storage = CachedBlobStorageTest.storage(fake, tmp);
        final Key key = new Key.From("a", "lib.jar");
        // First value() is a cold fill: exactly one GET.
        final byte[] first = storage.value(key).join().asBytesFuture().join();
        MatcherAssert.assertThat(first, new IsEqual<>("hello-world".getBytes(StandardCharsets.UTF_8)));
        MatcherAssert.assertThat("cold fill issues exactly one GET", fake.getCalls(), new IsEqual<>(1));
        // Second value() must be served entirely from disk: zero further blob-store calls.
        final byte[] second = storage.value(key).join().asBytesFuture().join();
        MatcherAssert.assertThat(second, new IsEqual<>("hello-world".getBytes(StandardCharsets.UTF_8)));
        MatcherAssert.assertThat("a disk hit must not re-contact the blob store", fake.getCalls(), new IsEqual<>(1));
        MatcherAssert.assertThat(fake.headCalls(), new IsEqual<>(0));
        MatcherAssert.assertThat(fake.existsCalls(), new IsEqual<>(0));
    }

    @Test
    void existsAndMetadataOnKnownKeyIssueZeroBlobStoreCalls(@TempDir final Path tmp) {
        final RecordingBlobStore fake = new RecordingBlobStore();
        fake.seed("known.jar", "x".getBytes(StandardCharsets.UTF_8));
        final CachedBlobStorage storage = CachedBlobStorageTest.storage(fake, tmp);
        final Key key = new Key.From("known.jar");
        // Populate the index via a cold value() fetch first.
        storage.value(key).join();
        final int getCallsAfterFill = fake.getCalls();
        MatcherAssert.assertThat(storage.exists(key).join(), new IsEqual<>(true));
        MatcherAssert.assertThat(storage.metadata(key).join().read(Meta.OP_SIZE).get(), new IsEqual<>(1L));
        MatcherAssert.assertThat(
            "exists()/metadata() on an already-known key must not touch the blob store",
            fake.headCalls(), new IsEqual<>(0)
        );
        MatcherAssert.assertThat(fake.getCalls(), new IsEqual<>(getCallsAfterFill));
    }

    @Test
    void existsOnUnknownKeySingleFlightsExactlyOneHead(@TempDir final Path tmp) {
        final RecordingBlobStore fake = new RecordingBlobStore();
        fake.seed("cold-meta.jar", "y".getBytes(StandardCharsets.UTF_8));
        final CachedBlobStorage storage = CachedBlobStorageTest.storage(fake, tmp);
        final Key key = new Key.From("cold-meta.jar");
        MatcherAssert.assertThat(storage.exists(key).join(), new IsEqual<>(true));
        MatcherAssert.assertThat("one HEAD for the first exists() on an unknown key", fake.headCalls(), new IsEqual<>(1));
        MatcherAssert.assertThat(storage.exists(key).join(), new IsEqual<>(true));
        MatcherAssert.assertThat("the index now answers without a second HEAD", fake.headCalls(), new IsEqual<>(1));
    }

    @Test
    void concurrentValueCallsForOneColdKeyIssueExactlyOneGet(@TempDir final Path tmp) throws Exception {
        final RecordingBlobStore fake = new RecordingBlobStore();
        final byte[] data = "concurrent-cold-fill".getBytes(StandardCharsets.UTF_8);
        fake.seed("hot.jar", data);
        final CachedBlobStorage storage = CachedBlobStorageTest.storage(fake, tmp);
        final Key key = new Key.From("hot.jar");
        final int callers = 12;
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        fake.gateGet(entered, release);
        final ExecutorService pool = Executors.newFixedThreadPool(callers);
        try {
            final CountDownLatch allIssued = new CountDownLatch(callers);
            final List<Future<CompletableFuture<Content>>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < callers; i++) {
                futures.add(pool.submit(() -> {
                    final CompletableFuture<Content> f = storage.value(key);
                    allIssued.countDown();
                    return f;
                }));
            }
            // Prove the single fetch has actually started (leader inside get()).
            MatcherAssert.assertThat(entered.await(10, TimeUnit.SECONDS), new IsEqual<>(true));
            // Prove all N callers issued their value() call before letting the fetch finish,
            // so late callers cannot race a second, independent cold fill.
            MatcherAssert.assertThat(allIssued.await(10, TimeUnit.SECONDS), new IsEqual<>(true));
            release.countDown();
            for (final Future<CompletableFuture<Content>> future : futures) {
                final byte[] bytes = future.get(10, TimeUnit.SECONDS).join().asBytesFuture().join();
                MatcherAssert.assertThat(bytes, new IsEqual<>(data));
            }
        } finally {
            pool.shutdown();
        }
        MatcherAssert.assertThat(
            "N concurrent value() calls for one cold key must issue exactly one BlobStore.get",
            fake.getCalls(), new IsEqual<>(1)
        );
    }

    @Test
    void bootRebuildRestoresExistsAndMetadataWithoutTouchingBlobStore(@TempDir final Path tmp) {
        final RecordingBlobStore fake = new RecordingBlobStore();
        final byte[] data = "rebuild-me".getBytes(StandardCharsets.UTF_8);
        fake.seed("rebuilt.jar", data);
        final Key key = new Key.From("rebuilt.jar");
        // First instance: cold-fills the key, persisting bytes + sidecar to disk.
        final CachedBlobStorage first = CachedBlobStorageTest.storage(fake, tmp);
        first.value(key).join();
        final int getCallsAfterFirstFill = fake.getCalls();
        // Drop the in-memory index and re-instantiate against the SAME disk directory --
        // the boot scan alone must restore correctness.
        final CachedBlobStorage second = CachedBlobStorageTest.storage(fake, tmp);
        MatcherAssert.assertThat(
            "exists() must be correct purely from the disk-scan rebuild",
            second.exists(key).join(), new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "metadata() must be correct purely from the disk-scan rebuild",
            second.metadata(key).join().read(Meta.OP_SIZE).get(), new IsEqual<>((long) data.length)
        );
        final byte[] served = second.value(key).join().asBytesFuture().join();
        MatcherAssert.assertThat(served, new IsEqual<>(data));
        MatcherAssert.assertThat(
            "none of exists()/metadata()/value() after rebuild may re-contact the blob store",
            fake.getCalls(), new IsEqual<>(getCallsAfterFirstFill)
        );
        MatcherAssert.assertThat(fake.headCalls(), new IsEqual<>(0));
    }

    @Test
    void saveWritesThroughToBlobStoreAndIsImmediatelyServedFromDisk(@TempDir final Path tmp) {
        final RecordingBlobStore fake = new RecordingBlobStore();
        final CachedBlobStorage storage = CachedBlobStorageTest.storage(fake, tmp);
        final Key key = new Key.From("uploaded.jar");
        final byte[] data = "uploaded-bytes".getBytes(StandardCharsets.UTF_8);
        storage.save(key, new Content.From(data)).join();
        MatcherAssert.assertThat("save() writes through to the blob store", fake.putCalls(), new IsEqual<>(1));
        final byte[] read = storage.value(key).join().asBytesFuture().join();
        MatcherAssert.assertThat(read, new IsEqual<>(data));
        MatcherAssert.assertThat(
            "reading back a just-saved key must be a pure disk hit",
            fake.getCalls(), new IsEqual<>(0)
        );
        MatcherAssert.assertThat(storage.exists(key).join(), new IsEqual<>(true));
        MatcherAssert.assertThat(fake.headCalls(), new IsEqual<>(0));
    }

    @Test
    void deleteRemovesFromBlobStoreDiskAndIndex(@TempDir final Path tmp) {
        final RecordingBlobStore fake = new RecordingBlobStore();
        final CachedBlobStorage storage = CachedBlobStorageTest.storage(fake, tmp);
        final Key key = new Key.From("deleteme.jar");
        storage.save(key, new Content.From("bye".getBytes(StandardCharsets.UTF_8))).join();
        storage.delete(key).join();
        MatcherAssert.assertThat(fake.deleteCalls(), new IsEqual<>(1));
        MatcherAssert.assertThat(storage.exists(key).join(), new IsEqual<>(false));
    }

    @Test
    void listPrefixReturnsIndexedKeysOnly(@TempDir final Path tmp) {
        final RecordingBlobStore fake = new RecordingBlobStore();
        final CachedBlobStorage storage = CachedBlobStorageTest.storage(fake, tmp);
        storage.save(new Key.From("a", "1.jar"), new Content.From("1".getBytes(StandardCharsets.UTF_8))).join();
        storage.save(new Key.From("a", "2.jar"), new Content.From("2".getBytes(StandardCharsets.UTF_8))).join();
        storage.save(new Key.From("b", "3.jar"), new Content.From("3".getBytes(StandardCharsets.UTF_8))).join();
        final Collection<Key> matches = storage.list(new Key.From("a")).join();
        MatcherAssert.assertThat(matches.size(), new IsEqual<>(2));
        MatcherAssert.assertThat(
            "list() must be answered purely from the index (zero blob-store LIST calls)",
            fake.listCalls(), new IsEqual<>(0)
        );
    }

    @Test
    void coldMissIsNegativelyCachedAndDoesNotRepeatlyHitBlobStore(@TempDir final Path tmp) {
        final RecordingBlobStore fake = new RecordingBlobStore();
        final CachedBlobStorage storage = CachedBlobStorageTest.storage(fake, tmp);
        final Key key = new Key.From("never-existed.jar");
        MatcherAssert.assertThat(storage.exists(key).join(), new IsEqual<>(false));
        MatcherAssert.assertThat(fake.headCalls(), new IsEqual<>(1));
        MatcherAssert.assertThat(
            "a repeated exists() within the negative TTL must be answered from the index",
            storage.exists(key).join(), new IsEqual<>(false)
        );
        MatcherAssert.assertThat(fake.headCalls(), new IsEqual<>(1));
    }

    private static CachedBlobStorage storage(final RecordingBlobStore fake, final Path tmp) {
        return new CachedBlobStorage(fake, tmp, FRESHNESS_TTL, NEGATIVE_TTL);
    }
}
