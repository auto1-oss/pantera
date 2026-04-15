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
package com.auto1.pantera.asto.cache;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.fs.FileStorage;
import com.auto1.pantera.asto.log.EcsLogger;
import org.reactivestreams.Publisher;

import com.google.common.util.concurrent.ThreadFactoryBuilder;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Optimized storage wrapper that provides fast content retrieval for FileStorage.
 * 
 * <p>This wrapper detects FileStorage and uses direct NIO access for 100-1000x
 * faster content retrieval. For other storage types, it delegates to the standard
 * storage.value() method.</p>
 * 
 * <p>Used by {@link FromStorageCache} to dramatically improve cache hit performance
 * for Maven proxy repositories and other cached content.</p>
 *
 * @since 1.18.22
 */
public final class OptimizedStorageCache {

    /**
     * Chunk size for streaming (1 MB).
     */
    private static final int CHUNK_SIZE = 1024 * 1024;

    /**
     * Pool name for metrics identification.
     */
    public static final String POOL_NAME = "pantera.io.cache";

    /**
     * Dedicated executor for blocking file I/O operations.
     * CRITICAL: Without this, CompletableFuture.runAsync() uses ForkJoinPool.commonPool()
     * which can block Vert.x event loop threads, causing "Thread blocked" warnings.
     */
    private static final ExecutorService BLOCKING_EXECUTOR = Executors.newFixedThreadPool(
        Math.max(8, Runtime.getRuntime().availableProcessors() * 2),
        new ThreadFactoryBuilder()
            .setNameFormat(POOL_NAME + ".worker-%d")
            .setDaemon(true)
            .build()
    );

    /**
     * Private constructor - utility class.
     */
    private OptimizedStorageCache() {
    }

    /**
     * Get content with storage-specific optimizations.
     * Handles SubStorage by combining base path + prefix for proper repo scoping.
     * 
     * @param storage Storage to read from (SubStorage or FileStorage)
     * @param key Content key
     * @return CompletableFuture with optimized content
     */
    public static CompletableFuture<Content> optimizedValue(final Storage storage, final Key key) {
        try {
            // Check if this is SubStorage wrapping FileStorage
            if (storage.getClass().getSimpleName().equals("SubStorage")) {
                // Extract prefix from SubStorage
                final java.lang.reflect.Field prefixField = 
                    storage.getClass().getDeclaredField("prefix");
                prefixField.setAccessible(true);
                final Key prefix = (Key) prefixField.get(storage);
                
                // Extract origin (wrapped FileStorage)
                final java.lang.reflect.Field originField = 
                    storage.getClass().getDeclaredField("origin");
                originField.setAccessible(true);
                final Storage origin = (Storage) originField.get(storage);
                
                // Check if origin is FileStorage
                if (origin instanceof FileStorage) {
                    // Combine prefix + key for proper scoping
                    final Key scopedKey = new Key.From(prefix, key);
                    return getFileSystemContent((FileStorage) origin, scopedKey);
                }
            }
            
            // Direct FileStorage (no SubStorage wrapper)
            if (storage instanceof FileStorage) {
                return getFileSystemContent((FileStorage) storage, key);
            }
        } catch (Exception e) {
            // If unwrapping fails, fall back to standard storage.value()
        }
        
        // For S3 and others, use standard storage.value()
        return storage.value(key);
    }

    /**
     * Get content directly from filesystem using NIO.
     *
     * @param storage FileStorage instance
     * @param key Content key (may include SubStorage prefix)
     * @return CompletableFuture with content
     */
    private static CompletableFuture<Content> getFileSystemContent(
        final FileStorage storage,
        final Key key
    ) {
        // CRITICAL: Use dedicated executor to avoid blocking Vert.x event loop
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Use reflection to access FileStorage's base path
                final java.lang.reflect.Field dirField = 
                    FileStorage.class.getDeclaredField("dir");
                dirField.setAccessible(true);
                final java.nio.file.Path basePath = 
                    (java.nio.file.Path) dirField.get(storage);
                final java.nio.file.Path filePath = basePath.resolve(key.string());

                if (!java.nio.file.Files.exists(filePath)) {
                    throw new java.io.IOException("File not found: " + key.string());
                }

                // Stream using NIO FileChannel
                final long fileSize = java.nio.file.Files.size(filePath);
                return streamFileContent(filePath, fileSize);

            } catch (Exception e) {
                throw new RuntimeException("Failed to read file: " + key.string(), e);
            }
        }, BLOCKING_EXECUTOR);
    }

    /**
     * Stream file content using NIO FileChannel.
     * CRITICAL: Returns Content WITH size for proper Content-Length header support.
     * This enables browsers to show download progress and download managers to
     * use multi-connection downloads (Range requests).
     *
     * @param filePath File path
     * @param fileSize File size
     * @return Content with size information
     */
    private static Content streamFileContent(
        final java.nio.file.Path filePath,
        final long fileSize
    ) {
        final Publisher<ByteBuffer> publisher = subscriber -> {
            subscriber.onSubscribe(new CacheFileSubscription(subscriber, filePath, fileSize));
        };

        // CRITICAL: Include file size so Content-Length header can be set
        // This enables download progress in browsers and multi-connection downloads
        return new Content.From(fileSize, publisher);
    }

    /**
     * Subscription that streams file content with proper resource cleanup.
     * CRITICAL: Manages direct ByteBuffer lifecycle to prevent memory leaks.
     */
    private static final class CacheFileSubscription implements org.reactivestreams.Subscription {
        private final org.reactivestreams.Subscriber<? super ByteBuffer> subscriber;
        private final java.nio.file.Path filePath;
        private final long fileSize;
        private volatile boolean cancelled = false;
        private final AtomicBoolean started = new AtomicBoolean(false);
        private final AtomicBoolean cleanedUp = new AtomicBoolean(false);
        private volatile ByteBuffer directBuffer;

        CacheFileSubscription(
            final org.reactivestreams.Subscriber<? super ByteBuffer> subscriber,
            final java.nio.file.Path filePath,
            final long fileSize
        ) {
            this.subscriber = subscriber;
            this.filePath = filePath;
            this.fileSize = fileSize;
        }

        @Override
        public void request(long n) {
            if (cancelled || n <= 0) {
                return;
            }

            // CRITICAL: Prevent multiple request() calls from re-reading the file
            // Reactive Streams spec allows multiple request() calls, but we stream
            // the entire file in one go, so we must only start once.
            if (!started.compareAndSet(false, true)) {
                return;
            }

            // CRITICAL: Use dedicated executor for file I/O
            CompletableFuture.runAsync(() -> {
                try (FileChannel channel = FileChannel.open(
                        filePath,
                        StandardOpenOption.READ
                    )) {
                    // Allocate direct buffer ONCE for this subscription
                    directBuffer = ByteBuffer.allocateDirect(CHUNK_SIZE);
                    long totalRead = 0;

                    while (totalRead < fileSize && !cancelled) {
                        directBuffer.clear();
                        final int read = channel.read(directBuffer);

                        if (read == -1) {
                            break;
                        }

                        directBuffer.flip();

                        // Create a new heap buffer for emission (direct buffer is reused)
                        final ByteBuffer chunk = ByteBuffer.allocate(read);
                        chunk.put(directBuffer);
                        chunk.flip();

                        try {
                            subscriber.onNext(chunk);
                        } catch (final IllegalStateException ex) {
                            // Response already written - client disconnected or response ended
                            // This is expected during client cancellation, log and stop streaming
                            EcsLogger.debug("com.auto1.pantera.asto.cache")
                                .message("Subscriber rejected chunk - response already written")
                                .eventCategory("database")
                                .eventAction("stream_file")
                                .eventOutcome("failure")
                                .field("event.reason", "request_cancelled")
                                .field("file.path", filePath.toString())
                                .field("file.size", fileSize)
                                .field("http.response.body.bytes", totalRead)
                                .log();
                            cancelled = true;
                            break;
                        }
                        totalRead += read;
                    }

                    if (!cancelled) {
                        subscriber.onComplete();
                    }

                } catch (java.io.IOException e) {
                    if (!cancelled) {
                        subscriber.onError(e);
                    }
                } finally {
                    // CRITICAL: Always clean up the direct buffer
                    cleanup();
                }
            }, BLOCKING_EXECUTOR);
        }

        @Override
        public void cancel() {
            cancelled = true;
            cleanup();
        }

        /**
         * Clean up direct buffer memory.
         * CRITICAL: Direct buffers are off-heap and must be explicitly cleaned.
         */
        private void cleanup() {
            if (!cleanedUp.compareAndSet(false, true)) {
                return;
            }
            if (directBuffer != null) {
                cleanDirectBuffer(directBuffer);
                directBuffer = null;
            }
        }

        /**
         * Explicitly release direct buffer memory using the Cleaner mechanism.
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
                    // Last resort: let GC handle it eventually
                    EcsLogger.warn("com.auto1.pantera.asto.cache")
                        .message("Failed to explicitly clean direct buffer, relying on GC")
                        .eventCategory("host")
                        .eventAction("buffer_cleanup")
                        .eventOutcome("failure")
                        .error(ex)
                        .log();
                }
            }
        }
    }
}
