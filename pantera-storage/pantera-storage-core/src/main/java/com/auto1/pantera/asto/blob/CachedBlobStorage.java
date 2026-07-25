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
import com.auto1.pantera.asto.ListResult;
import com.auto1.pantera.asto.Meta;
import com.auto1.pantera.asto.MetaCommon;
import com.auto1.pantera.asto.PanteraIOException;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.UnderLockOperation;
import com.auto1.pantera.asto.ValueNotFoundException;
import com.auto1.pantera.asto.cache.OptimizedStorageCache;
import com.auto1.pantera.asto.ext.ContentDigest;
import com.auto1.pantera.asto.ext.Digests;
import com.auto1.pantera.asto.fs.FileStorage;
import com.auto1.pantera.asto.lock.storage.StorageLock;
import com.auto1.pantera.asto.log.EcsLogger;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * {@link Storage} that composes a local disk tier, a {@link StorageIndex},
 * and a durable {@link BlobStore} cold tier so that a hit never contacts the
 * blob store (spec {@code WS1-storage-for-scale.md} &sect;3.B).
 *
 * <p>This is the WS1.1 read-path rename target of the spec's
 * {@code CachedS3Storage} -- generalised to any {@link BlobStore}, not just
 * S3. It replaces {@code DiskCacheStorage} for repositories that opt in via
 * {@code cache.mode: index} (see {@code S3StorageFactory}); {@code
 * DiskCacheStorage} remains the default so existing repositories are
 * unaffected until they opt in.</p>
 *
 * <h2>Hit path (zero blob-store contact)</h2>
 * <ul>
 *   <li>{@link #exists(Key)} / {@link #metadata(Key)}: answered from {@link
 *   StorageIndex} in memory. A key the index has never seen triggers a
 *   single-flighted {@link BlobStore#head(Key)} (metadata only, no bytes) --
 *   the ONLY case that ever contacts the blob store for these two calls.</li>
 *   <li>{@link #list(Key)} / {@link #list(Key, String)}: answered purely from
 *   {@link StorageIndex}'s prefix scan -- scoped to what this index has
 *   observed (see {@code docs/admin-guide/storage-backends.md}).</li>
 *   <li>{@link #value(Key)}: the index says {@code presentOnDisk} and the
 *   entry is inside its freshness TTL &rArr; stream from the local disk tier
 *   via {@link OptimizedStorageCache} NIO, zero blob-store contact. A miss
 *   (unknown, negative, stale, or not yet on disk) single-flights a {@link
 *   BlobStore#get(Key)}: N concurrent callers for the same cold key trigger
 *   exactly ONE blob-store GET, after which every caller (including the one
 *   that triggered it) independently reads the now-local file. This trades a
 *   deliberate simplification -- the cold-fill leader also waits for the full
 *   object to land on disk before its own read starts, rather than the
 *   tee-to-client-while-writing streaming {@code DiskCacheStorage} does per
 *   key -- for a correctness-first implementation with no shared-Content
 *   multi-subscriber hazard; true tee-while-downloading is a natural
 *   follow-up alongside WS1.2/WS1.3's write-path streaming work.</li>
 * </ul>
 *
 * <p><strong>Freshness, not inline validation:</strong> a disk-served hit is
 * never preceded by an inline blob-store HEAD (the whole point -- {@code
 * DiskCacheStorage} pays 1-2 synchronous S3 HEADs per hit). The local copy is
 * trusted for {@code freshnessTtl}; cross-node staleness beyond that window
 * is out of WS1.1 scope (pub/sub invalidation is WS1.5).</p>
 *
 * <h2>Write path (WS1.2: async durable write-back, default)</h2>
 * <p>{@link #save(Key, Content)} dispatches on the {@code writeThrough}
 * constructor flag:</p>
 * <ul>
 *   <li><strong>write-back (default, {@code writeThrough=false}):</strong> a
 *   bounded admission gate ({@link Semaphore}) is checked FIRST, before any
 *   byte is written to disk, so a saturated queue cannot grow the disk cache
 *   unbounded -- an admission miss fails the save with {@link
 *   WriteBackSaturatedException} carrying a {@code retryAfterSeconds} hint.
 *   On admission, bytes land on local disk (this is the durability basis the
 *   caller is acked from), a digest is computed once from the just-written
 *   file, the index records {@code s3State=PENDING_WRITE} (persisted to the
 *   {@code .meta} sidecar so a crash before drain survives a restart), and
 *   the key is handed to a bounded pool of dedicated daemon uploader threads
 *   ({@code pantera-storage-writeback-*}, never the Vert.x event loop) that
 *   drain the queue to the blob store with bounded retry/backoff. Success
 *   flips the entry to {@code PRESENT} and releases the admission permit;
 *   exhausted retries dead-letter the key (logged, permit released, entry
 *   left {@code PENDING_WRITE} so the NEXT boot replay retries it -- see the
 *   constructor). The returned future completes at the disk-durable point,
 *   not after the blob-store {@code PUT}: this is the acknowledged durability
 *   window documented in {@code docs/admin-guide/storage-backends.md}.</li>
 *   <li><strong>write-through ({@code writeThrough=true}, opt-out):</strong>
 *   the pre-WS1.2 synchronous behaviour, preserved verbatim for repositories
 *   that cannot tolerate the write-back durability window (e.g. compliance):
 *   disk write, digest, durable blob-store {@code PUT}, then index update --
 *   the returned future completes only after the blob-store ack.</li>
 * </ul>
 * <p>{@link #delete(Key)} removes from the blob store first (the durability
 * tier) for a {@code PRESENT} entry, then best-effort cleans up the local
 * disk copy and index entry. A {@code PENDING_WRITE} entry has never been
 * confirmed in the blob store, so the blob-store call is skipped entirely
 * (nothing to delete there yet) -- see {@link #delete(Key)}'s javadoc for the
 * narrow accepted race with an in-flight uploader this implies.</p>
 *
 * @since 2.3.0
 */
public final class CachedBlobStorage implements Storage, AutoCloseable {

    /**
     * Durable cold tier.
     */
    private final BlobStore blobStore;

    /**
     * Local disk tier: plain {@link FileStorage} over the cache directory
     * passed to the constructor, so {@link OptimizedStorageCache} always
     * takes its direct-NIO fast path.
     */
    private final FileStorage disk;

    /**
     * In-memory metadata index.
     */
    private final StorageIndex index;

    /**
     * How long a disk-cached entry is trusted without re-validation.
     */
    private final Duration freshnessTtl;

    /**
     * How long a confirmed blob-store miss is remembered.
     */
    private final Duration negativeTtl;

    /**
     * Single-flight coalescing map for cold {@link BlobStore#get(Key)} fills:
     * at most one fetch-and-persist in flight per key at a time.
     */
    private final ConcurrentHashMap<Key, CompletableFuture<Void>> getInFlight = new ConcurrentHashMap<>();

    /**
     * Single-flight coalescing map for cold {@link BlobStore#head(Key)}
     * checks (used by {@link #exists(Key)}/{@link #metadata(Key)} on an
     * index miss).
     */
    private final ConcurrentHashMap<Key, CompletableFuture<Optional<StorageIndex.Entry>>> headInFlight =
        new ConcurrentHashMap<>();

    /**
     * Identifier for logs/metrics.
     */
    private final String id;

    /**
     * {@code true}: {@link #save(Key, Content)} keeps the pre-WS1.2
     * synchronous write-through behaviour. {@code false} (default): WS1.2
     * async durable write-back.
     */
    private final boolean writeThrough;

    /**
     * Write-back tuning. Only consulted when {@link #writeThrough} is
     * {@code false}.
     */
    private final WriteBackConfig writeBackConfig;

    /**
     * Bounded admission gate for the write-back queue: {@link
     * #writeBackConfig}'s {@code queueCapacity} permits, acquired in {@link
     * #saveWriteBack} BEFORE any disk write and released only when an
     * upload's outcome is final (confirmed {@code PRESENT} or
     * dead-lettered). {@code null} iff {@link #writeThrough} -- the
     * write-back machinery is not constructed at all in that mode.
     */
    private final Semaphore writeBackAdmission;

    /**
     * Bounded pool of dedicated daemon threads
     * ({@code pantera-storage-writeback-*}) draining the write-back queue --
     * never the Vert.x event loop. {@code null} iff {@link #writeThrough}.
     */
    private final ExecutorService writeBackUploaders;

    /**
     * Completes once boot replay (if any) has fully drained: every {@code
     * PENDING_WRITE} entry recovered on boot has reached a terminal upload
     * outcome. Assigned once by {@link #replayPendingWrites()}; stays the
     * pre-completed default when {@link #writeThrough} or when there was
     * nothing to replay. Package-visible for deterministic tests only -- not
     * a production correctness dependency.
     */
    private volatile CompletableFuture<Void> bootReplayComplete = CompletableFuture.completedFuture(null);

    /**
     * New cached blob storage.
     *
     * <p>Performs a blocking boot-time {@link StorageIndex#rebuildFromDisk}
     * scan of {@code diskRoot} and, in write-back mode, a boot replay of any
     * {@code PENDING_WRITE} entries the scan recovered (spec &sect;3.C) --
     * call this constructor only from a boot thread (storage-factory
     * construction), never from the Vert.x event loop, per CLAUDE.md's
     * thread model.</p>
     *
     * @param blobStore Durable cold tier.
     * @param diskRoot Local disk cache directory (created if absent).
     * @param freshnessTtl How long a confirmed-{@code PRESENT} disk copy is
     *  trusted without re-validation.
     * @param negativeTtl How long a confirmed blob-store miss is remembered.
     * @param writeThrough {@code true} to keep the pre-WS1.2 synchronous
     *  write-through save path; {@code false} (recommended default) for
     *  WS1.2 async durable write-back.
     * @param writeBackConfig Write-back tuning; ignored when {@code
     *  writeThrough}.
     */
    public CachedBlobStorage(
        final BlobStore blobStore,
        final Path diskRoot,
        final Duration freshnessTtl,
        final Duration negativeTtl,
        final boolean writeThrough,
        final WriteBackConfig writeBackConfig
    ) {
        this.blobStore = blobStore;
        this.disk = new FileStorage(diskRoot);
        this.index = new StorageIndex();
        this.freshnessTtl = freshnessTtl;
        this.negativeTtl = negativeTtl;
        this.writeThrough = writeThrough;
        this.writeBackConfig = writeBackConfig;
        this.id = "CachedBlobStorage: " + blobStore.identifier();
        try {
            Files.createDirectories(diskRoot);
        } catch (final IOException err) {
            throw new PanteraIOException(err);
        }
        this.index.rebuildFromDisk(diskRoot);
        if (writeThrough) {
            this.writeBackAdmission = null;
            this.writeBackUploaders = null;
        } else {
            this.writeBackAdmission = new Semaphore(writeBackConfig.queueCapacity());
            this.writeBackUploaders = CachedBlobStorage.newUploaderPool(writeBackConfig.uploaderThreads());
            this.replayPendingWrites();
        }
    }

    /**
     * Convenience constructor preserving the pre-WS1.2 signature and its
     * synchronous write-through behaviour verbatim, for callers that have
     * not been updated to choose a durability mode explicitly. New callers
     * should use the full constructor and pick {@code writeThrough}
     * deliberately -- production wiring ({@code S3StorageFactory}) always
     * does.
     *
     * @param blobStore Durable cold tier.
     * @param diskRoot Local disk cache directory (created if absent).
     * @param freshnessTtl How long a disk copy is trusted without re-validation.
     * @param negativeTtl How long a confirmed blob-store miss is remembered.
     */
    public CachedBlobStorage(
        final BlobStore blobStore,
        final Path diskRoot,
        final Duration freshnessTtl,
        final Duration negativeTtl
    ) {
        this(blobStore, diskRoot, freshnessTtl, negativeTtl, true, WriteBackConfig.defaults());
    }

    /**
     * Whether this instance uses the pre-WS1.2 synchronous write-through
     * save path (as opposed to WS1.2 async write-back).
     *
     * @return {@code true} for write-through.
     */
    public boolean writeThrough() {
        return this.writeThrough;
    }

    /**
     * Test/observability accessor: whether {@code key} is currently tracked
     * as {@code PENDING_WRITE} (write-back upload not yet confirmed
     * {@code PRESENT}). Package-visible only -- not a correctness dependency
     * for any production code path, mirroring {@link StorageIndex#size()}'s
     * "intended for tests" contract.
     *
     * @param key Key.
     * @return {@code true} iff the index holds a {@code PENDING_WRITE} entry.
     */
    boolean isPendingWrite(final Key key) {
        return this.index.knownEntry(key).map(StorageIndex.Entry::pendingUpload).orElse(false);
    }

    /**
     * Test-only barrier: a future that completes once boot replay has fully
     * drained (all recovered {@code PENDING_WRITE} uploads reached a terminal
     * outcome, permit bookkeeping included). Package-visible only.
     *
     * @return Boot-replay completion future (pre-completed if nothing replayed).
     */
    CompletableFuture<Void> bootReplayComplete() {
        return this.bootReplayComplete;
    }

    /**
     * Test/observability accessor for the write-back admission gate's current
     * free-permit count -- lets a test assert the bound is exactly {@code
     * queueCapacity} and was not inflated (e.g. by a release without a
     * matching acquire). Package-visible only; {@link Integer#MAX_VALUE} in
     * write-through mode (no gate).
     *
     * @return Available admission permits, or {@link Integer#MAX_VALUE} if
     *  write-through.
     */
    int writeBackPermitsAvailable() {
        final int available;
        if (this.writeBackAdmission == null) {
            available = Integer.MAX_VALUE;
        } else {
            available = this.writeBackAdmission.availablePermits();
        }
        return available;
    }

    @Override
    public CompletableFuture<Boolean> exists(final Key key) {
        return this.index.knownEntry(key)
            .<CompletableFuture<Boolean>>map(entry -> CompletableFuture.completedFuture(!entry.negative()))
            .orElseGet(() -> this.coldHead(key).thenApply(Optional::isPresent));
    }

    @Override
    public CompletableFuture<? extends Meta> metadata(final Key key) {
        return this.index.knownEntry(key)
            .filter(entry -> !entry.negative())
            .<CompletableFuture<Meta>>map(entry -> CompletableFuture.completedFuture(new IndexMeta(entry)))
            .orElseGet(() -> this.coldHead(key).thenApply(
                opt -> opt.<Meta>map(IndexMeta::new).orElseThrow(() -> new ValueNotFoundException(key))
            ));
    }

    @Override
    public CompletableFuture<Collection<Key>> list(final Key prefix) {
        return CompletableFuture.completedFuture(this.index.listPrefix(prefix));
    }

    @Override
    public CompletableFuture<ListResult> list(final Key prefix, final String delimiter) {
        return CompletableFuture.completedFuture(this.index.listPrefix(prefix, delimiter));
    }

    @Override
    public CompletableFuture<Content> value(final Key key) {
        return this.index.freshEntry(key, this.freshnessTtl)
            .<CompletableFuture<Content>>map(entry -> this.readFromDisk(key))
            .orElseGet(() -> this.coldFillThenRead(key));
    }

    @Override
    public CompletableFuture<Void> save(final Key key, final Content content) {
        return this.writeThrough
            ? this.saveWriteThrough(key, content)
            : this.saveWriteBack(key, content);
    }

    @Override
    public CompletableFuture<Void> move(final Key source, final Key destination) {
        return this.value(source)
            .thenCompose(content -> this.save(destination, content))
            .thenCompose(ignored -> this.delete(source));
    }

    @Override
    public CompletableFuture<Void> delete(final Key key) {
        final boolean pendingUpload = this.index.knownEntry(key)
            .map(StorageIndex.Entry::pendingUpload)
            .orElse(false);
        final CompletableFuture<Void> removedFromBlobStore = pendingUpload
            // Never confirmed in the blob store -- nothing to delete there.
            // Narrow accepted race: if an uploader is mid-flight for this
            // exact key right now (already past the pendingUpload check
            // inside uploadWithRetry, about to call blobStore.put), it can
            // still land the object in the blob store after this delete()
            // returns, orphaning it there. WS1.2 does not add per-key
            // upload cancellation for this; same disclosed boundary as
            // WS1.4's eviction-protection scope.
            ? CompletableFuture.completedFuture(null)
            : this.blobStore.delete(key);
        return removedFromBlobStore
            .thenCompose(ignored -> this.deleteDiskCopyBestEffort(key))
            .thenRun(() -> this.completeDelete(key));
    }

    @Override
    public <T> CompletionStage<T> exclusively(
        final Key key,
        final Function<Storage, CompletionStage<T>> operation
    ) {
        return new UnderLockOperation<>(new StorageLock(this, key), operation).perform(this);
    }

    @Override
    public String identifier() {
        return this.id;
    }

    @Override
    public Optional<Path> pathFor(final Key key) {
        return this.index.knownEntry(key)
            .filter(entry -> !entry.negative() && entry.presentOnDisk())
            .flatMap(entry -> this.disk.pathFor(key));
    }

    @Override
    public void close() {
        if (this.writeBackUploaders != null) {
            this.writeBackUploaders.shutdown();
        }
        if (this.blobStore instanceof AutoCloseable) {
            try {
                ((AutoCloseable) this.blobStore).close();
            } catch (final Exception ex) {
                EcsLogger.warn("com.auto1.pantera.asto.blob")
                    .message("Failed to close underlying blob store")
                    .error(ex)
                    .field("log.source", "application")
                    .log();
            }
        }
    }

    // === value() cold path: single-flighted BlobStore.get + disk persist ===

    private CompletableFuture<Content> coldFillThenRead(final Key key) {
        return this.coldFill(key).thenCompose(ignored -> this.readFromDisk(key));
    }

    private CompletableFuture<Void> coldFill(final Key key) {
        final CompletableFuture<Void> placeholder = new CompletableFuture<>();
        final CompletableFuture<Void> prior = this.getInFlight.putIfAbsent(key, placeholder);
        final CompletableFuture<Void> result;
        if (prior == null) {
            this.blobStore.get(key)
                .thenCompose(content -> this.persistFetchedContent(key, content))
                .whenComplete((ignored, err) -> this.completeColdFill(key, placeholder, err));
            result = placeholder;
        } else {
            result = prior;
        }
        return result;
    }

    private void completeColdFill(final Key key, final CompletableFuture<Void> placeholder, final Throwable err) {
        this.getInFlight.remove(key, placeholder);
        if (err == null) {
            placeholder.complete(null);
        } else {
            if (CachedBlobStorage.isValueNotFound(err)) {
                this.index.putNegative(key, this.negativeTtl);
            }
            placeholder.completeExceptionally(err);
        }
    }

    private CompletableFuture<Void> persistFetchedContent(final Key key, final Content content) {
        return this.disk.save(key, content).thenCompose(ignored -> this.finalizeDiskWrite(key, null));
    }

    private CompletableFuture<Content> readFromDisk(final Key key) {
        return OptimizedStorageCache.optimizedValue(this.disk, key).handle(
            (content, err) -> err == null
                ? CompletableFuture.completedFuture(content)
                : this.recoverVanishedDiskEntry(key, err)
        ).thenCompose(Function.identity());
    }

    private CompletableFuture<Content> recoverVanishedDiskEntry(final Key key, final Throwable err) {
        final CompletableFuture<Content> result;
        if (CachedBlobStorage.isVanished(err)) {
            // TOCTOU: the index said presentOnDisk but the file is gone
            // (e.g. concurrent eviction). Drop the stale entry and re-fetch
            // from the blob store rather than propagating a spurious error.
            this.index.remove(key);
            result = this.coldFillThenRead(key);
        } else {
            result = CompletableFuture.failedFuture(err);
        }
        return result;
    }

    // === exists()/metadata() cold path: single-flighted BlobStore.head ===

    private CompletableFuture<Optional<StorageIndex.Entry>> coldHead(final Key key) {
        final CompletableFuture<Optional<StorageIndex.Entry>> placeholder = new CompletableFuture<>();
        final CompletableFuture<Optional<StorageIndex.Entry>> prior = this.headInFlight.putIfAbsent(key, placeholder);
        final CompletableFuture<Optional<StorageIndex.Entry>> result;
        if (prior == null) {
            this.blobStore.head(key).whenComplete((meta, err) -> this.completeColdHead(key, placeholder, meta, err));
            result = placeholder;
        } else {
            result = prior;
        }
        return result;
    }

    private void completeColdHead(
        final Key key,
        final CompletableFuture<Optional<StorageIndex.Entry>> placeholder,
        final Meta meta,
        final Throwable err
    ) {
        this.headInFlight.remove(key, placeholder);
        if (err == null) {
            final StorageIndex.Entry entry = StorageIndex.Entry.present(
                new MetaCommon(meta).size(), CachedBlobStorage.etagOf(meta), null, System.currentTimeMillis(), false
            );
            this.index.putPresent(key, entry.size(), entry.etag(), entry.digest(), false);
            placeholder.complete(Optional.of(entry));
        } else if (CachedBlobStorage.isValueNotFound(err)) {
            this.index.putNegative(key, this.negativeTtl);
            placeholder.complete(Optional.empty());
        } else {
            placeholder.completeExceptionally(err);
        }
    }

    // === save(): shared digest + disk-size helpers ===

    private CompletableFuture<String> digestWrittenFile(final Key key) {
        return OptimizedStorageCache.optimizedValue(this.disk, key)
            .thenCompose(body -> new ContentDigest(body, Digests.SHA256).hex().toCompletableFuture());
    }

    private CompletableFuture<Long> diskSize(final Key key) {
        return this.disk.metadata(key).thenApply(meta -> new MetaCommon(meta).size());
    }

    private CompletableFuture<Void> finalizeDiskWrite(final Key key, final String digest) {
        return this.diskSize(key).thenAccept(size -> {
            final long now = System.currentTimeMillis();
            this.index.putPresent(key, size, null, digest, true);
            this.writeSidecarBestEffort(key, StorageIndex.Entry.present(size, null, digest, now, true));
        });
    }

    // === save(): write-through opt-out (pre-WS1.2 behaviour, verbatim) ===

    private CompletableFuture<Void> saveWriteThrough(final Key key, final Content content) {
        return this.disk.save(key, content)
            .thenCompose(ignored -> this.digestWrittenFile(key))
            .thenCompose(digest -> this.uploadWrittenFile(key, digest));
    }

    private CompletableFuture<Void> uploadWrittenFile(final Key key, final String digest) {
        return OptimizedStorageCache.optimizedValue(this.disk, key)
            .thenCompose(body -> this.blobStore.put(key, body))
            .thenCompose(ignored -> this.finalizeDiskWrite(key, digest));
    }

    // === save(): WS1.2 async durable write-back (default) ===

    /**
     * Write-back save: admission gate first (before any disk write), then
     * disk durability, then a fire-and-forget hand-off to the uploader pool.
     * The returned future completes once the key is durable on local disk
     * and recorded {@code PENDING_WRITE} -- it does NOT wait for the
     * blob-store upload.
     */
    private CompletableFuture<Void> saveWriteBack(final Key key, final Content content) {
        if (!this.writeBackAdmission.tryAcquire()) {
            return this.rejectSaturated(key);
        }
        return this.disk.save(key, content)
            .thenCompose(ignored -> this.digestWrittenFile(key))
            .thenCompose(digest -> this.enqueuePendingWrite(key, digest))
            .whenComplete((ignored, err) -> {
                if (err != null) {
                    // Admission was granted but the write never reached the
                    // point of handing off to an uploader task (disk write,
                    // digest, or the post-write metadata read failed) --
                    // release the permit ourselves. Once enqueuePendingWrite
                    // hands off to submitUpload, ownership of the permit
                    // belongs to that task's eventual onUploadSuccess/
                    // onUploadDeadLetter and this branch does not run.
                    this.writeBackAdmission.release();
                }
            });
    }

    private CompletableFuture<Void> rejectSaturated(final Key key) {
        EcsLogger.warn("com.auto1.pantera.asto.blob")
            .message("Write-back queue saturated; rejecting save before any disk write (admission-first backpressure)")
            .eventCategory("file")
            .eventAction("write_back_saturated")
            .eventOutcome("failure")
            .field("file.path", key.string())
            .field("log.source", "application")
            .log();
        return CompletableFuture.failedFuture(
            new WriteBackSaturatedException(key.string(), this.writeBackConfig.retryAfterSeconds())
        );
    }

    private CompletableFuture<Void> enqueuePendingWrite(final Key key, final String digest) {
        return this.diskSize(key).thenAccept(size -> {
            final long now = System.currentTimeMillis();
            this.index.putPendingWrite(key, size, null, digest);
            this.writeSidecarBestEffort(key, StorageIndex.Entry.pendingWrite(size, null, digest, now));
            EcsLogger.debug("com.auto1.pantera.asto.blob")
                .message("Write-back save acked from local disk; upload enqueued")
                .eventCategory("file")
                .eventAction("write_back_enqueue")
                .eventOutcome("success")
                .field("file.path", key.string())
                .field("log.source", "application")
                .log();
            this.submitUpload(new PendingUpload(key, digest, true));
        });
    }

    /**
     * Hand a pending upload to the dedicated uploader pool. Returns a future
     * that completes once the retry loop reaches a terminal outcome (success
     * or dead-letter) -- used by {@link #replayPendingWrites()} to log
     * "boot replay complete"; the {@link #saveWriteBack} hot path does not
     * await it.
     */
    private CompletableFuture<Void> submitUpload(final PendingUpload pending) {
        final CompletableFuture<Void> done = new CompletableFuture<>();
        this.writeBackUploaders.execute(() -> {
            try {
                this.uploadWithRetry(pending);
            } finally {
                done.complete(null);
            }
        });
        return done;
    }

    /**
     * Runs entirely on a dedicated {@code pantera-storage-writeback-*}
     * daemon thread -- the blocking {@code .join()} calls here are
     * deliberate and safe (never the Vert.x event loop, per CLAUDE.md's
     * thread model), mirroring how {@code DbConsumer}'s dead-letter/backoff
     * loop blocks its own dedicated single thread.
     */
    private void uploadWithRetry(final PendingUpload pending) {
        final int maxAttempts = this.writeBackConfig.maxRetries() + 1;
        Throwable lastError = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                final Content body = OptimizedStorageCache.optimizedValue(this.disk, pending.key()).join();
                this.blobStore.put(pending.key(), body).join();
                this.onUploadSuccess(pending);
                return;
            } catch (final RuntimeException ex) {
                lastError = CachedBlobStorage.rootCause(ex);
                if (attempt < maxAttempts) {
                    this.logWriteBackRetry(pending.key(), attempt, maxAttempts - 1, lastError);
                    CachedBlobStorage.sleepBackoff(this.writeBackConfig, attempt);
                }
            }
        }
        this.onUploadDeadLetter(pending, lastError);
    }

    private void onUploadSuccess(final PendingUpload pending) {
        Throwable finalizeError = null;
        try {
            // Runs on the dedicated uploader thread (see uploadWithRetry): the
            // blocking join is deliberate and holds the admission permit until
            // the entry is fully PRESENT, so that releasing it -- and hence
            // submitUpload's completion, and the boot-replay completion
            // barrier -- happens synchronously here on the uploader thread
            // rather than on a detached callback.
            this.finalizeDiskWrite(pending.key(), pending.digest()).join();
        } catch (final RuntimeException ex) {
            finalizeError = CachedBlobStorage.rootCause(ex);
        }
        this.releaseAdmissionIfHeld(pending);
        if (finalizeError == null) {
            EcsLogger.info("com.auto1.pantera.asto.blob")
                .message("Write-back upload confirmed; entry now PRESENT")
                .eventCategory("file")
                .eventAction("write_back_confirm")
                .eventOutcome("success")
                .field("file.path", pending.key().string())
                .field("log.source", "application")
                .log();
        } else {
            // Rare: the blob-store PUT confirmed the bytes durably, but the
            // local disk.metadata() read to finalize the index entry
            // afterwards failed (e.g. the file vanished in the tiny window
            // after upload). The blob-store copy is already safe, so we do NOT
            // dead-letter (that would trigger a redundant re-upload) -- the
            // entry simply stays PENDING_WRITE and self-heals on the next boot
            // replay or write to this key.
            EcsLogger.warn("com.auto1.pantera.asto.blob")
                .message("Write-back upload confirmed but local index finalize failed; entry remains PENDING_WRITE")
                .eventCategory("file")
                .eventAction("write_back_finalize")
                .eventOutcome("failure")
                .error(finalizeError)
                .field("file.path", pending.key().string())
                .field("log.source", "application")
                .log();
        }
    }

    private void onUploadDeadLetter(final PendingUpload pending, final Throwable cause) {
        this.releaseAdmissionIfHeld(pending);
        EcsLogger.error("com.auto1.pantera.asto.blob")
            .message("Write-back upload dead-lettered after " + this.writeBackConfig.maxRetries()
                + " retries; entry remains PENDING_WRITE for the next boot replay")
            .eventCategory("file")
            .eventAction("write_back_dead_letter")
            .eventOutcome("failure")
            .error(cause)
            .field("file.path", pending.key().string())
            .field("log.source", "application")
            .log();
    }

    private void logWriteBackRetry(final Key key, final int attempt, final int maxRetries, final Throwable cause) {
        EcsLogger.warn("com.auto1.pantera.asto.blob")
            .message("Write-back upload attempt " + attempt + "/" + (maxRetries + 1)
                + " failed; retrying")
            .eventCategory("file")
            .eventAction("write_back_retry")
            .eventOutcome("failure")
            .error(cause)
            .field("file.path", key.string())
            .field("log.source", "application")
            .log();
    }

    /**
     * Boot replay (spec &sect;3.C): re-enqueues every {@code PENDING_WRITE}
     * entry {@link StorageIndex#rebuildFromDisk} recovered from {@code .meta}
     * sidecars, so a crash before drain survives a restart -- the disk file
     * IS the payload, the {@code PENDING_WRITE} sidecar IS the queue record;
     * there is no second on-disk copy of the bytes. Bypasses {@link
     * #writeBackAdmission}: these uploads already represent bytes durably
     * committed to local disk before this boot, not new admission requests,
     * so replaying more of them than {@code queueCapacity} must never fail.
     */
    private void replayPendingWrites() {
        final Collection<Key> pending = this.index.pendingWriteKeys();
        if (pending.isEmpty()) {
            return;
        }
        EcsLogger.info("com.auto1.pantera.asto.blob")
            .message("Write-back boot replay starting: re-queuing " + pending.size() + " pending upload(s)")
            .eventCategory("file")
            .eventAction("write_back_boot_replay")
            .eventOutcome("success")
            .field("pantera.storage.write_back.replayed", pending.size())
            .field("log.source", "application")
            .log();
        final List<CompletableFuture<Void>> tasks = new ArrayList<>();
        for (final Key key : pending) {
            this.index.knownEntry(key).ifPresent(
                entry -> tasks.add(this.submitUpload(new PendingUpload(key, entry.digest(), false)))
            );
        }
        final CompletableFuture<Void> all = CompletableFuture.allOf(tasks.toArray(new CompletableFuture[0]));
        all.whenComplete(
            (ignored, err) -> EcsLogger.info("com.auto1.pantera.asto.blob")
                .message("Write-back boot replay complete")
                .eventCategory("file")
                .eventAction("write_back_boot_replay")
                .eventOutcome("success")
                .field("log.source", "application")
                .log()
        );
        this.bootReplayComplete = all;
    }

    private static ExecutorService newUploaderPool(final int threads) {
        final AtomicInteger counter = new AtomicInteger();
        final ThreadFactory factory = runnable -> {
            final Thread thread = new Thread(runnable, "pantera-storage-writeback-" + counter.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newFixedThreadPool(Math.max(1, threads), factory);
    }

    private static void sleepBackoff(final WriteBackConfig config, final int attempt) {
        final long backoff = Math.min(
            config.baseBackoffMillis() * (1L << (attempt - 1)),
            config.maxBackoffMillis()
        );
        try {
            Thread.sleep(backoff);
        } catch (final InterruptedException ex) {
            // EXPECTED: shutdown signalled mid-backoff -- restore interrupt
            // and let the retry loop's next attempt (or dead-letter) proceed
            // immediately rather than block further.
            Thread.currentThread().interrupt();
        }
    }

    private static Throwable rootCause(final Throwable err) {
        Throwable cause = err;
        while (cause instanceof CompletionException && cause.getCause() != null && cause.getCause() != cause) { // NOPMD CompareObjectsWithEquals - intentional identity check (cycle guard for self-causing exception)
            cause = cause.getCause();
        }
        return cause;
    }

    /**
     * A blob-store upload awaiting confirmation.
     *
     * @param key Key to upload.
     * @param digest Already-computed content digest (hex).
     * @param admissionHeld {@code true} iff this upload holds a {@link
     *  #writeBackAdmission} permit acquired by {@link #saveWriteBack} -- and
     *  so must release exactly one on its terminal outcome. {@code false} for
     *  boot-replay uploads ({@link #replayPendingWrites}), which represent
     *  bytes already durable on disk before this boot and deliberately bypass
     *  admission: releasing a permit they never acquired would inflate the
     *  semaphore above {@code queueCapacity} and silently weaken the
     *  backpressure bound.
     */
    private record PendingUpload(Key key, String digest, boolean admissionHeld) {
    }

    private void releaseAdmissionIfHeld(final PendingUpload pending) {
        if (pending.admissionHeld()) {
            this.writeBackAdmission.release();
        }
    }

    // === delete() ===

    private CompletableFuture<Void> deleteDiskCopyBestEffort(final Key key) {
        return this.disk.exists(key).thenCompose(
            present -> present ? this.disk.delete(key) : CompletableFuture.completedFuture(null)
        );
    }

    private void completeDelete(final Key key) {
        this.index.remove(key);
        this.deleteSidecarBestEffort(key);
    }

    // === sidecar persistence (feeds StorageIndex#rebuildFromDisk on boot) ===

    private void writeSidecarBestEffort(final Key key, final StorageIndex.Entry entry) {
        this.disk.pathFor(key).ifPresent(dataPath -> {
            final Path sidecar = Path.of(dataPath + StorageIndex.SIDECAR_SUFFIX);
            try {
                StorageIndex.Sidecar.write(sidecar, entry);
            } catch (final IOException ex) {
                EcsLogger.debug("com.auto1.pantera.asto.blob")
                    .message("Failed to write index sidecar; entry remains valid in memory until restart")
                    .eventCategory("file")
                    .eventAction("storage_index_sidecar_write")
                    .eventOutcome("failure")
                    .error(ex)
                    .field("file.path", sidecar.toString())
                    .field("log.source", "application")
                    .log();
            }
        });
    }

    private void deleteSidecarBestEffort(final Key key) {
        this.disk.pathFor(key).ifPresent(dataPath -> {
            final Path sidecar = Path.of(dataPath + StorageIndex.SIDECAR_SUFFIX);
            try {
                Files.deleteIfExists(sidecar);
            } catch (final IOException ex) {
                EcsLogger.debug("com.auto1.pantera.asto.blob")
                    .message("Failed to delete index sidecar")
                    .eventCategory("file")
                    .eventAction("storage_index_sidecar_delete")
                    .eventOutcome("failure")
                    .error(ex)
                    .field("file.path", sidecar.toString())
                    .field("log.source", "application")
                    .log();
            }
        });
    }

    // === error classification shared by the cold-fill and cold-head paths ===

    private static boolean isValueNotFound(final Throwable err) {
        Throwable cause = err;
        while (cause != null) {
            if (cause instanceof ValueNotFoundException) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    private static boolean isVanished(final Throwable err) {
        Throwable cause = err;
        while (cause != null) {
            if (cause instanceof java.nio.file.NoSuchFileException || cause instanceof ValueNotFoundException) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    private static String etagOf(final Meta meta) {
        return meta.read(Meta.OP_MD5).map(Object::toString).orElse(null);
    }

    /**
     * {@link Meta} view over a {@link StorageIndex.Entry}, mirroring how
     * {@code S3HeadMeta} exposes a raw HEAD response -- so callers see the
     * same {@link Meta#OP_SIZE}/{@link Meta#OP_MD5} contract regardless of
     * whether the answer came from the index or a fresh blob-store HEAD.
     *
     * @since 2.3.0
     */
    private static final class IndexMeta implements Meta {

        /**
         * Backing entry.
         */
        private final StorageIndex.Entry entry;

        IndexMeta(final StorageIndex.Entry entry) {
            this.entry = entry;
        }

        @Override
        public <T> T read(final ReadOperator<T> opr) {
            final Map<String, String> raw = new HashMap<>();
            Meta.OP_SIZE.put(raw, this.entry.size());
            if (this.entry.etag() != null) {
                Meta.OP_MD5.put(raw, this.entry.etag());
            }
            return opr.take(raw);
        }
    }

    /**
     * Tuning knobs for the WS1.2 write-back queue (spec &sect;3.C). Only
     * consulted when {@code writeThrough=false} (the default) -- mirrored by
     * {@code S3StorageFactory}'s {@code cache.write-back-*} config keys,
     * which fall back to {@link #defaults()} per field when unset.
     *
     * @param queueCapacity High-water mark for concurrently in-flight
     *  (enqueued + retrying) write-back uploads. {@link #save} rejects with
     *  {@link WriteBackSaturatedException} once this many admissions are
     *  outstanding, checked before any disk write.
     * @param uploaderThreads Size of the dedicated daemon uploader pool
     *  ({@code pantera-storage-writeback-*}) draining the queue.
     * @param maxRetries Retry attempts after the first failed {@link
     *  BlobStore#put} before an upload is dead-lettered (left {@code
     *  PENDING_WRITE} for the next boot replay to retry).
     * @param baseBackoffMillis Backoff before the first retry; doubles per
     *  attempt up to {@link #maxBackoffMillis} (mirrors {@code DbConsumer}'s
     *  dead-letter/backoff philosophy).
     * @param maxBackoffMillis Backoff ceiling.
     * @param retryAfterSeconds {@code Retry-After} hint carried on {@link
     *  WriteBackSaturatedException} for callers that surface it as an HTTP
     *  503.
     * @since 2.3.0
     */
    public record WriteBackConfig(
        int queueCapacity,
        int uploaderThreads,
        int maxRetries,
        long baseBackoffMillis,
        long maxBackoffMillis,
        long retryAfterSeconds
    ) {
        /**
         * Hardcoded defaults, mirrored by {@code S3StorageFactory} when a
         * {@code cache.write-back-*} key is unset.
         *
         * @return Default write-back configuration.
         */
        public static WriteBackConfig defaults() {
            return new WriteBackConfig(1024, 4, 5, 500L, 30_000L, 5L);
        }
    }
}
