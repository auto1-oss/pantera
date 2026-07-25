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
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
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
 * <h2>Write path (write-through, for now)</h2>
 * <p>{@link #save(Key, Content)} writes to disk, computes a digest once from
 * that just-written file, uploads durably to the blob store, then updates the
 * index -- synchronous write-through. Async durable write-back with a
 * persistent queue is WS1.2, explicitly out of scope here. {@link
 * #delete(Key)} removes from the blob store (the durability tier) first, then
 * best-effort cleans up the local disk copy and index entry.</p>
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
     * New cached blob storage.
     *
     * <p>Performs a blocking boot-time {@link StorageIndex#rebuildFromDisk}
     * scan of {@code diskRoot} -- call this constructor only from a boot
     * thread (storage-factory construction), never from the Vert.x event
     * loop, per CLAUDE.md's thread model.</p>
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
        this.blobStore = blobStore;
        this.disk = new FileStorage(diskRoot);
        this.index = new StorageIndex();
        this.freshnessTtl = freshnessTtl;
        this.negativeTtl = negativeTtl;
        this.id = "CachedBlobStorage: " + blobStore.identifier();
        try {
            Files.createDirectories(diskRoot);
        } catch (final IOException err) {
            throw new PanteraIOException(err);
        }
        this.index.rebuildFromDisk(diskRoot);
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
        return this.disk.save(key, content)
            .thenCompose(ignored -> this.digestWrittenFile(key))
            .thenCompose(digest -> this.uploadWrittenFile(key, digest));
    }

    @Override
    public CompletableFuture<Void> move(final Key source, final Key destination) {
        return this.value(source)
            .thenCompose(content -> this.save(destination, content))
            .thenCompose(ignored -> this.delete(source));
    }

    @Override
    public CompletableFuture<Void> delete(final Key key) {
        return this.blobStore.delete(key)
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

    // === save(): write-through (WS1.2 will add async write-back) ===

    private CompletableFuture<String> digestWrittenFile(final Key key) {
        return OptimizedStorageCache.optimizedValue(this.disk, key)
            .thenCompose(body -> new ContentDigest(body, Digests.SHA256).hex().toCompletableFuture());
    }

    private CompletableFuture<Void> uploadWrittenFile(final Key key, final String digest) {
        return OptimizedStorageCache.optimizedValue(this.disk, key)
            .thenCompose(body -> this.blobStore.put(key, body))
            .thenCompose(ignored -> this.finalizeDiskWrite(key, digest));
    }

    private CompletableFuture<Void> finalizeDiskWrite(final Key key, final String digest) {
        return this.disk.metadata(key).thenAccept(meta -> {
            final long size = new MetaCommon(meta).size();
            final long now = System.currentTimeMillis();
            this.index.putPresent(key, size, null, digest, true);
            this.writeSidecarBestEffort(key, StorageIndex.Entry.present(size, null, digest, now, true));
        });
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
}
