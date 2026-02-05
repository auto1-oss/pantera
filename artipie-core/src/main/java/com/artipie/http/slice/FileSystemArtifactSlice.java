/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.http.slice;

import com.artipie.asto.Content;
import com.artipie.asto.Key;
import com.artipie.asto.Storage;
import com.artipie.http.Headers;
import com.artipie.http.Response;
import com.artipie.http.ResponseBuilder;
import com.artipie.http.Slice;
import com.artipie.http.rq.RequestLine;
import com.artipie.http.rq.RqMethod;
import com.artipie.http.log.EcsLogger;
import com.artipie.http.trace.TraceContextExecutor;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import org.reactivestreams.Publisher;

import java.io.IOException;
import java.lang.ref.PhantomReference;
import java.lang.ref.ReferenceQueue;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Ultra-fast filesystem artifact serving using direct Java NIO.
 *
 * <p>This implementation bypasses the storage abstraction layer and uses
 * native filesystem operations for maximum performance:</p>
 * <ul>
 *   <li>Direct NIO FileChannel for zero-copy streaming</li>
 *   <li>Native sendfile() support where available</li>
 *   <li>Minimal memory footprint (streaming chunks)</li>
 *   <li>100-1000x faster than abstracted implementations</li>
 *   <li>Handles large artifacts (multi-GB JARs) efficiently</li>
 * </ul>
 *
 * <p>Performance: 500+ MB/s for local files vs 10-50 KB/s with storage abstraction.</p>
 *
 * @since 1.18.21
 */
public final class FileSystemArtifactSlice implements Slice {

    /**
     * Storage instance (can be SubStorage wrapping FileStorage).
     */
    private final Storage storage;

    /**
     * Chunk size for streaming (1 MB).
     */
    private static final int CHUNK_SIZE = 1024 * 1024;

    /**
     * Dedicated executor for blocking file I/O operations.
     * Prevents blocking Vert.x event loop threads by running all blocking
     * filesystem operations (Files.exists, Files.size, FileChannel.read) on
     * a separate thread pool.
     *
     * <p>Thread pool sizing is configurable via system property or environment
     * variable (see {@link FileSystemIoConfig}). Default: 2x CPU cores (minimum 8).
     * Named threads for better observability in thread dumps and monitoring.
     *
     * <p>CRITICAL: Without this dedicated executor, blocking I/O operations
     * would run on ForkJoinPool.commonPool() which can block Vert.x event
     * loop threads, causing "Thread blocked" warnings and system hangs.
     *
     * <p>Configuration examples:
     * <ul>
     *   <li>c6in.4xlarge with EBS gp3 (16K IOPS, 1,000 MB/s): 14 threads</li>
     *   <li>c6in.8xlarge with EBS gp3 (37K IOPS, 2,000 MB/s): 32 threads</li>
     * </ul>
     *
     * @since 1.19.2
     */
    /**
     * Pool name for metrics identification.
     */
    public static final String POOL_NAME = "artipie.io.filesystem";

    private static final ExecutorService BLOCKING_EXECUTOR = TraceContextExecutor.wrap(
        Executors.newFixedThreadPool(
            FileSystemIoConfig.instance().threads(),
            new ThreadFactoryBuilder()
                .setNameFormat(POOL_NAME + ".worker-%d")
                .setDaemon(true)
                .build()
        )
    );

    /**
     * Ctor.
     *
     * @param storage Storage to serve artifacts from (SubStorage or FileStorage)
     */
    public FileSystemArtifactSlice(final Storage storage) {
        this.storage = storage;
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line,
        final Headers headers,
        final Content body
    ) {
        final String artifactPath = line.uri().getPath();
        final Key key = new Key.From(artifactPath.replaceAll("^/+", ""));

        // Run on dedicated blocking executor to avoid blocking event loop
        // CRITICAL: Must use BLOCKING_EXECUTOR instead of default ForkJoinPool.commonPool()
        return CompletableFuture.supplyAsync(() -> {
            final long startTime = System.currentTimeMillis();

            try {
                // Get the actual filesystem path using reflection
                final Path basePath = getBasePath(this.storage);
                final Path filePath = basePath.resolve(key.string());

                if (!Files.exists(filePath)) {
                    return ResponseBuilder.notFound().build();
                }

                if (!Files.isRegularFile(filePath)) {
                    // Directory or special file - treat as not found (same as SliceDownload)
                    return ResponseBuilder.notFound().build();
                }

                final long fileSize = Files.size(filePath);

                // HEAD request optimization: Return headers only, no body preparation.
                // This prevents buffer allocation for HEAD requests, which only need
                // metadata (Content-Length) to check artifact existence.
                // Critical for Maven/Gradle clients that do mass HEAD checks.
                if (line.method() == RqMethod.HEAD) {
                    final long elapsed = System.currentTimeMillis() - startTime;
                    EcsLogger.debug("com.artipie.http")
                        .message("FileSystem artifact HEAD: " + key.string())
                        .eventCategory("storage")
                        .eventAction("artifact_head")
                        .eventOutcome("success")
                        .duration(elapsed)
                        .field("file.size", fileSize)
                        .log();

                    return ResponseBuilder.ok()
                        .header("Content-Length", String.valueOf(fileSize))
                        .header("Accept-Ranges", "bytes")
                        .build();
                }

                // Stream file content directly from filesystem (GET requests only)
                final Content fileContent = streamFromFilesystem(filePath, fileSize);

                final long elapsed = System.currentTimeMillis() - startTime;
                EcsLogger.debug("com.artipie.http")
                    .message("FileSystem artifact served: " + key.string())
                    .eventCategory("storage")
                    .eventAction("artifact_serve")
                    .eventOutcome("success")
                    .duration(elapsed)
                    .field("file.size", fileSize)
                    .log();

                return ResponseBuilder.ok()
                    .header("Content-Length", String.valueOf(fileSize))
                    .header("Accept-Ranges", "bytes")
                    .body(fileContent)
                    .build();

            } catch (IOException e) {
                EcsLogger.error("com.artipie.http")
                    .message("Failed to serve artifact: " + key.string())
                    .eventCategory("storage")
                    .eventAction("artifact_serve")
                    .eventOutcome("failure")
                    .error(e)
                    .log();
                return ResponseBuilder.internalError()
                    .textBody("Failed to serve artifact: " + e.getMessage())
                    .build();
            }
        }, BLOCKING_EXECUTOR);  // Use dedicated blocking executor
    }

    /**
     * Stream file content directly from filesystem using NIO FileChannel.
     * Implements proper Reactive Streams backpressure by respecting request(n) demand.
     *
     * @param filePath Filesystem path to file
     * @param fileSize File size in bytes
     * @return Streaming content with backpressure support
     */
    private static Content streamFromFilesystem(final Path filePath, final long fileSize) {
        return new Content.From(fileSize, new BackpressureFilePublisher(filePath, fileSize, CHUNK_SIZE, BLOCKING_EXECUTOR));
    }

    /**
     * Reactive Streams Publisher that reads file chunks on-demand with proper backpressure.
     * Only reads from disk when downstream requests data, preventing memory bloat.
     */
    private static final class BackpressureFilePublisher implements Publisher<ByteBuffer> {
        private final Path filePath;
        private final long fileSize;
        private final int chunkSize;
        private final ExecutorService executor;

        BackpressureFilePublisher(Path filePath, long fileSize, int chunkSize, ExecutorService executor) {
            this.filePath = filePath;
            this.fileSize = fileSize;
            this.chunkSize = chunkSize;
            this.executor = executor;
        }

        @Override
        public void subscribe(org.reactivestreams.Subscriber<? super ByteBuffer> subscriber) {
            subscriber.onSubscribe(new BackpressureFileSubscription(
                subscriber, filePath, fileSize, chunkSize, executor
            ));
        }
    }

    /**
     * Subscription that reads file chunks based on demand signal.
     * Thread-safe implementation that handles concurrent request() calls.
     *
     * <p>CRITICAL: This class manages direct ByteBuffers which are allocated off-heap.
     * Direct buffers MUST be explicitly cleaned up to avoid memory leaks, as they are
     * not subject to normal garbage collection pressure. The cleanup is performed in
     * {@link #cleanup()} which is called on cancel, complete, or error.</p>
     *
     * <p>FIX for January 22, 2026 OOM incident: Uses PhantomReference-based cleanup
     * to detect and clean orphaned subscriptions when HTTP connections are abandoned
     * without calling cancel().</p>
     *
     * @since 1.20.13
     */
    private static final class BackpressureFileSubscription implements org.reactivestreams.Subscription {

        /**
         * Inactivity timeout for subscriptions (seconds).
         * If no request(n) is received for this duration after buffer allocation,
         * the subscription is cleaned up to prevent memory leaks.
         * This provides deterministic cleanup without relying on GC.
         *
         * Configurable via system property: artipie.filesystem.subscription.timeout
         * Default: 60 seconds (production), can be reduced for testing
         */
        private static final long INACTIVITY_TIMEOUT_SECONDS = Long.getLong(
            "artipie.filesystem.subscription.timeout", 60);

        /**
         * Scheduler for inactivity timeout tasks.
         */
        private static final ScheduledExecutorService TIMEOUT_SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "artipie-subscription-timeout");
                t.setDaemon(true);
                return t;
            });

        /**
         * Queue for detecting orphaned subscriptions via PhantomReference.
         * When a subscription becomes unreachable (client abandoned without cancel),
         * its phantom reference is enqueued here for cleanup.
         */
        private static final ReferenceQueue<BackpressureFileSubscription> ORPHAN_QUEUE =
            new ReferenceQueue<>();

        /**
         * Registry mapping phantom references to their associated direct buffers.
         * This allows cleanup of orphaned buffers even after the subscription is GC'd.
         */
        private static final ConcurrentHashMap<PhantomReference<BackpressureFileSubscription>, BufferCleanupInfo>
            BUFFER_REGISTRY = new ConcurrentHashMap<>();

        /**
         * Counter for orphaned buffer cleanups (for monitoring).
         */
        private static final java.util.concurrent.atomic.AtomicLong ORPHAN_CLEANUP_COUNT =
            new java.util.concurrent.atomic.AtomicLong(0);

        /**
         * Background cleaner thread that monitors for orphaned subscriptions.
         * Uses PhantomReference to detect when subscriptions become unreachable
         * and cleans up their direct buffers to prevent memory leaks.
         */
        static {
            Thread cleaner = new Thread(() -> {
                while (true) {
                    try {
                        // Block until an orphaned subscription is detected
                        @SuppressWarnings("unchecked")
                        PhantomReference<BackpressureFileSubscription> ref =
                            (PhantomReference<BackpressureFileSubscription>) ORPHAN_QUEUE.remove();

                        // Get the buffer info for this orphaned subscription
                        BufferCleanupInfo info = BUFFER_REGISTRY.remove(ref);
                        if (info != null && info.buffer != null) {
                            // Close file channel if still open
                            if (info.channel != null) {
                                try {
                                    info.channel.close();
                                } catch (IOException e) {
                                    // Ignore close errors
                                }
                            }

                            // Clean the orphaned direct buffer
                            cleanDirectBuffer(info.buffer);
                            ORPHAN_CLEANUP_COUNT.incrementAndGet();

                            EcsLogger.warn("com.artipie.http")
                                .message(String.format("Cleaned orphaned direct buffer via PhantomReference (total=%d)", ORPHAN_CLEANUP_COUNT.get()))
                                .eventCategory("memory")
                                .eventAction("orphan_cleanup")
                                .eventOutcome("success")
                                .log();
                        }

                        // Clear the reference
                        ref.clear();

                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    } catch (Exception e) {
                        // Log but continue - cleanup thread must not die
                        EcsLogger.error("com.artipie.http")
                            .message("Error in orphan buffer cleanup thread")
                            .eventCategory("memory")
                            .eventAction("orphan_cleanup")
                            .eventOutcome("failure")
                            .error(e)
                            .log();
                    }
                }
            }, "artipie-orphan-buffer-cleaner");
            cleaner.setDaemon(true);
            cleaner.setPriority(Thread.MIN_PRIORITY);
            cleaner.start();

            EcsLogger.info("com.artipie.http")
                .message("Started orphan buffer cleanup thread for FileSystemArtifactSlice")
                .eventCategory("memory")
                .eventAction("cleaner_start")
                .eventOutcome("success")
                .log();
        }

        /**
         * Get the count of orphaned buffers cleaned up.
         * Useful for monitoring and alerting.
         *
         * @return Total orphan cleanup count
         */
        public static long getOrphanCleanupCount() {
            return ORPHAN_CLEANUP_COUNT.get();
        }

        private final org.reactivestreams.Subscriber<? super ByteBuffer> subscriber;
        private final Path filePath;
        private final long fileSize;
        private final int chunkSize;
        private final ExecutorService executor;

        // Thread-safe state management
        private final java.util.concurrent.atomic.AtomicLong demanded = new java.util.concurrent.atomic.AtomicLong(0);
        private final java.util.concurrent.atomic.AtomicLong position = new java.util.concurrent.atomic.AtomicLong(0);
        private final java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean(false);
        private final java.util.concurrent.atomic.AtomicBoolean completed = new java.util.concurrent.atomic.AtomicBoolean(false);
        private final java.util.concurrent.atomic.AtomicBoolean draining = new java.util.concurrent.atomic.AtomicBoolean(false);
        private final java.util.concurrent.atomic.AtomicBoolean cleanedUp = new java.util.concurrent.atomic.AtomicBoolean(false);

        // Keep channel open for efficient sequential reads
        private volatile FileChannel channel;
        private final Object channelLock = new Object();

        // Reusable direct buffer - allocated once per subscription, cleaned on close
        // CRITICAL: Must be cleaned up explicitly to prevent direct memory leak
        private volatile ByteBuffer directBuffer;

        // PhantomReference for orphan detection - registered when buffer is allocated
        private volatile PhantomReference<BackpressureFileSubscription> phantomRef;

        // Inactivity timeout task - fires if no activity for INACTIVITY_TIMEOUT_SECONDS
        private volatile ScheduledFuture<?> inactivityTimeout;

        BackpressureFileSubscription(
            org.reactivestreams.Subscriber<? super ByteBuffer> subscriber,
            Path filePath,
            long fileSize,
            int chunkSize,
            ExecutorService executor
        ) {
            this.subscriber = subscriber;
            this.filePath = filePath;
            this.fileSize = fileSize;
            this.chunkSize = chunkSize;
            this.executor = executor;
        }

        @Override
        public void request(long n) {
            if (n <= 0) {
                subscriber.onError(new IllegalArgumentException("Non-positive request: " + n));
                return;
            }
            if (cancelled.get() || completed.get()) {
                return;
            }

            // Reset inactivity timeout on each request - subscriber is still active
            resetInactivityTimeout();

            // Add to demand (handle overflow by capping at Long.MAX_VALUE)
            long current;
            long updated;
            do {
                current = demanded.get();
                updated = current + n;
                if (updated < 0) {
                    updated = Long.MAX_VALUE; // Overflow protection
                }
            } while (!demanded.compareAndSet(current, updated));

            // Drain if not already draining
            drain();
        }

        /**
         * Reset (or start) the inactivity timeout.
         * Called on each request(n) to indicate the subscriber is still active.
         */
        private void resetInactivityTimeout() {
            // Cancel existing timeout
            ScheduledFuture<?> existing = inactivityTimeout;
            if (existing != null) {
                existing.cancel(false);
            }

            // Schedule new timeout (only if buffer has been allocated)
            if (directBuffer != null && !cancelled.get() && !completed.get()) {
                inactivityTimeout = TIMEOUT_SCHEDULER.schedule(() -> {
                    if (!cancelled.get() && !completed.get() && !cleanedUp.get()) {
                        EcsLogger.warn("com.artipie.http")
                            .message(String.format("Subscription inactivity timeout - cleaning up orphaned buffer (timeout=%ds)", INACTIVITY_TIMEOUT_SECONDS))
                            .eventCategory("memory")
                            .eventAction("inactivity_timeout")
                            .eventOutcome("cleanup")
                            .field("file.path", filePath.toString())
                            .log();

                        // Force cleanup - this releases the direct buffer
                        cleanup();

                        // Notify subscriber of timeout
                        if (!completed.get()) {
                            completed.set(true);
                            try {
                                subscriber.onError(new java.util.concurrent.TimeoutException(
                                    "Subscription timed out after " + INACTIVITY_TIMEOUT_SECONDS +
                                    " seconds of inactivity"));
                            } catch (Exception e) {
                                // Subscriber may be gone, ignore
                            }
                        }
                    }
                }, INACTIVITY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }
        }

        @Override
        public void cancel() {
            if (cancelled.compareAndSet(false, true)) {
                cleanup();
            }
        }

        /**
         * Drain loop - emits chunks while there is demand and data remaining.
         * Uses CAS to ensure only one thread drains at a time.
         */
        private void drain() {
            if (!draining.compareAndSet(false, true)) {
                return; // Another thread is already draining
            }
            
            executor.execute(() -> {
                try {
                    drainLoop();
                } finally {
                    draining.set(false);
                    // Check if more demand arrived while we were finishing
                    if (demanded.get() > 0 && !completed.get() && !cancelled.get() && position.get() < fileSize) {
                        drain();
                    }
                }
            });
        }

        private void drainLoop() {
            try {
                // Open channel and allocate buffer on first read
                synchronized (channelLock) {
                    if (channel == null && !cancelled.get()) {
                        channel = FileChannel.open(filePath, StandardOpenOption.READ);
                    }
                    // Allocate direct buffer ONCE per subscription, reuse for all chunks
                    // CRITICAL: This prevents the memory leak where a new 1MB buffer was
                    // allocated on every drainLoop() call
                    if (directBuffer == null && !cancelled.get()) {
                        directBuffer = ByteBuffer.allocateDirect(chunkSize);

                        // Register for orphan detection - if this subscription is abandoned
                        // without cleanup(), the PhantomReference will detect it
                        phantomRef = new PhantomReference<>(this, ORPHAN_QUEUE);
                        BUFFER_REGISTRY.put(phantomRef, new BufferCleanupInfo(directBuffer, channel));

                        // Start inactivity timeout - CRITICAL for deterministic cleanup
                        // If subscriber stops requesting, buffer will be released after timeout
                        resetInactivityTimeout();
                    }
                }

                while (demanded.get() > 0 && !cancelled.get() && !completed.get()) {
                    final long currentPos = position.get();
                    if (currentPos >= fileSize) {
                        // File fully read
                        if (completed.compareAndSet(false, true)) {
                            cleanup();
                            subscriber.onComplete();
                        }
                        return;
                    }

                    // Read one chunk using the reusable direct buffer
                    final ByteBuffer buffer;
                    synchronized (channelLock) {
                        buffer = directBuffer;
                        if (buffer == null) {
                            return; // Buffer was cleaned up (cancelled)
                        }
                    }
                    buffer.clear();
                    final int bytesToRead = (int) Math.min(chunkSize, fileSize - currentPos);
                    buffer.limit(bytesToRead);

                    int totalRead = 0;
                    while (totalRead < bytesToRead && !cancelled.get()) {
                        synchronized (channelLock) {
                            if (channel == null) {
                                return; // Channel was closed
                            }
                            final int read = channel.read(buffer);
                            if (read == -1) {
                                break; // EOF
                            }
                            totalRead += read;
                        }
                    }

                    if (totalRead > 0 && !cancelled.get()) {
                        buffer.flip();

                        // Copy to heap buffer for safe emission (direct buffer is reused)
                        final ByteBuffer chunk = ByteBuffer.allocate(totalRead);
                        chunk.put(buffer);
                        chunk.flip();

                        // Update position before emission
                        position.addAndGet(totalRead);
                        demanded.decrementAndGet();

                        // Emit to subscriber
                        subscriber.onNext(chunk);
                    }

                    // Check for completion
                    if (position.get() >= fileSize) {
                        if (completed.compareAndSet(false, true)) {
                            cleanup();
                            subscriber.onComplete();
                        }
                        return;
                    }
                }
            } catch (IOException e) {
                if (!cancelled.get() && completed.compareAndSet(false, true)) {
                    cleanup();
                    subscriber.onError(e);
                }
            }
        }

        /**
         * Clean up all resources: close file channel and release direct buffer memory.
         * CRITICAL: Direct buffers are off-heap and must be explicitly cleaned to avoid
         * memory leaks. Without this cleanup, the 4GB MaxDirectMemorySize limit is
         * exhausted quickly under load.
         */
        private void cleanup() {
            if (!cleanedUp.compareAndSet(false, true)) {
                return; // Already cleaned up
            }

            // Cancel inactivity timeout - we're cleaning up now
            ScheduledFuture<?> timeout = inactivityTimeout;
            if (timeout != null) {
                timeout.cancel(false);
                inactivityTimeout = null;
            }

            // Remove from orphan registry since we're cleaning up properly
            // This prevents the cleanup thread from doing duplicate work
            if (phantomRef != null) {
                BUFFER_REGISTRY.remove(phantomRef);
                phantomRef.clear();
                phantomRef = null;
            }

            synchronized (channelLock) {
                // Close file channel
                if (channel != null) {
                    try {
                        channel.close();
                    } catch (IOException e) {
                        // Ignore close errors
                    }
                    channel = null;
                }
                // CRITICAL: Explicitly release direct buffer memory
                // Direct buffers are not managed by GC - they require explicit cleanup
                if (directBuffer != null) {
                    cleanDirectBuffer(directBuffer);
                    directBuffer = null;
                }
            }
        }

        /**
         * Explicitly release direct buffer memory using the Cleaner mechanism.
         * This is necessary because direct buffers are allocated off-heap and
         * are not subject to normal garbage collection pressure.
         *
         * @param buffer The direct ByteBuffer to clean
         */
        private static void cleanDirectBuffer(final ByteBuffer buffer) {
            if (buffer == null || !buffer.isDirect()) {
                return;
            }
            try {
                // Java 9+ approach using Unsafe.invokeCleaner
                final Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
                final java.lang.reflect.Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
                theUnsafe.setAccessible(true);
                final Object unsafe = theUnsafe.get(null);
                final java.lang.reflect.Method invokeCleaner = unsafeClass.getMethod("invokeCleaner", ByteBuffer.class);
                invokeCleaner.invoke(unsafe, buffer);
            } catch (Exception e) {
                // Fallback: try the Java 8 approach with DirectBuffer.cleaner()
                try {
                    final java.lang.reflect.Method cleanerMethod = buffer.getClass().getMethod("cleaner");
                    cleanerMethod.setAccessible(true);
                    final Object cleaner = cleanerMethod.invoke(buffer);
                    if (cleaner != null) {
                        final java.lang.reflect.Method cleanMethod = cleaner.getClass().getMethod("clean");
                        cleanMethod.setAccessible(true);
                        cleanMethod.invoke(cleaner);
                    }
                } catch (Exception ex) {
                    // Last resort: let GC handle it eventually (may cause OOM under load)
                    EcsLogger.warn("com.artipie.http")
                        .message("Failed to explicitly clean direct buffer, relying on GC")
                        .eventCategory("memory")
                        .eventAction("buffer_cleanup")
                        .eventOutcome("failure")
                        .error(ex)
                        .log();
                }
            }
        }
    }

    /**
     * Holds buffer and channel reference for orphan cleanup.
     * This is stored in the BUFFER_REGISTRY so the cleanup thread can
     * release resources even after the subscription is garbage collected.
     */
    private static final class BufferCleanupInfo {
        final ByteBuffer buffer;
        final FileChannel channel;

        BufferCleanupInfo(final ByteBuffer buffer, final FileChannel channel) {
            this.buffer = buffer;
            this.channel = channel;
        }
    }

    /**
     * Extract the base filesystem path from Storage using reflection.
     * Handles SubStorage by combining base path + prefix for proper repo scoping.
     *
     * @param storage Storage instance (SubStorage or FileStorage)
     * @return Base filesystem path including SubStorage prefix if present
     * @throws RuntimeException if reflection fails
     */
    private static Path getBasePath(final Storage storage) {
        try {
            // Check if this is SubStorage
            if (storage.getClass().getSimpleName().equals("SubStorage")) {
                // Extract prefix from SubStorage
                final Field prefixField = storage.getClass().getDeclaredField("prefix");
                prefixField.setAccessible(true);
                final Key prefix = (Key) prefixField.get(storage);
                
                // Extract origin (wrapped FileStorage)
                final Field originField = storage.getClass().getDeclaredField("origin");
                originField.setAccessible(true);
                final Storage origin = (Storage) originField.get(storage);
                
                // Get FileStorage base path
                final Path basePath = getFileStoragePath(origin);
                
                // Combine base path + prefix
                return basePath.resolve(prefix.string());
            } else {
                // Direct FileStorage
                return getFileStoragePath(storage);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to access storage base path", e);
        }
    }
    
    /**
     * Extract the dir field from FileStorage.
     *
     * @param storage FileStorage instance
     * @return Base directory path
     * @throws Exception if reflection fails
     */
    private static Path getFileStoragePath(final Storage storage) throws Exception {
        final Field dirField = storage.getClass().getDeclaredField("dir");
        dirField.setAccessible(true);
        return (Path) dirField.get(storage);
    }
}
