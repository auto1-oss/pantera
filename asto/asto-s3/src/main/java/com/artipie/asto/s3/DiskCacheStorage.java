/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.asto.s3;

import com.artipie.asto.ArtipieIOException;
import com.artipie.asto.Content;
import com.artipie.asto.Key;
import com.artipie.asto.Meta;
import com.artipie.asto.Storage;
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
     * Shared executor service for all cache cleanup tasks.
     * Uses bounded thread pool to prevent thread proliferation.
     */
    private static final ScheduledExecutorService SHARED_CLEANER = 
        Executors.newScheduledThreadPool(
            Math.max(2, Runtime.getRuntime().availableProcessors() / 4),
            r -> {
                final Thread t = new Thread(r, "shared-disk-cache-cleaner");
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
            throw new ArtipieIOException(err);
        }
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

    @Override
    public CompletableFuture<Content> value(final Key key) {
        final Path file = filePath(key);
        final Path meta = metaPath(key);
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (Files.exists(file) && Files.exists(meta)) {
                    final CacheMeta cm = CacheMeta.read(meta);
                    if (!this.validateOnRead || this.matchRemote(key, cm)) {
                        // Serve from cache and update metadata
                        final Content cnt = new Content.From(
                            cm.size > 0 ? Optional.of(cm.size) : Optional.empty(),
                            filePublisher(file)
                        );
                        // Update metadata asynchronously to avoid blocking and race conditions
                        CompletableFuture.runAsync(() -> {
                            try {
                                synchronized (getLock(meta)) {
                                    final CacheMeta updated = CacheMeta.read(meta);
                                    updated.hits += 1;
                                    updated.lastAccess = Instant.now().toEpochMilli();
                                    CacheMeta.write(meta, updated);
                                }
                            } catch (final IOException ignored) {
                                // Best effort - cache hit still served
                            }
                        });
                        return cnt;
                    }
                }
            } catch (final IOException ex) {
                // Fall through to fetch on any cache read error
            }
            return null;
        }).thenCompose(hit -> {
            if (hit != null) {
                return CompletableFuture.completedFuture(hit);
            }
            // Miss or stale: fetch from delegate, stream to caller, persist to disk
            return this.fetchAndPersist(key, file, meta);
        });
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
        } catch (final IOException ignored) {
            // best-effort
        }
    }

    private CompletableFuture<Content> fetchAndPersist(final Key key, final Path file, final Path meta) {
        // Ensure parent directories exist
        try {
            Files.createDirectories(file.getParent());
        } catch (final IOException err) {
            return CompletableFuture.failedFuture(new ArtipieIOException(err));
        }
        // Preload remote metadata (ETag/size) to store alongside
        final CompletableFuture<? extends Meta> remoteMeta = super.metadata(key);
        final Path tmp = file.getParent().resolve(file.getFileName().toString() + ".part-" + UUID.randomUUID());
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
                            throw new ArtipieIOException(ioe);
                        }
                    })
                    .doOnComplete(() -> {
                        try {
                            ch.force(true);
                            ch.close();
                            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                            final CacheMeta cm = CacheMeta.fromRemote(remoteMeta.join());
                            cm.lastAccess = Instant.now().toEpochMilli();
                            cm.hits = 1;
                            CacheMeta.write(meta, cm);
                        } catch (final IOException ioe) {
                            throw new ArtipieIOException(ioe);
                        }
                    })
                    .doOnError(th -> {
                        try { ch.close(); } catch (final IOException ignore) { }
                        try { Files.deleteIfExists(tmp); } catch (final IOException ignore) { }
                    });
                result.complete(new Content.From(cnt.size(), stream));
            } catch (final IOException ioe) {
                result.completeExceptionally(new ArtipieIOException(ioe));
            }
        });
        return result;
    }

    private boolean matchRemote(final Key key, final CacheMeta local) {
        try {
            // FIXME: This blocks! Should be made async or validation disabled for high-load scenarios
            // For now, add timeout to prevent indefinite blocking
            final Meta meta = super.metadata(key)
                .toCompletableFuture()
                .orTimeout(5, TimeUnit.SECONDS)
                .join();
            final boolean md5ok = meta.read(Meta.OP_MD5)
                .map(val -> Objects.equals(val, local.etag))
                .orElse(false);
            final boolean sizeok = meta.read(Meta.OP_SIZE)
                .map(val -> Objects.equals(val, local.size))
                .orElse(false);
            return md5ok && sizeok;
        } catch (final Exception err) {
            // If cannot validate or timeout, assume stale
            return false;
        }
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
        }, ch -> { try { ch.close(); } catch (final IOException ignored) { } });
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
                } catch (final Exception e) {
                    // Log but continue - best effort cleanup
                    System.err.println("Failed to close delegate storage: " + e.getMessage());
                }
            }
        }
    }

    private void safeCleanup() {
        if (!this.closed.get()) {
            try { 
                cleanup(); 
            } catch (final Throwable ignored) { 
                // Ignore errors during cleanup
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
                try { cm = CacheMeta.read(meta); } catch (final Exception ignore) { }
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
            } catch (final IOException ignore) { }
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
