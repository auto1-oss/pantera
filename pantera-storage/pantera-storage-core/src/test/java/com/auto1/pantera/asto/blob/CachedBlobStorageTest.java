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
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.IsInstanceOf;
import org.junit.jupiter.api.Assertions;
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

    // ===== WS1.2 write-back tests (spec WS1-storage-for-scale.md sect.3.C) =====

    @Test
    void writeBackSaveAcksFromDiskThenUploaderConfirmsPresent(@TempDir final Path tmp) throws Exception {
        final RecordingBlobStore fake = new RecordingBlobStore();
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        fake.gatePut(entered, release);
        final CachedBlobStorage storage = CachedBlobStorageTest.writeBackStorage(fake, tmp);
        final Key key = new Key.From("wb.jar");
        final byte[] data = "write-back-bytes".getBytes(StandardCharsets.UTF_8);

        storage.save(key, new Content.From(data)).join();

        // Acked from local disk durability, not the blob-store PUT: zero
        // GETs, and the index already carries a PENDING_WRITE entry before
        // the gated upload has even started.
        MatcherAssert.assertThat("save() must not wait for the blob-store PUT", fake.getCalls(), new IsEqual<>(0));
        MatcherAssert.assertThat(fake.putCalls(), new IsEqual<>(0));
        MatcherAssert.assertThat("index must record PENDING_WRITE immediately after save() returns", storage.isPendingWrite(key), new IsEqual<>(true));
        final byte[] servedWhilePending = storage.value(key).join().asBytesFuture().join();
        MatcherAssert.assertThat("value() must serve the just-saved bytes from disk", servedWhilePending, new IsEqual<>(data));
        MatcherAssert.assertThat("serving a PENDING_WRITE key must issue zero blobStore.get calls", fake.getCalls(), new IsEqual<>(0));

        MatcherAssert.assertThat("the uploader pool must pick up the enqueued key", entered.await(10, TimeUnit.SECONDS), new IsEqual<>(true));
        release.countDown();

        CachedBlobStorageTest.awaitTrue(() -> !storage.isPendingWrite(key), Duration.ofSeconds(5));
        MatcherAssert.assertThat("driving the uploader must confirm the upload with exactly one PUT", fake.putCalls(), new IsEqual<>(1));
        MatcherAssert.assertThat(fake.getCalls(), new IsEqual<>(0));

        // Teardown-safety barrier: awaitTrue(!isPendingWrite) above returns at
        // index.putPresent, one statement BEFORE onUploadSuccess synchronously
        // persists the .meta sidecar into the sharded cache dir and only THEN
        // releases the admission permit. Await the permit's return so no
        // uploader-thread sidecar write can race @TempDir cleanup ("5b/2a").
        CachedBlobStorageTest.awaitTrue(
            () -> storage.writeBackPermitsAvailable() == storage.writeBackQueueCapacity(),
            Duration.ofSeconds(5)
        );
    }

    @Test
    void crashBeforeDrainReplaysPendingWriteOnFreshInstance(@TempDir final Path tmp) throws Exception {
        final RecordingBlobStore stalledFake = new RecordingBlobStore();
        final CountDownLatch entered = new CountDownLatch(1);
        // Never counted down: simulates the process crashing before the
        // write-back upload drains.
        final CountDownLatch neverReleases = new CountDownLatch(1);
        stalledFake.gatePut(entered, neverReleases);
        final CachedBlobStorage crashed = CachedBlobStorageTest.writeBackStorage(stalledFake, tmp);
        final Key key = new Key.From("crash.jar");
        final byte[] data = "not-yet-durable-in-blob-store".getBytes(StandardCharsets.UTF_8);
        crashed.save(key, new Content.From(data)).join();
        MatcherAssert.assertThat(entered.await(10, TimeUnit.SECONDS), new IsEqual<>(true));
        MatcherAssert.assertThat(
            "the simulated crash never lets the upload complete -- must stay PENDING_WRITE",
            crashed.isPendingWrite(key), new IsEqual<>(true)
        );

        // "Restart": a FRESH CachedBlobStorage instance over the SAME disk
        // directory with a FRESH RecordingBlobStore (no shared in-memory
        // state whatsoever) -- proves recovery is driven purely by the
        // sidecar StorageIndex#rebuildFromDisk persisted, not by anything
        // held in memory by the crashed instance.
        final RecordingBlobStore freshFake = new RecordingBlobStore();
        final CountDownLatch replayEntered = new CountDownLatch(1);
        final CountDownLatch replayRelease = new CountDownLatch(1);
        freshFake.gatePut(replayEntered, replayRelease);
        final CachedBlobStorage restarted = CachedBlobStorageTest.writeBackStorage(freshFake, tmp);

        MatcherAssert.assertThat(
            "boot replay must re-enqueue the still-pending key on the fresh instance",
            replayEntered.await(10, TimeUnit.SECONDS), new IsEqual<>(true)
        );
        replayRelease.countDown();
        CachedBlobStorageTest.awaitTrue(() -> !restarted.isPendingWrite(key), Duration.ofSeconds(5));
        MatcherAssert.assertThat("the replayed key must land in the blob store exactly once", freshFake.putCalls(), new IsEqual<>(1));
        final byte[] served = restarted.value(key).join().asBytesFuture().join();
        MatcherAssert.assertThat(served, new IsEqual<>(data));

        // Teardown-safety barrier: the replay's onUploadSuccess persists the
        // .meta sidecar before it completes the boot-replay barrier, whereas
        // awaitTrue(!isPendingWrite) above returns one statement earlier at
        // index.putPresent. Await the replay barrier so that trailing sidecar
        // write cannot race @TempDir cleanup. (Boot-replay uploads bypass the
        // admission gate, so the permit-count barrier used elsewhere does not
        // apply here.)
        restarted.bootReplayComplete().get(5, TimeUnit.SECONDS);
    }

    @Test
    void saveRejectsWithSaturatedExceptionAtHighWaterMarkWithoutWritingDisk(@TempDir final Path tmp) throws Exception {
        final RecordingBlobStore fake = new RecordingBlobStore();
        // put() never completes -- every admitted upload stays "in flight"
        // forever, so the admission gate stays saturated once filled.
        fake.gatePut(new CountDownLatch(1), new CountDownLatch(1));
        final CachedBlobStorage.WriteBackConfig config =
            new CachedBlobStorage.WriteBackConfig(2, 2, 5, 10L, 100L, 7L);
        final CachedBlobStorage storage = CachedBlobStorageTest.writeBackStorage(fake, tmp, config);

        storage.save(new Key.From("a.jar"), new Content.From("a".getBytes(StandardCharsets.UTF_8))).join();
        storage.save(new Key.From("b.jar"), new Content.From("b".getBytes(StandardCharsets.UTF_8))).join();
        // Both admissions are granted synchronously inside save() before any
        // disk I/O -- by the time both joins return, the 2-permit queue is
        // exhausted regardless of whether the uploader threads have started.

        final Key rejected = new Key.From("c.jar");
        final CompletableFuture<Void> saveFuture =
            storage.save(rejected, new Content.From("c".getBytes(StandardCharsets.UTF_8)));
        final ExecutionException wrapped = Assertions.assertThrows(
            ExecutionException.class, () -> saveFuture.get(10, TimeUnit.SECONDS)
        );
        MatcherAssert.assertThat(wrapped.getCause(), new IsInstanceOf(WriteBackSaturatedException.class));
        final WriteBackSaturatedException saturated = (WriteBackSaturatedException) wrapped.getCause();
        MatcherAssert.assertThat(saturated.retryAfterSeconds(), new IsEqual<>(7L));
        MatcherAssert.assertThat(
            "admission is checked BEFORE any disk write -- the rejected key must never reach disk"
                + " (checked at its sharded on-disk path -- WS1.4 CacheKeyShard, not the legacy literal path)",
            Files.exists(tmp.resolve(CacheKeyShard.toDiskKey(rejected).string())), new IsEqual<>(false)
        );
    }

    @Test
    void writeThroughSaveDoesNotCompleteUntilBlobStorePutObserved(@TempDir final Path tmp) throws Exception {
        final RecordingBlobStore fake = new RecordingBlobStore();
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        fake.gatePut(entered, release);
        final CachedBlobStorage storage = new CachedBlobStorage(
            fake, tmp, FRESHNESS_TTL, NEGATIVE_TTL, true,
            CachedBlobStorage.WriteBackConfig.defaults(), CachedBlobStorage.EvictionConfig.defaults()
        );
        final Key key = new Key.From("sync.jar");

        final CompletableFuture<Void> save =
            storage.save(key, new Content.From("sync-bytes".getBytes(StandardCharsets.UTF_8)));

        MatcherAssert.assertThat("the PUT must have started", entered.await(10, TimeUnit.SECONDS), new IsEqual<>(true));
        MatcherAssert.assertThat(
            "write-through save() must NOT complete before the blob-store PUT is observed",
            save.isDone(), new IsEqual<>(false)
        );
        release.countDown();
        save.join();
        MatcherAssert.assertThat(fake.putCalls(), new IsEqual<>(1));
    }

    @Test
    void deletingAPendingWriteKeySkipsTheBlobStoreDeleteCall(@TempDir final Path tmp) throws Exception {
        final RecordingBlobStore fake = new RecordingBlobStore();
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch neverReleases = new CountDownLatch(1);
        fake.gatePut(entered, neverReleases);
        final CachedBlobStorage storage = CachedBlobStorageTest.writeBackStorage(fake, tmp);
        final Key key = new Key.From("delete-while-pending.jar");
        storage.save(key, new Content.From("x".getBytes(StandardCharsets.UTF_8))).join();
        MatcherAssert.assertThat(entered.await(10, TimeUnit.SECONDS), new IsEqual<>(true));

        storage.delete(key).join();

        MatcherAssert.assertThat(
            "the key was never confirmed in the blob store -- delete() must not call BlobStore.delete for it",
            fake.deleteCalls(), new IsEqual<>(0)
        );
        MatcherAssert.assertThat(storage.exists(key).join(), new IsEqual<>(false));
    }

    @Test
    void bootReplayDoesNotOverReleaseTheAdmissionGate(@TempDir final Path tmp) throws Exception {
        // A crashed instance leaves ONE PENDING_WRITE entry on disk (data file
        // + sidecar) that a fresh instance's boot replay must re-upload.
        CachedBlobStorageTest.seedPendingWriteOnDisk(
            tmp, "replayed.jar", "durable-locally".getBytes(StandardCharsets.UTF_8)
        );
        final RecordingBlobStore fake = new RecordingBlobStore();
        // Capacity of exactly ONE permit makes any over-release observable.
        final CachedBlobStorage.WriteBackConfig capacityOne =
            new CachedBlobStorage.WriteBackConfig(1, 1, 5, 10L, 100L, 3L);
        final CachedBlobStorage storage = CachedBlobStorageTest.writeBackStorage(fake, tmp, capacityOne);

        // Deterministically await the full terminal path of boot replay --
        // including the admission bookkeeping -- then assert the invariant.
        storage.bootReplayComplete().get(10, TimeUnit.SECONDS);
        MatcherAssert.assertThat("boot replay uploads the pending key exactly once", fake.putCalls(), new IsEqual<>(1));
        MatcherAssert.assertThat(
            "the replayed key is now durably confirmed (PRESENT), not pending",
            storage.isPendingWrite(new Key.From("replayed.jar")), new IsEqual<>(false)
        );
        // Core regression: a boot-replay upload bypasses admission, so it must
        // NOT release a permit it never acquired. The gate must still hold
        // exactly its configured capacity (1), never an inflated 2.
        MatcherAssert.assertThat(
            "boot replay must not inflate the admission gate above its configured capacity",
            storage.writeBackPermitsAvailable(), new IsEqual<>(1)
        );

        // Behavioural corollary: with the bound intact at 1, a single gated
        // (never-completing) save consumes the only permit and the next save
        // is correctly rejected. Under the over-release bug the gate would
        // hold 2 permits and this second save would be wrongly admitted.
        fake.gatePut(new CountDownLatch(1), new CountDownLatch(1));
        storage.save(new Key.From("new1.jar"), new Content.From("1".getBytes(StandardCharsets.UTF_8))).join();
        final CompletableFuture<Void> rejected =
            storage.save(new Key.From("new2.jar"), new Content.From("2".getBytes(StandardCharsets.UTF_8)));
        final ExecutionException wrapped = Assertions.assertThrows(
            ExecutionException.class, () -> rejected.get(10, TimeUnit.SECONDS)
        );
        MatcherAssert.assertThat(wrapped.getCause(), new IsInstanceOf(WriteBackSaturatedException.class));
    }

    // ===== WS1.4 eviction + admission control tests (spec WS1-storage-for-scale.md sect.3.D) =====

    @Test
    void admissionBoundKeepsActualOnDiskBytesWithinTheConfiguredMaxAcrossAWriteFlood(@TempDir final Path tmp)
        throws IOException {
        // A durable PENDING_WRITE entry -- seeded directly on disk exactly as
        // a crashed-before-drain write-back would leave it -- must survive
        // the entire flood untouched: it is the ONLY durable copy of its
        // bytes (acceptance #5, second half).
        final byte[] pendingBytes = "still-uploading".getBytes(StandardCharsets.UTF_8);
        CachedBlobStorageTest.seedPendingWriteOnDisk(tmp, "pending.jar", pendingBytes);
        final Key pendingKey = new Key.From("pending.jar");

        final long maxDiskBytes = 500L;
        final CachedBlobStorage.EvictionConfig eviction =
            new CachedBlobStorage.EvictionConfig(maxDiskBytes, 90, 80, CachedBlobStorage.EvictionPolicy.LRU);
        final RecordingBlobStore fake = new RecordingBlobStore();
        // Write-through: each save()'s future completes only once the entry
        // is fully PRESENT (eviction-eligible), keeping the flood's
        // bookkeeping deterministic without any background upload race.
        final CachedBlobStorage storage = new CachedBlobStorage(
            fake, tmp, FRESHNESS_TTL, NEGATIVE_TTL, true, CachedBlobStorage.WriteBackConfig.defaults(), eviction
        );

        for (int i = 0; i < 40; i++) {
            final byte[] payload = ("flood-payload-" + i).getBytes(StandardCharsets.UTF_8);
            storage.save(new Key.From("flood-" + i + ".jar"), new Content.From(payload)).join();
            MatcherAssert.assertThat(
                "on-disk bytes must never exceed cache.max-disk-bytes, checked after every single write in the flood",
                CachedBlobStorageTest.actualOnDiskBytes(tmp) <= maxDiskBytes, new IsEqual<>(true)
            );
        }

        MatcherAssert.assertThat(
            "the index's own running counter must agree with the real on-disk byte total",
            storage.diskBytesUsed(), new IsEqual<>(CachedBlobStorageTest.actualOnDiskBytes(tmp))
        );
        MatcherAssert.assertThat(
            "a PENDING_WRITE entry must never be evicted, however aggressive the flood",
            storage.isPendingWrite(pendingKey), new IsEqual<>(true)
        );
        MatcherAssert.assertThat(
            "the PENDING_WRITE entry's disk file must physically survive the flood",
            Files.exists(tmp.resolve(CacheKeyShard.toDiskKey(pendingKey).string())), new IsEqual<>(true)
        );
    }

    @Test
    void watermarkEvictionEvictsTheColdestLfuEntriesFirstDownTowardTheLowWatermark(@TempDir final Path tmp) {
        // LFU, driven entirely by invocation counts (CLAUDE.md doctrine --
        // never assert wall-clock): five keys are written, then value()'d a
        // strictly descending number of times (k1: 5 hits ... k5: 1 hit) so
        // coldness ordering is unambiguous. A sixth write then crosses the
        // high watermark and must evict the coldest keys first, down toward
        // the low watermark, leaving the hottest keys untouched.
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
        // k1 -> 5 hits (hottest) ... k5 -> 1 hit (coldest).
        for (int i = 1; i <= 5; i++) {
            for (int hit = 0; hit < 6 - i; hit++) {
                storage.value(new Key.From("k" + i + ".jar")).join();
            }
        }
        MatcherAssert.assertThat(
            "sanity check: five 150-byte entries fit comfortably under the 800-byte high watermark",
            storage.diskBytesUsed(), new IsEqual<>(750L)
        );

        // Crosses the high watermark (750 + 150 = 900 > 800): triggers
        // eviction toward the low watermark (400).
        storage.save(new Key.From("k6.jar"), new Content.From(payload)).join();

        // Eviction is LOCAL-DISK-CACHE housekeeping, not deletion -- every
        // key was already durably write-through'd to the blob store, so
        // exists()/value() stay true for an evicted key too (a cold re-fill
        // would serve it). isCachedOnDisk() is the accessor that reflects
        // eviction specifically.
        MatcherAssert.assertThat("k5 (coldest, 1 hit) must be evicted first", storage.isCachedOnDisk(new Key.From("k5.jar")), new IsEqual<>(false));
        MatcherAssert.assertThat("k4 (2nd coldest, 2 hits) must be evicted next", storage.isCachedOnDisk(new Key.From("k4.jar")), new IsEqual<>(false));
        MatcherAssert.assertThat("k3 (3rd coldest, 3 hits) must be evicted to reach the low watermark", storage.isCachedOnDisk(new Key.From("k3.jar")), new IsEqual<>(false));
        MatcherAssert.assertThat("k2 (4 hits) is warm enough to survive", storage.isCachedOnDisk(new Key.From("k2.jar")), new IsEqual<>(true));
        MatcherAssert.assertThat("k1 (hottest, 5 hits) must never be evicted", storage.isCachedOnDisk(new Key.From("k1.jar")), new IsEqual<>(true));
        MatcherAssert.assertThat("k6 (just written) must be cached on disk", storage.isCachedOnDisk(new Key.From("k6.jar")), new IsEqual<>(true));
        MatcherAssert.assertThat(
            "final usage: k1 + k2 + k6 = 450 bytes (evicted down to the 400-byte low watermark, then admitted k6)",
            storage.diskBytesUsed(), new IsEqual<>(450L)
        );
    }

    @Test
    void shardedCacheDirRoundTripsWriteBootRebuildRead(@TempDir final Path tmp) {
        final RecordingBlobStore fake = new RecordingBlobStore();
        final CachedBlobStorage first = CachedBlobStorageTest.storage(fake, tmp);
        final int keyCount = 25;
        for (int i = 0; i < keyCount; i++) {
            final byte[] data = ("sharded-content-" + i).getBytes(StandardCharsets.UTF_8);
            first.save(new Key.From("group" + i, "artifact-" + i, "file-" + i + ".jar"), new Content.From(data)).join();
        }
        MatcherAssert.assertThat(
            "a 2-level hex fan-out must not collapse every key into one flat directory",
            CachedBlobStorageTest.topLevelShardDirCount(tmp) > 1, new IsEqual<>(true)
        );

        // Drop the in-memory index entirely: re-instantiate over the SAME
        // sharded directory with a FRESH RecordingBlobStore (no shared
        // in-memory state) -- exists()/value() must be correct purely from
        // StorageIndex#rebuildFromDisk decoding CacheKeyShard's leaf names.
        final CachedBlobStorage rebuilt = CachedBlobStorageTest.storage(new RecordingBlobStore(), tmp);
        for (int i = 0; i < keyCount; i++) {
            final Key key = new Key.From("group" + i, "artifact-" + i, "file-" + i + ".jar");
            final byte[] expected = ("sharded-content-" + i).getBytes(StandardCharsets.UTF_8);
            MatcherAssert.assertThat(
                "exists() must be correct purely from the sharded boot rebuild for key " + key,
                rebuilt.exists(key).join(), new IsEqual<>(true)
            );
            MatcherAssert.assertThat(
                "value() must serve the correct bytes purely from the sharded boot rebuild for key " + key,
                rebuilt.value(key).join().asBytesFuture().join(), new IsEqual<>(expected)
            );
        }
    }

    /**
     * Ground-truth on-disk byte total via a real directory walk -- used only
     * to VERIFY the admission bound against reality; production code never
     * does this (the whole point of {@link StorageIndex}'s running counter).
     * Excludes {@code .meta} sidecars and the {@code .tmp} staging directory,
     * mirroring {@code StorageIndex}'s own boot-scan filtering.
     */
    private static long actualOnDiskBytes(final Path root) throws IOException {
        try (java.util.stream.Stream<Path> walk = Files.walk(root)) {
            return walk
                .filter(Files::isRegularFile)
                .filter(path -> !path.getFileName().toString().endsWith(StorageIndex.SIDECAR_SUFFIX))
                .filter(path -> {
                    final Path parent = path.getParent();
                    return parent == null || !".tmp".equals(parent.getFileName().toString());
                })
                .mapToLong(path -> {
                    try {
                        return Files.size(path);
                    } catch (final IOException ex) {
                        throw new java.io.UncheckedIOException(ex);
                    }
                })
                .sum();
        }
    }

    /**
     * Counts distinct first-level shard directories directly under {@code
     * root} -- used only to sanity-check that {@code CacheKeyShard}'s hex
     * fan-out actually fans out (as opposed to accidentally collapsing every
     * key into a single directory).
     */
    private static long topLevelShardDirCount(final Path root) {
        try (java.util.stream.Stream<Path> children = Files.list(root)) {
            return children.filter(Files::isDirectory).filter(path -> !".tmp".equals(path.getFileName().toString())).count();
        } catch (final IOException ex) {
            throw new java.io.UncheckedIOException(ex);
        }
    }

    private static void seedPendingWriteOnDisk(final Path root, final String name, final byte[] data)
        throws IOException {
        // Written at the sharded on-disk path (WS1.4 CacheKeyShard), the
        // exact same layout CachedBlobStorage itself uses, so the boot-time
        // StorageIndex#rebuildFromDisk scan recovers it.
        final Path dataFile = root.resolve(CacheKeyShard.toDiskKey(new Key.From(name)).string());
        Files.createDirectories(dataFile.getParent());
        Files.write(dataFile, data);
        StorageIndex.Sidecar.write(
            Path.of(dataFile + StorageIndex.SIDECAR_SUFFIX),
            StorageIndex.Entry.pendingWrite(data.length, null, null, 1L)
        );
    }

    private static CachedBlobStorage writeBackStorage(final RecordingBlobStore fake, final Path tmp) {
        return CachedBlobStorageTest.writeBackStorage(fake, tmp, CachedBlobStorage.WriteBackConfig.defaults());
    }

    private static CachedBlobStorage writeBackStorage(
        final RecordingBlobStore fake, final Path tmp, final CachedBlobStorage.WriteBackConfig config
    ) {
        return new CachedBlobStorage(
            fake, tmp, FRESHNESS_TTL, NEGATIVE_TTL, false, config, CachedBlobStorage.EvictionConfig.defaults()
        );
    }

    private static CachedBlobStorage writeBackStorage(
        final RecordingBlobStore fake,
        final Path tmp,
        final CachedBlobStorage.WriteBackConfig config,
        final CachedBlobStorage.EvictionConfig evictionConfig
    ) {
        return new CachedBlobStorage(fake, tmp, FRESHNESS_TTL, NEGATIVE_TTL, false, config, evictionConfig);
    }

    /**
     * Poll for an eventual state transition after deterministically
     * triggering it via a latch release -- CLAUDE.md testing doctrine
     * explicitly sanctions this for shared-resource transient contention
     * (as opposed to asserting an absolute wall-clock bound).
     */
    private static void awaitTrue(final BooleanSupplier condition, final Duration timeout) {
        final long deadlineNanos = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadlineNanos) {
                throw new AssertionError("Condition not met within " + timeout);
            }
            try {
                Thread.sleep(10);
            } catch (final InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new AssertionError(ex);
            }
        }
    }

    private static CachedBlobStorage storage(final RecordingBlobStore fake, final Path tmp) {
        return new CachedBlobStorage(fake, tmp, FRESHNESS_TTL, NEGATIVE_TTL);
    }
}
