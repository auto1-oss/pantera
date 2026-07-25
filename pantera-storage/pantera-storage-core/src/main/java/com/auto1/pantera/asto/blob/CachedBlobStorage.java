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
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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
 * <h2>Cross-node coherence (WS1.5, spec &sect;3.E)</h2>
 * <p>A commit (write-through OR write-back landing durably) or a delete
 * publishes {@code key + digest + commit-time} on {@link
 * #invalidationBus} -- {@link StorageInvalidationBus#NOOP} by default (pure
 * single-instance mode; every existing constructor call site keeps this),
 * or a real bus wired in by the caller (e.g. {@code S3StorageFactory} reads
 * one from {@code StorageInvalidationBusRegistry} when clustering is
 * configured). A peer instance that has this key cached locally drops its
 * disk+index entry on receipt so the NEXT access re-resolves it -- this is
 * what lets the "trust the local disk copy for {@code freshnessTtl}, never
 * inline-HEAD" read path above be safe across nodes: event-driven
 * invalidation replaces per-read validation, {@code freshnessTtl} remains
 * only the backstop for the window before a message arrives (or is lost).
 * See {@link #onPeerInvalidate} for the two races this must not get wrong
 * (a concurrent local write-back for the same key; a stale message
 * reordered behind a newer local write) and {@link StorageInvalidationToken}
 * for the wire format.</p>
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
     * Root of {@link #disk}'s cache directory -- kept alongside {@link
     * #disk} so {@link #writeSidecarBestEffort} can stage its atomic-move
     * temp files under the SAME top-level {@code .tmp} directory {@link
     * FileStorage#save} already uses for its own atomic writes, rather than
     * creating a proliferation of per-shard-leaf {@code .tmp} directories.
     */
    private final Path diskRoot;

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
     * WS1.4 eviction/admission-control tuning (spec &sect;3.D). Consulted on
     * every {@link #save} before any disk write.
     */
    private final EvictionConfig evictionConfig;

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
     * WS1.5 (spec &sect;3.E) cross-node coherence bus. {@link
     * StorageInvalidationBus#NOOP} for every constructor that does not
     * receive one explicitly -- preserves pre-WS1.5 behaviour exactly.
     */
    private final StorageInvalidationBus invalidationBus;

    /**
     * WS1.5 namespace this instance's {@link #invalidationBus} messages are
     * scoped to: {@link #diskRoot}'s string form. Several {@link
     * CachedBlobStorage} instances (one per repository configured with
     * {@code cache.mode: index}) can share ONE process-wide bus/channel;
     * this is how each one ignores every OTHER repository's traffic before
     * touching its own {@link #index} -- see {@link #onPeerInvalidate}.
     */
    private final String invalidationNamespace;

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
     * @param evictionConfig WS1.4 eviction/admission-control tuning (spec
     *  &sect;3.D): hard disk-bytes bound plus high/low watermarks.
     * @param invalidationBus WS1.5 cross-node coherence bus (spec &sect;3.E);
     *  {@code null} is treated identically to {@link StorageInvalidationBus#NOOP}.
     */
    public CachedBlobStorage(
        final BlobStore blobStore,
        final Path diskRoot,
        final Duration freshnessTtl,
        final Duration negativeTtl,
        final boolean writeThrough,
        final WriteBackConfig writeBackConfig,
        final EvictionConfig evictionConfig,
        final StorageInvalidationBus invalidationBus
    ) {
        this.blobStore = blobStore;
        this.disk = new FileStorage(diskRoot);
        this.diskRoot = diskRoot;
        this.index = new StorageIndex();
        this.freshnessTtl = freshnessTtl;
        this.negativeTtl = negativeTtl;
        this.writeThrough = writeThrough;
        this.writeBackConfig = writeBackConfig;
        this.evictionConfig = evictionConfig;
        this.invalidationBus = invalidationBus == null ? StorageInvalidationBus.NOOP : invalidationBus;
        this.invalidationNamespace = diskRoot.toString();
        this.id = "CachedBlobStorage: " + blobStore.identifier();
        try {
            Files.createDirectories(diskRoot);
        } catch (final IOException err) {
            throw new PanteraIOException(err);
        }
        this.index.rebuildFromDisk(diskRoot, dataFile -> CacheKeyShard.fromDiskPath(diskRoot, dataFile));
        if (writeThrough) {
            this.writeBackAdmission = null;
            this.writeBackUploaders = null;
        } else {
            this.writeBackAdmission = new Semaphore(writeBackConfig.queueCapacity());
            this.writeBackUploaders = CachedBlobStorage.newUploaderPool(writeBackConfig.uploaderThreads());
            this.replayPendingWrites();
        }
        this.invalidationBus.onInvalidate(this::onPeerInvalidate);
    }

    /**
     * Convenience constructor for callers that do not (yet) wire a WS1.5
     * cross-node coherence bus -- delegates with {@link
     * StorageInvalidationBus#NOOP}, preserving pre-WS1.5 behaviour exactly.
     *
     * @param blobStore Durable cold tier.
     * @param diskRoot Local disk cache directory (created if absent).
     * @param freshnessTtl How long a confirmed-{@code PRESENT} disk copy is
     *  trusted without re-validation.
     * @param negativeTtl How long a confirmed blob-store miss is remembered.
     * @param writeThrough {@code true} for the pre-WS1.2 synchronous
     *  write-through save path; {@code false} for WS1.2 async write-back.
     * @param writeBackConfig Write-back tuning; ignored when {@code writeThrough}.
     * @param evictionConfig WS1.4 eviction/admission-control tuning.
     */
    public CachedBlobStorage(
        final BlobStore blobStore,
        final Path diskRoot,
        final Duration freshnessTtl,
        final Duration negativeTtl,
        final boolean writeThrough,
        final WriteBackConfig writeBackConfig,
        final EvictionConfig evictionConfig
    ) {
        this(
            blobStore, diskRoot, freshnessTtl, negativeTtl, writeThrough, writeBackConfig, evictionConfig,
            StorageInvalidationBus.NOOP
        );
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
        this(blobStore, diskRoot, freshnessTtl, negativeTtl, true, WriteBackConfig.defaults(), EvictionConfig.defaults());
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
     * Test/observability accessor: whether {@code key} is currently cached
     * on the LOCAL disk tier -- distinct from {@link #exists(Key)}, which is
     * also {@code true} for a key durably confirmed in the blob store but
     * since evicted from the local disk cache (eviction is a local-cache
     * housekeeping concern, never a deletion of the durable copy). Used to
     * assert WS1.4 eviction outcomes without conflating "evicted from disk"
     * with "no longer exists at all". Package-visible only.
     *
     * @param key Key.
     * @return {@code true} iff the index holds a present-on-disk entry for {@code key}.
     */
    boolean isCachedOnDisk(final Key key) {
        return this.index.knownEntry(key).map(StorageIndex.Entry::presentOnDisk).orElse(false);
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

    /**
     * Current running total of bytes occupied by the disk tier, per the
     * in-memory {@link StorageIndex} counter -- never a directory walk (WS1.4,
     * spec &sect;3.D). Package-visible: test/observability accessor.
     *
     * @return Disk bytes currently accounted for.
     */
    long diskBytesUsed() {
        return this.index.diskBytesUsed();
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
            .thenRun(() -> this.completeDelete(key))
            .thenRun(() -> {
                // WS1.5 (spec sect 3.E): only publish when this delete
                // actually removed a durably-confirmed copy. A PENDING_WRITE
                // key was never seen as PRESENT anywhere else -- publishing a
                // tombstone for it could wrongly evict an UNRELATED, still
                // valid PRESENT entry a peer holds for this same key (e.g. a
                // genuinely older or concurrently-written version this node
                // never finished uploading).
                if (!pendingUpload) {
                    this.publishDeleteInvalidation(key);
                }
            });
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
            .flatMap(entry -> this.disk.pathFor(this.diskKey(key)));
    }

    /**
     * Translates a logical key to the sharded on-disk key that actually
     * addresses {@link #disk} -- the single choke point every disk-facing
     * call in this class goes through, so the write path (here), the read
     * path, sidecar persistence, and eviction can never drift into
     * addressing the disk tier with two different mappings for the same
     * logical key (spec &sect;3.D).
     *
     * @param key Logical key.
     * @return Physical (sharded) key for {@link #disk}.
     */
    private Key diskKey(final Key key) {
        return CacheKeyShard.toDiskKey(key);
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
        return this.disk.save(this.diskKey(key), content).thenCompose(ignored -> this.finalizeDiskWrite(key, null));
    }

    private CompletableFuture<Content> readFromDisk(final Key key) {
        return OptimizedStorageCache.optimizedValue(this.disk, this.diskKey(key)).handle(
            (content, err) -> {
                final CompletableFuture<Content> result;
                if (err == null) {
                    // WS1.4: record the disk hit for the LRU/LFU eviction
                    // policy -- in-memory only (spec sect 3.D). Deliberately
                    // NOT also persisted to the sidecar here: a per-read
                    // background sidecar rewrite would race any concurrent
                    // reader of the SAME sidecar (most notably another
                    // instance's StorageIndex#rebuildFromDisk boot scan),
                    // and race @TempDir cleanup in tests. hits/lastAccess
                    // ARE still persisted -- at the existing write-time
                    // sidecar writes (finalizeDiskWrite/enqueuePendingWrite)
                    // -- so a restart recovers "coldness as of last write",
                    // a documented, acceptable trade-off versus "coldness as
                    // of last read" (see docs/admin-guide/storage-backends.md).
                    this.index.recordAccess(key);
                    result = CompletableFuture.completedFuture(content);
                } else {
                    result = this.recoverVanishedDiskEntry(key, err);
                }
                return result;
            }
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
        return OptimizedStorageCache.optimizedValue(this.disk, this.diskKey(key))
            .thenCompose(body -> new ContentDigest(body, Digests.SHA256).hex().toCompletableFuture());
    }

    private CompletableFuture<Long> diskSize(final Key key) {
        return this.disk.metadata(this.diskKey(key)).thenApply(meta -> new MetaCommon(meta).size());
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
        return this.admitWrite(key, content.size().orElse(0L))
            .thenCompose(ignored -> this.disk.save(this.diskKey(key), content))
            .thenCompose(ignored -> this.digestWrittenFile(key))
            .thenCompose(digest -> this.uploadWrittenFile(key, digest));
    }

    private CompletableFuture<Void> uploadWrittenFile(final Key key, final String digest) {
        return OptimizedStorageCache.optimizedValue(this.disk, this.diskKey(key))
            .thenCompose(body -> this.blobStore.put(key, body))
            .thenCompose(ignored -> this.finalizeDiskWrite(key, digest))
            .thenRun(() -> this.publishCommitInvalidation(key));
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
        return this.admitWrite(key, content.size().orElse(0L))
            .thenCompose(ignored -> this.disk.save(this.diskKey(key), content))
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
                final Content body = OptimizedStorageCache.optimizedValue(this.disk, this.diskKey(pending.key())).join();
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
            this.publishCommitInvalidation(pending.key());
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

    // === WS1.4: index-driven eviction + hard admission control (spec sect 3.D) ===

    /**
     * Hard admission control, checked BEFORE any disk write (mirroring the
     * write-back {@link #writeBackAdmission} gate's discipline): if the
     * incoming write would push disk usage past {@link
     * EvictionConfig#highWatermarkBytes()}, evict the coldest eligible
     * candidates first -- proactively down to {@link
     * EvictionConfig#lowWatermarkBytes()} (or exactly as far as needed to
     * admit this write, whichever is less aggressive) -- then re-check the
     * HARD bound ({@link EvictionConfig#maxDiskBytes()}) and reject with
     * {@link CacheAdmissionRejectedException} if it still would not fit
     * (e.g. the content itself exceeds the bound, or every other entry is
     * pinned {@code PENDING_WRITE}). {@code incomingBytes} of {@code 0}
     * (unknown-size content) skips the pre-write check entirely -- eviction
     * still happens on the NEXT write once the actual size is reflected in
     * the running counter, a documented limitation for size-unknown streams.
     *
     * @param key Key about to be written (for the rejection message only).
     * @param incomingBytes Size of the content about to be written, or
     *  {@code 0} if unknown.
     * @return Future completing once admission is granted; fails with
     *  {@link CacheAdmissionRejectedException} otherwise.
     */
    private CompletableFuture<Void> admitWrite(final Key key, final long incomingBytes) {
        final CompletableFuture<Void> result;
        if (this.evictionConfig.maxDiskBytes() <= 0 || incomingBytes <= 0) {
            result = CompletableFuture.completedFuture(null);
        } else {
            final long projected = this.index.diskBytesUsed() + incomingBytes;
            final CompletableFuture<Void> afterEviction = projected > this.evictionConfig.highWatermarkBytes()
                ? this.evictDownTo(Math.min(
                    this.evictionConfig.lowWatermarkBytes(),
                    this.evictionConfig.maxDiskBytes() - incomingBytes
                ))
                : CompletableFuture.completedFuture(null);
            result = afterEviction.thenCompose(ignored -> this.enforceHardBound(key, incomingBytes));
        }
        return result;
    }

    private CompletableFuture<Void> enforceHardBound(final Key key, final long incomingBytes) {
        final CompletableFuture<Void> result;
        if (this.index.diskBytesUsed() + incomingBytes > this.evictionConfig.maxDiskBytes()) {
            result = CompletableFuture.failedFuture(
                new CacheAdmissionRejectedException(key.string(), incomingBytes, this.evictionConfig.maxDiskBytes())
            );
        } else {
            result = CompletableFuture.completedFuture(null);
        }
        return result;
    }

    /**
     * Evicts the coldest eligible candidates (by {@link #evictionConfig}'s
     * policy) one at a time, sequentially, until {@link
     * StorageIndex#diskBytesUsed()} is at or below {@code targetBytes} or
     * there are no more eligible candidates (every remaining entry is
     * {@code PENDING_WRITE} and therefore never selected -- acceptance #5's
     * second half). Sequential rather than parallel: simplicity over
     * eviction throughput, matching this cache tier's "best-effort
     * accelerator, not the hot path" role.
     *
     * @param targetBytes Stop evicting once usage is at or below this.
     * @return Future completing once no further eviction is possible or needed.
     */
    private CompletableFuture<Void> evictDownTo(final long targetBytes) {
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        if (this.index.diskBytesUsed() > targetBytes) {
            final List<Map.Entry<Key, StorageIndex.Entry>> candidates = this.index.evictionCandidates();
            candidates.sort(this.evictionComparator());
            for (final Map.Entry<Key, StorageIndex.Entry> candidate : candidates) {
                chain = chain.thenCompose(ignored -> this.evictOneIfStillOverTarget(candidate.getKey(), targetBytes));
            }
        }
        return chain;
    }

    private CompletableFuture<Void> evictOneIfStillOverTarget(final Key key, final long targetBytes) {
        return this.index.diskBytesUsed() > targetBytes
            ? this.evictEntry(key)
            : CompletableFuture.completedFuture(null);
    }

    /**
     * Coldest-first comparator per {@link EvictionConfig#policy()}: LRU
     * orders by {@link StorageIndex.Entry#lastAccessEpochMilli()} ascending
     * (oldest access first); LFU orders by {@link
     * StorageIndex.Entry#hits()} ascending, breaking ties by {@code
     * lastAccessEpochMilli} -- the same tie-break {@code DiskCacheStorage}
     * used for parity.
     *
     * @return Comparator ordering the coldest candidate first.
     */
    private Comparator<Map.Entry<Key, StorageIndex.Entry>> evictionComparator() {
        final Comparator<Map.Entry<Key, StorageIndex.Entry>> comparator;
        if (this.evictionConfig.policy() == EvictionPolicy.LFU) {
            comparator = Comparator.<Map.Entry<Key, StorageIndex.Entry>>comparingLong(candidate -> candidate.getValue().hits())
                .thenComparingLong(candidate -> candidate.getValue().lastAccessEpochMilli());
        } else {
            comparator = Comparator.comparingLong(candidate -> candidate.getValue().lastAccessEpochMilli());
        }
        return comparator;
    }

    /**
     * Evicts a single entry: removes it from the index FIRST (so a
     * concurrent {@link #value} for this key sees "unknown" and cold-fills
     * cleanly from the blob store, rather than racing a vanishing disk file
     * through the {@link #recoverVanishedDiskEntry} TOCTOU path), then
     * best-effort deletes its disk file and sidecar.
     *
     * <p><strong>Accepted narrow race</strong> (same disclosure style as
     * {@link #delete(Key)}'s javadoc): if a concurrent cold-fill for this
     * exact key lands a fresh file between this method's index removal and
     * its disk delete, that fresh file can be deleted here too, forcing one
     * extra cold-fill on the next read. This cache tier is a best-effort
     * accelerator, not the source of truth (the blob store is), so this is
     * accepted rather than engineered around.</p>
     *
     * @param key Key to evict.
     * @return Future completing once the disk file and sidecar are removed
     *  (or confirmed absent).
     */
    private CompletableFuture<Void> evictEntry(final Key key) {
        this.index.remove(key);
        this.deleteSidecarBestEffort(key);
        final Key onDisk = this.diskKey(key);
        return this.disk.exists(onDisk)
            .thenCompose(present -> present ? this.disk.delete(onDisk) : CompletableFuture.completedFuture(null))
            .exceptionally(err -> {
                EcsLogger.debug("com.auto1.pantera.asto.blob")
                    .message("Best-effort eviction delete failed; index entry is already dropped")
                    .eventCategory("file")
                    .eventAction("cache_eviction")
                    .eventOutcome("failure")
                    .error(CachedBlobStorage.rootCause(err))
                    .field("file.path", key.string())
                    .field("log.source", "application")
                    .log();
                return null;
            });
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
        final Key onDisk = this.diskKey(key);
        return this.disk.exists(onDisk).thenCompose(
            present -> present ? this.disk.delete(onDisk) : CompletableFuture.completedFuture(null)
        );
    }

    private void completeDelete(final Key key) {
        this.index.remove(key);
        this.deleteSidecarBestEffort(key);
    }

    // === WS1.5: cross-node coherence publish/receive (spec sect 3.E) ===

    /**
     * Publishes a commit invalidation for {@code key} after a write-through
     * OR write-back upload lands durably (both funnel here via {@link
     * #uploadWrittenFile} and {@link #onUploadSuccess} respectively) -- reads
     * the JUST-updated {@link #index} entry rather than recomputing anything
     * separately, so the published {@link StorageInvalidationToken} always
     * reflects EXACTLY what this node itself now believes, never a value
     * that could race the index write.
     *
     * @param key Key that was just committed durably.
     */
    private void publishCommitInvalidation(final Key key) {
        this.index.knownEntry(key).ifPresent(
            entry -> this.publishInvalidation(key, entry.digest(), entry.lastModifiedEpochMilli())
        );
    }

    /**
     * Publishes a delete tombstone for {@code key} -- see {@link #delete(Key)}'s
     * call site for why this is only invoked when the delete actually
     * removed a durably-confirmed blob-store copy.
     *
     * @param key Key that was just deleted.
     */
    private void publishDeleteInvalidation(final Key key) {
        this.publishInvalidation(key, null, System.currentTimeMillis());
    }

    private void publishInvalidation(final Key key, final String digest, final long committedAtEpochMilli) {
        final String token = new StorageInvalidationToken(this.invalidationNamespace, digest, committedAtEpochMilli).encode();
        this.invalidationBus.publish(key, token);
        EcsLogger.debug("com.auto1.pantera.asto.blob")
            .message("Published cross-node cache invalidation")
            .eventCategory("file")
            .eventAction("storage_invalidation_publish")
            .eventOutcome("success")
            .field("file.path", key.string())
            .field("log.source", "application")
            .log();
    }

    /**
     * Invoked when a peer node publishes a cross-node invalidation on {@link
     * #invalidationBus} (spec &sect;3.E). Registered once, in the
     * constructor, against EVERY message the bus delivers -- including
     * messages published by OTHER {@link CachedBlobStorage} instances
     * sharing the same process-wide bus for a DIFFERENT repository/cache
     * namespace, which is why the very first check is the {@link
     * #invalidationNamespace} match: a shared bus multiplexes every repo's
     * coherence traffic over one channel, so each listener must silently
     * ignore messages that are not about its OWN local disk-cache directory
     * before doing anything else.
     *
     * <p>Two races this method is specifically responsible for NOT getting
     * wrong (spec &sect;3.E):</p>
     * <ul>
     *   <li><strong>A concurrent local write-back upload for the SAME
     *   key.</strong> If the local entry is currently {@code PENDING_WRITE},
     *   this node's own uploader thread may be mid-flight reading THIS EXACT
     *   disk file to upload it (see {@link #uploadWithRetry}) -- dropping
     *   the disk file here out from under it would corrupt that in-flight
     *   upload. The peer's message is ignored unconditionally in this case,
     *   regardless of what it claims.</li>
     *   <li><strong>A stale message reordered behind a newer local
     *   write.</strong> The token's {@code committedAtEpochMilli} is
     *   compared against the local entry's OWN {@link
     *   StorageIndex.Entry#lastModifiedEpochMilli()}: a message that is not
     *   STRICTLY newer than what this node already recorded locally is a
     *   delayed echo of a write this node has already superseded (by its own
     *   later write, or by processing a later message first) and must not
     *   evict a newer local copy. A content digest gives no such ordering
     *   (two independent writes just have two unrelated hashes), which is
     *   why {@link StorageInvalidationToken} carries a timestamp instead.</li>
     * </ul>
     *
     * @param key Key a peer committed or deleted.
     * @param rawToken Encoded {@link StorageInvalidationToken}.
     */
    private void onPeerInvalidate(final Key key, final String rawToken) {
        final Optional<StorageInvalidationToken> parsed = StorageInvalidationToken.decode(rawToken);
        if (parsed.isEmpty() || !this.invalidationNamespace.equals(parsed.get().namespace())) {
            return;
        }
        final Optional<StorageIndex.Entry> local = this.index.knownEntry(key);
        if (local.isEmpty()) {
            return;
        }
        final StorageIndex.Entry entry = local.get();
        final StorageInvalidationToken token = parsed.get();
        if (entry.pendingUpload()) {
            this.logInvalidationIgnored(key, "pending_write_in_flight");
        } else if (token.committedAtEpochMilli() <= entry.lastModifiedEpochMilli()) {
            this.logInvalidationIgnored(key, "superseded_by_local_write");
        } else {
            this.dropLocalEntryBestEffort(key);
            EcsLogger.info("com.auto1.pantera.asto.blob")
                .message("Applied cross-node cache invalidation: dropped local disk+index entry")
                .eventCategory("file")
                .eventAction("storage_invalidation_apply")
                .eventOutcome("success")
                .field("file.path", key.string())
                .field("log.source", "application")
                .log();
        }
    }

    private void logInvalidationIgnored(final Key key, final String reason) {
        EcsLogger.debug("com.auto1.pantera.asto.blob")
            .message("Ignored cross-node cache invalidation: " + reason)
            .eventCategory("file")
            .eventAction("storage_invalidation_ignore")
            .eventOutcome("success")
            .field("event.reason", reason)
            .field("file.path", key.string())
            .field("log.source", "application")
            .log();
    }

    /**
     * Drops {@code key}'s local disk+index entry so the NEXT access
     * re-resolves it via a fresh cold fill from the blob store (spec
     * &sect;3.E) -- mirrors {@link #completeDelete(Key)} plus a best-effort
     * disk cleanup, but is never called from {@link #delete(Key)} itself
     * (that path already does its own blob-store-first removal).
     *
     * <p>Deliberately does NOT reuse {@link #deleteDiskCopyBestEffort(Key)}
     * (which goes through {@code FileStorage#delete}): that call also
     * recursively removes now-empty parent directories, all the way up to
     * the shared {@code .tmp} staging directory {@link
     * #writeSidecarBestEffort} and every {@code FileStorage#save} use for
     * atomic writes. An invalidation-drop is, by construction, immediately
     * followed by exactly the write it would race -- the NEXT access to
     * this SAME key cold-fills it right back, creating a fresh temp file
     * under {@code .tmp} at any moment. A raw {@link Files#deleteIfExists}
     * achieves everything this path needs (the stale bytes are gone, so a
     * boot-time {@link StorageIndex#rebuildFromDisk} scan can never
     * resurrect them as a valid entry from bare filesystem attributes)
     * without ever touching the shared staging directory.</p>
     *
     * @param key Key to drop locally.
     */
    private void dropLocalEntryBestEffort(final Key key) {
        this.index.remove(key);
        this.deleteSidecarBestEffort(key);
        this.disk.pathFor(this.diskKey(key)).ifPresent(path -> {
            try {
                Files.deleteIfExists(path);
            } catch (final IOException ex) {
                EcsLogger.debug("com.auto1.pantera.asto.blob")
                    .message("Best-effort disk cleanup after cross-node invalidation failed")
                    .eventCategory("file")
                    .eventAction("storage_invalidation_apply")
                    .eventOutcome("failure")
                    .error(ex)
                    .field("file.path", key.string())
                    .field("log.source", "application")
                    .log();
            }
        });
    }

    // === sidecar persistence (feeds StorageIndex#rebuildFromDisk on boot) ===

    /**
     * Writes {@code entry}'s sidecar via a temp-file-then-atomic-move,
     * exactly like {@link FileStorage}'s own data-file writes -- NOT a
     * direct in-place {@code Properties.store}. This matters because {@link
     * #persistAccessSidecarBestEffort} fires a sidecar rewrite on every
     * disk-served read, fully concurrently with any other reader of that
     * same key (including another node's or another instance's {@link
     * StorageIndex#rebuildFromDisk} boot scan); an in-place write truncates
     * the file before rewriting it, so a concurrent reader can observe a
     * momentarily-empty/corrupt sidecar and silently fall back to zeroed
     * defaults (the exact failure mode this method exists to rule out).
     *
     * @param key Logical key.
     * @param entry Entry to persist.
     */
    private void writeSidecarBestEffort(final Key key, final StorageIndex.Entry entry) {
        this.disk.pathFor(this.diskKey(key)).ifPresent(dataPath -> {
            final Path sidecar = Path.of(dataPath + StorageIndex.SIDECAR_SUFFIX);
            // Staged under the SAME top-level ".tmp" directory FileStorage#save
            // already uses -- the identical name StorageIndex#rebuildFromDisk's
            // boot scan already excludes (isCacheableDataFile), so an
            // in-flight temp file can never be misread as a cache data file
            // even if a walk catches it mid-write.
            final Path tmpDir = this.diskRoot.resolve(StorageIndex.STAGING_DIR);
            final Path tmp = tmpDir.resolve(UUID.randomUUID().toString());
            try {
                Files.createDirectories(tmpDir);
                StorageIndex.Sidecar.write(tmp, entry);
                Files.move(tmp, sidecar, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (final IOException ex) {
                CachedBlobStorage.deleteQuietly(tmp);
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

    private static void deleteQuietly(final Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (final IOException ex) { // NOPMD EmptyCatchBlock - best-effort cleanup of a failed sidecar temp file
            // EXPECTED: cleanup of an already-failed write; nothing more to do.
        }
    }

    private void deleteSidecarBestEffort(final Key key) {
        this.disk.pathFor(this.diskKey(key)).ifPresent(dataPath -> {
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

    /**
     * Coldness policy for WS1.4 eviction candidate ordering (spec &sect;3.D)
     * -- mirrors {@code DiskCacheStorage.Policy} for parity between the two
     * cache modes.
     *
     * @since 2.3.0
     */
    public enum EvictionPolicy {
        /**
         * Evict the entry least recently accessed first.
         */
        LRU,
        /**
         * Evict the entry accessed the fewest times first (ties broken by
         * least-recently-accessed).
         */
        LFU
    }

    /**
     * Tuning knobs for the WS1.4 index-driven eviction and hard admission
     * control (spec &sect;3.D) -- mirrored by {@code S3StorageFactory}'s
     * {@code cache.max-disk-bytes}/{@code cache.eviction-*} config keys,
     * which fall back to {@link #defaults()} per field when unset. Units and
     * high/low-watermark semantics mirror {@code DiskCacheStorage} exactly,
     * so an operator already familiar with disk-cache-mode tuning does not
     * need to learn a new vocabulary for index-cache mode.
     *
     * @param maxDiskBytes Hard bound on total disk-tier bytes. {@link #save}
     *  never lets this be exceeded: it evicts synchronously first and
     *  rejects with {@link CacheAdmissionRejectedException} if it still
     *  cannot fit. {@code <= 0} disables eviction/admission entirely (the
     *  disk directory is unbounded).
     * @param highWatermarkPercent Percentage of {@link #maxDiskBytes} at
     *  which a write proactively triggers eviction (before the hard bound is
     *  actually reached).
     * @param lowWatermarkPercent Percentage of {@link #maxDiskBytes} eviction
     *  targets when triggered -- evicting further than the immediate write
     *  requires, to avoid evicting on almost every subsequent write.
     * @param policy Coldest-candidate-first ordering: {@link
     *  EvictionPolicy#LRU} or {@link EvictionPolicy#LFU}.
     * @since 2.3.0
     */
    public record EvictionConfig(
        long maxDiskBytes,
        int highWatermarkPercent,
        int lowWatermarkPercent,
        EvictionPolicy policy
    ) {
        /**
         * Hardcoded defaults, mirrored by {@code S3StorageFactory} when a
         * {@code cache.max-disk-bytes}/{@code cache.eviction-*} key is unset
         * -- the same 10 GiB / 90% / 80% / LRU defaults {@code
         * DiskCacheStorage} uses.
         *
         * @return Default eviction configuration.
         */
        public static EvictionConfig defaults() {
            return new EvictionConfig(10L * 1024 * 1024 * 1024, 90, 80, EvictionPolicy.LRU);
        }

        /**
         * Disk-bytes threshold at which a write proactively triggers
         * eviction.
         *
         * @return {@link #maxDiskBytes} * {@link #highWatermarkPercent} / 100.
         */
        public long highWatermarkBytes() {
            return this.maxDiskBytes * this.highWatermarkPercent / 100L;
        }

        /**
         * Disk-bytes target eviction proactively works down to once triggered.
         *
         * @return {@link #maxDiskBytes} * {@link #lowWatermarkPercent} / 100.
         */
        public long lowWatermarkBytes() {
            return this.maxDiskBytes * this.lowWatermarkPercent / 100L;
        }
    }
}
