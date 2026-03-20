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
package com.auto1.pantera.asto.s3;

import com.auto1.pantera.asto.PanteraIOException;
import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Meta;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.log.EcsLogger;
import hu.akarnokd.rxjava2.interop.SingleInterop;
import io.reactivex.Flowable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.reactivestreams.Subscriber;

/**
 * Read-through on-disk cache for downloads from underlying {@link Storage}.
 *
 * - Streams data to caller while persisting to disk, to avoid full buffering.
 * - Validates cache entries against remote ETag/size before serving (configurable).
 * - Scheduled cleanup with LRU/LFU eviction; high/low watermarks.
 * - Uses shared executor service to prevent thread proliferation.
 */
final class DiskCacheStorage extends Storage.Wrap implements AutoCloseable {

    enum Policy { LRU, LFU }

    /**
     * Pool name for metrics identification.
     */
    static final String POOL_NAME = "pantera.asto.s3.cache";

    /**
     * Shared executor service for all cache cleanup tasks.
     * Uses bounded thread pool to prevent thread proliferation.
     * Pool name: {@value #POOL_NAME} (visible in thread dumps and metrics).
     */
    private static final ScheduledExecutorService SHARED_CLEANER = 
        Executors.newScheduledThreadPool(
            Math.max(2, Runtime.getRuntime().availableProcessors() / 4),
            r -> {
                final Thread t = new Thread(r, POOL_NAME + ".cleaner");
                t.setDaemon(true);
                t.setPriority(Thread.MIN_PRIORITY);
                return t;
            }
        );

    /**
     * Striped locks for metadata updates to avoid string interning anti-pattern.
     */
    private static final int LOCK_STRIPES = 256;
    private static final Object[] LOCKS = new Object[LOCK_STRIPES];
    
    static {
        for (int i = 0; i < LOCK_STRIPES; i++) {
            LOCKS[i] = new Object();
        }
    }

    private final Path root;
    private final long maxBytes;
    private final Policy policy;
    private final long intervalMillis;
    private final int highPct;
    private final int lowPct;
    private final boolean validateOnRead;
    private final String namespace; // per-storage namespace directory (sha1 of identifier)
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final java.util.concurrent.Future<?> cleanupTask;

    DiskCacheStorage(
        final Storage delegate,
        final Path root,
        final long maxBytes,
        final Policy policy,
        final long intervalMillis,
        final int highPct,
        final int lowPct,
        final boolean validateOnRead
    ) {
        super(delegate);
        this.root = Objects.requireNonNull(root);
        this.maxBytes = maxBytes;
        this.policy = policy;
        this.intervalMillis = intervalMillis;
        this.highPct = highPct;
        this.lowPct = lowPct;
        this.validateOnRead = validateOnRead;
        this.namespace = sha1(delegate.identifier());
        try {
            Files.createDirectories(this.nsRoot());
        } catch (final IOException err) {
            throw new PanteraIOException(err);
        }
        // Clean up orphaned files from previous runs
        this.cleanupOrphanedFiles();
        
        // Schedule periodic cleanup using shared executor
        if (this.intervalMillis > 0) {
            this.cleanupTask = SHARED_CLEANER.scheduleWithFixedDelay(
                this::safeCleanup, 
                this.intervalMillis, 
                this.intervalMillis, 
                TimeUnit.MILLISECONDS
            );
        } else {
            this.cleanupTask = null;
        }
    }
    
    /**
     * Clean up orphaned .part- files, .meta files, and temp files from previous runs.
     * Called on startup to recover from crashes.
     */
    private void cleanupOrphanedFiles() {
        try (java.util.stream.Stream<Path> walk = Files.walk(nsRoot())) {
            walk.filter(Files::isRegularFile)
                .forEach(p -> {
                    try {
                        final String name = p.getFileName().toString();
                        // Delete .part- files older than 1 hour (failed writes)
                        if (name.contains(".part-")) {
                            final long age = System.currentTimeMillis() -
                                Files.getLastModifiedTime(p).toMillis();
                            if (age > 3600_000) { // 1 hour
                                Files.deleteIfExists(p);
                            }
                        }
                        // Delete orphaned .meta files without corresponding data files
                        if (name.endsWith(".meta")) {
                            final Path dataFile = Path.of(p.toString().replace(".meta", ""));
                            if (!Files.exists(dataFile)) {
                                Files.deleteIfExists(p);
                            }
                        }
                        // Delete temp files in .tmp directory older than 1 hour
                        if (p.getParent() != null &&
                            p.getParent().getFileName() != null &&
                            ".tmp".equals(p.getParent().getFileName().toString())) {
                            final long age = System.currentTimeMillis() -
                                Files.getLastModifiedTime(p).toMillis();
                            if (age > 3600_000) { // 1 hour
                                Files.deleteIfExists(p);
                            }
                        }
                    } catch (final IOException ex) {
                        EcsLogger.debug("com.auto1.pantera.asto.cache")
                            .message("Failed to clean up orphaned file")
                            .error(ex)
                            .log();
                    }
                });
        } catch (final IOException ex) {
            EcsLogger.debug("com.auto1.pantera.asto.cache")
                .message("Failed to walk directory for orphan cleanup")
                .error(ex)
                .log();
        }
    }

    @Override
    public CompletableFuture<Content> value(final Key key) {
        final Path file = filePath(key);
        final Path meta = metaPath(key);
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (Files.exists(file) && Files.exists(meta)) {
                    return CacheMeta.read(meta);
                }
            } catch (final IOException ex) {
                // Fall through to fetch on any cache read error
            }
            return null;
        }).thenCompose(cm -> {
            if (cm == null) {
                return this.fetchAndPersist(key, file, meta);
            }
            if (!this.validateOnRead) {
                return CompletableFuture.completedFuture(
                    this.serveCached(file, meta, cm)
                );
            }
            return this.matchRemoteAsync(key, cm).thenCompose(valid -> {
                if (valid) {
                    return CompletableFuture.completedFuture(
                        this.serveCached(file, meta, cm)
                    );
                }
                return this.fetchAndPersist(key, file, meta);
            });
        });
    }

    /**
     * Serve content from local cache and update access metadata in background.
     * @param file Path to cached file
     * @param meta Path to metadata file
     * @param cm Cache metadata
     * @return Cached content
     */
    private Content serveCached(final Path file, final Path meta, final CacheMeta cm) {
        final Content cnt = new Content.From(
            cm.size > 0 ? Optional.of(cm.size) : Optional.empty(),
            filePublisher(file)
        );
        CompletableFuture.runAsync(() -> {
            try {
                synchronized (getLock(meta)) {
                    final CacheMeta updated = CacheMeta.read(meta);
                    updated.hits += 1;
                    updated.lastAccess = Instant.now().toEpochMilli();
                    CacheMeta.write(meta, updated);
                }
            } catch (final IOException ex) {
                EcsLogger.debug("com.auto1.pantera.asto.cache")
                    .message("Failed to update cache metadata after hit")
                    .error(ex)
                    .log();
            }
        });
        return cnt;
    }

    @Override
    public CompletableFuture<Void> save(final Key key, final Content content) {
        // Invalidate cache entry for this key on write
        this.invalidate(key);
        return super.save(key, content);
    }

    @Override
    public CompletableFuture<Void> move(final Key source, final Key destination) {
        this.invalidate(source);
        this.invalidate(destination);
        return super.move(source, destination);
    }

    @Override
    public CompletableFuture<Void> delete(final Key key) {
        this.invalidate(key);
        return super.delete(key);
    }

    private void invalidate(final Key key) {
        try {
            Files.deleteIfExists(filePath(key));
            Files.deleteIfExists(metaPath(key));
        } catch (final IOException ex) {
            EcsLogger.debug("com.auto1.pantera.asto.cache")
                .message("Failed to invalidate cache entry")
                .error(ex)
                .log();
        }
    }

    private CompletableFuture<Content> fetchAndPersist(final Key key, final Path file, final Path meta) {
        // Ensure parent directories exist
        try {
            Files.createDirectories(file.getParent());
        } catch (final IOException err) {
            return CompletableFuture.failedFuture(new PanteraIOException(err));
        }
        // Preload remote metadata (ETag/size) to store alongside
        final CompletableFuture<? extends Meta> remoteMeta = super.metadata(key);
        // Create temp file in .tmp directory at namespace root to avoid exceeding filesystem limits
        // Using parent directory could still exceed 255-byte limit if parent path is long
        final Path tmpDir = this.nsRoot().resolve(".tmp");
        try {
            Files.createDirectories(tmpDir);
        } catch (final IOException err) {
            return CompletableFuture.failedFuture(new PanteraIOException(err));
        }
        final Path tmp = tmpDir.resolve(UUID.randomUUID().toString());
        final CompletableFuture<Content> result = new CompletableFuture<>();
        final CompletableFuture<Content> delegate = super.value(key);
        delegate.whenComplete((cnt, err) -> {
            if (err != null) {
                result.completeExceptionally(err);
                return;
            }
            try {
                final FileChannel ch = FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
                final Flowable<ByteBuffer> stream = Flowable.fromPublisher(cnt)
                    .doOnNext(buf -> {
                        try {
                            ch.write(buf.asReadOnlyBuffer());
                        } catch (final IOException ioe) {
                            throw new PanteraIOException(ioe);
                        }
                    })
                    .doOnComplete(() -> {
                        try {
                            ch.force(true);
                            ch.close();
                            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                            // Write metadata asynchronously to avoid blocking stream completion
                            // If metadata fetch fails/times out, write basic metadata
                            remoteMeta.handle((rm, metaErr) -> {
                                try {
                                    final CacheMeta cm = metaErr == null ? CacheMeta.fromRemote(rm) : new CacheMeta();
                                    if (metaErr != null) {
                                        // Fallback: use file size for metadata
                                        cm.size = Files.size(file);
                                        cm.etag = "";
                                    }
                                    cm.lastAccess = Instant.now().toEpochMilli();
                                    cm.hits = 1;
                                    CacheMeta.write(meta, cm);
                                } catch (final IOException ex) {
                                    EcsLogger.debug("com.auto1.pantera.asto.cache")
                                        .message("Failed to write cache metadata after fetch")
                                        .error(ex)
                                        .log();
                                }
                                return null;
                            });
                        } catch (final IOException ioe) {
                            throw new PanteraIOException(ioe);
                        }
                    })
                    .doOnError(th -> {
                        try { ch.close(); } catch (final IOException ex) {
                            EcsLogger.debug("com.auto1.pantera.asto.cache")
                                .message("Failed to close channel on error")
                                .error(ex)
                                .log();
                        }
                        try { Files.deleteIfExists(tmp); } catch (final IOException ex) {
                            EcsLogger.debug("com.auto1.pantera.asto.cache")
                                .message("Failed to delete temp file on error")
                                .error(ex)
                                .log();
                        }
                    });
                result.complete(new Content.From(cnt.size(), stream));
            } catch (final IOException ioe) {
                result.completeExceptionally(new PanteraIOException(ioe));
            }
        });
        return result;
    }

    /**
     * Asynchronously validates local cache entry against remote metadata.
     * @param key Storage key
     * @param local Local cache metadata
     * @return Future resolving to true if cache entry matches remote, false otherwise
     */
    private CompletableFuture<Boolean> matchRemoteAsync(final Key key, final CacheMeta local) {
        return super.metadata(key)
            .toCompletableFuture()
            .orTimeout(5, TimeUnit.SECONDS)
            .thenApply(meta -> {
                final boolean md5ok = meta.read(Meta.OP_MD5)
                    .map(val -> Objects.equals(val, local.etag))
                    .orElse(false);
                final boolean sizeok = meta.read(Meta.OP_SIZE)
                    .map(val -> Objects.equals(val, local.size))
                    .orElse(false);
                return md5ok && sizeok;
            })
            .exceptionally(err -> false);
    }

    private Path nsRoot() { return this.root.resolve(this.namespace); }
    private Path filePath(final Key key) { return nsRoot().resolve(Paths.get(key.string())); }
    private Path metaPath(final Key key) { return nsRoot().resolve(Paths.get(key.string() + ".meta")); }

    private static Flowable<ByteBuffer> filePublisher(final Path file) {
        return Flowable.generate(() -> FileChannel.open(file, StandardOpenOption.READ), (ch, emitter) -> {
            final ByteBuffer buf = ByteBuffer.allocate(64 * 1024);
            final int read = ch.read(buf);
            if (read < 0) {
                ch.close();
                emitter.onComplete();
            } else if (read == 0) {
                emitter.onComplete();
            } else {
                buf.flip();
                emitter.onNext(buf);
            }
            return ch;
        }, ch -> { try { ch.close(); } catch (final IOException ex) {
            EcsLogger.debug("com.auto1.pantera.asto.cache")
                .message("Failed to close file channel in publisher")
                .error(ex)
                .log();
        } });
    }

    @Override
    public void close() {
        if (this.closed.compareAndSet(false, true)) {
            // Cancel cleanup task first
            if (this.cleanupTask != null) {
                this.cleanupTask.cancel(false);
            }
            
            // Close underlying storage if it's closeable (e.g., S3Storage)
            // This ensures proper resource cleanup through the delegation chain
            final Storage delegate = this.delegate();
            if (delegate instanceof AutoCloseable) {
                try {
                    ((AutoCloseable) delegate).close();
                } catch (final Exception ex) {
                    EcsLogger.warn("com.auto1.pantera.asto.cache")
                        .message("Failed to close delegate storage")
                        .error(ex)
                        .log();
                }
            }
        }
    }

    private void safeCleanup() {
        if (!this.closed.get()) {
            try {
                cleanup();
            } catch (final Throwable ex) {
                EcsLogger.warn("com.auto1.pantera.asto.cache")
                    .message("Cache cleanup failed")
                    .error(ex)
                    .log();
            }
        }
    }

    /**
     * Get lock object for given path using striped locking.
     * Avoids string interning anti-pattern.
     */
    private Object getLock(final Path path) {
        int hash = path.hashCode();
        return LOCKS[Math.abs(hash % LOCK_STRIPES)];
    }

    private void cleanup() throws IOException {
        final Path base = nsRoot();
        if (!Files.exists(base)) {
            return;
        }
        
        // Clean up orphaned files during periodic cleanup
        this.cleanupOrphanedFiles();
        
        final List<Path> dataFiles = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(base)) {
            walk.filter(p -> {
                    try {
                        return Files.isRegularFile(p) 
                            && !p.getFileName().toString().endsWith(".meta")
                            && !p.getFileName().toString().contains(".part-");
                    } catch (final Exception e) {
                        return false;  // Skip on error
                    }
                })
                .forEach(dataFiles::add);
        }
        long used = 0L;
        final List<Candidate> candidates = new ArrayList<>();
        for (final Path f : dataFiles) {
            final long size = Files.size(f);
            used += size;
            final Path meta = Path.of(f.toString() + ".meta");
            CacheMeta cm = null;
            if (Files.exists(meta)) {
                try { cm = CacheMeta.read(meta); } catch (final Exception ex) {
                    EcsLogger.debug("com.auto1.pantera.asto.cache")
                        .message("Failed to read cache metadata during cleanup")
                        .error(ex)
                        .log();
                }
            }
            if (cm == null) {
                cm = new CacheMeta();
                cm.size = size;
                cm.lastAccess = Files.getLastModifiedTime(f).toMillis();
                cm.hits = 0;
                cm.etag = "";
            }
            candidates.add(new Candidate(f, meta, cm));
        }
        if (this.maxBytes <= 0) {
            return;
        }
        final long high = this.maxBytes * this.highPct / 100L;
        final long low = this.maxBytes * this.lowPct / 100L;
        if (used <= high) {
            return;
        }
        final long target = used - low;
        // Sort by policy
        if (this.policy == Policy.LRU) {
            candidates.sort(Comparator.comparingLong(c -> c.meta.lastAccess));
        } else {
            candidates.sort(Comparator.comparingLong((Candidate c) -> c.meta.hits).thenComparingLong(c -> c.meta.lastAccess));
        }
        long freed = 0L;
        for (final Candidate c : candidates) {
            try {
                Files.deleteIfExists(c.file);
                Files.deleteIfExists(c.metaFile);
                freed += c.meta.size;
            } catch (final IOException ex) {
                EcsLogger.debug("com.auto1.pantera.asto.cache")
                    .message("Failed to delete cache file during eviction")
                    .error(ex)
                    .log();
            }
            if (freed >= target) {
                break;
            }
        }
    }

    private static String sha1(final String s) {
        try {
            final MessageDigest md = MessageDigest.getInstance("SHA-1");
            final byte[] dig = md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            final StringBuilder sb = new StringBuilder();
            for (final byte b : dig) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (final Exception err) {
            throw new IllegalStateException(err);
        }
    }

    private static final class Candidate {
        final Path file;
        final Path metaFile;
        final CacheMeta meta;
        Candidate(final Path f, final Path m, final CacheMeta cm) { this.file = f; this.metaFile = m; this.meta = cm; }
    }

    private static final class CacheMeta {
        String etag;
        long size;
        long lastAccess;
        long hits;

        static CacheMeta read(final Path meta) throws IOException {
            final Properties p = new Properties();
            try (InputStream in = Files.newInputStream(meta)) {
                p.load(in);
            }
            final CacheMeta cm = new CacheMeta();
            cm.etag = p.getProperty("etag", "");
            cm.size = Long.parseLong(p.getProperty("size", "0"));
            cm.lastAccess = Long.parseLong(p.getProperty("lastAccess", "0"));
            cm.hits = Long.parseLong(p.getProperty("hits", "0"));
            return cm;
        }

        static void write(final Path meta, final CacheMeta cm) throws IOException {
            final Properties p = new Properties();
            p.setProperty("etag", cm.etag == null ? "" : cm.etag);
            p.setProperty("size", Long.toString(cm.size));
            p.setProperty("lastAccess", Long.toString(cm.lastAccess));
            p.setProperty("hits", Long.toString(cm.hits));
            try (OutputStream out = Files.newOutputStream(meta)) {
                p.store(out, "cache");
            }
        }

        static CacheMeta fromRemote(final Meta meta) {
            final CacheMeta cm = new CacheMeta();
            cm.etag = meta.read(Meta.OP_MD5).map(Object::toString).orElse("");
            cm.size = meta.read(Meta.OP_SIZE).map(Long.class::cast).orElse(0L);
            return cm;
        }
    }
}
