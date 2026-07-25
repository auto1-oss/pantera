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
package com.auto1.pantera.http.slice;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.ValueNotFoundException;
import com.auto1.pantera.asto.blob.CachedBlobStorage;
import com.auto1.pantera.asto.cache.OptimizedStorageCache;
import com.auto1.pantera.asto.fs.FileStorage;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.log.EcsLogger;

import java.util.concurrent.CompletableFuture;

/**
 * Smart storage-aware artifact serving slice with automatic optimization.
 *
 * <p>This slice automatically dispatches to the most efficient implementation
 * based on the underlying storage type:</p>
 *
 * <ul>
 *   <li><b>FileStorage:</b> Uses {@link FileSystemArtifactSlice} for direct NIO access
 *       <ul>
 *         <li>Performance: 500+ MB/s throughput</li>
 *         <li>Zero-copy file streaming</li>
 *         <li>Native sendfile() support</li>
 *       </ul>
 *   </li>
 *   <li><b>{@link CachedBlobStorage} (WS1.1 {@code cache.mode: index}):</b> Uses
 *       {@link IndexBackedArtifactSlice} (WS1.6, spec &sect;3.F)
 *       <ul>
 *         <li>Single {@code storage.value()} call -- no upfront {@code exists()}
 *         probe, so a hosted read never pays the HEAD+GET double round-trip
 *         {@link GenericArtifactSlice} would (a cold key now issues exactly
 *         one blob-store GET instead of a HEAD then a GET; a warm key is a
 *         pure index+disk hit either way)</li>
 *         <li>404 derived from a {@link ValueNotFoundException}, not a
 *         separate existence check</li>
 *       </ul>
 *   </li>
 *   <li><b>Other Storage</b> (including {@code DiskCacheStorage} and plain
 *   {@code S3Storage}): Falls back to {@link GenericArtifactSlice}'s
 *   {@code exists()}+{@code value()} abstraction, unchanged
 *       <ul>
 *         <li>Works with any Storage implementation</li>
 *         <li>Slower but compatible</li>
 *       </ul>
 *   </li>
 * </ul>
 *
 * <p><b>Usage:</b></p>
 * <pre>{@code
 * // In repository slice (e.g., LocalMavenSlice):
 * Slice artifactSlice = new StorageArtifactSlice(storage);
 * return artifactSlice.response(line, headers, body);
 * }</pre>
 *
 * <p><b>Performance Impact:</b></p>
 * <ul>
 *   <li>FileStorage: 100-1000x faster downloads</li>
 *   <li>S3Storage: Eliminates abstraction overhead</li>
 *   <li>Build times: 13 minutes → ~30 seconds for FileStorage</li>
 * </ul>
 *
 * @since 1.18.21
 */
public final class StorageArtifactSlice implements Slice {

    /**
     * Underlying storage.
     */
    private final Storage storage;

    /**
     * Ctor.
     *
     * @param storage Storage to serve artifacts from
     */
    public StorageArtifactSlice(final Storage storage) {
        this.storage = storage;
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line,
        final Headers headers,
        final Content body
    ) {
        // Dispatch to storage-specific implementation
        final Slice delegate = this.selectArtifactSlice();
        return delegate.response(line, headers, body);
    }

    /**
     * Select the optimal artifact serving implementation based on storage type.
     *
     * @return Optimal Slice for serving artifacts
     */
    private Slice selectArtifactSlice() {
        // Unwrap storage to find the underlying implementation (for detection only)
        final Storage unwrapped = unwrapStorage(this.storage);
        
        // FileStorage: Use direct NIO for maximum performance
        // IMPORTANT: Pass original storage (with SubStorage prefix) to maintain repo scoping
        // Wrap with RangeSlice to support multi-connection downloads (Chrome, download managers, Maven)
        if (unwrapped instanceof FileStorage) {
            EcsLogger.debug("com.auto1.pantera.http")
                .message("Using FileSystemArtifactSlice for direct NIO access (detected: " + unwrapped.getClass().getSimpleName() + ", wrapper: " + this.storage.getClass().getSimpleName() + ")")
                .eventCategory("file")
                .eventAction("artifact_slice_select")
                .eventOutcome("success")
                .field("log.source", "application")
                .log();
            // Use original storage to preserve SubStorage prefix (repo scoping)
            // Wrap with RangeSlice for HTTP Range request support (resumable/parallel downloads)
            return new RangeSlice(new FileSystemArtifactSlice(this.storage));
        }

        // CachedBlobStorage (WS1.1 cache.mode: index): the index already
        // answers exists()/metadata() with zero blob-store round trips, so
        // GenericArtifactSlice's exists()+value() is a redundant SECOND
        // index consult on a hit (and a redundant HEAD-then-GET blob-store
        // round trip on a cold key) -- IndexBackedArtifactSlice (WS1.6, spec
        // sect 3.F) collapses this to the single value() call the spec's
        // "no redundant exists()" acceptance criterion calls for.
        if (unwrapped instanceof CachedBlobStorage) {
            EcsLogger.debug("com.auto1.pantera.http")
                .message("Using IndexBackedArtifactSlice for cache.mode: index storage (detected: "
                    + unwrapped.getClass().getSimpleName() + ", wrapper: "
                    + this.storage.getClass().getSimpleName() + ")")
                .eventCategory("file")
                .eventAction("artifact_slice_select")
                .eventOutcome("success")
                .field("log.source", "application")
                .log();
            // Use original storage to preserve SubStorage prefix (repo scoping)
            return new RangeSlice(new IndexBackedArtifactSlice(this.storage));
        }

        // Other storage types (DiskCacheStorage, plain S3Storage, ...): generic
        // exists()+value() abstraction, unchanged.
        EcsLogger.debug("com.auto1.pantera.http")
            .message("Using generic storage abstraction (type: " + unwrapped.getClass().getSimpleName() + ")")
            .eventCategory("file")
            .eventAction("artifact_slice_select")
            .eventOutcome("success")
            .field("log.source", "application")
            .log();
        // Wrap with RangeSlice for HTTP Range request support (resumable/parallel downloads)
        return new RangeSlice(new GenericArtifactSlice(this.storage));
    }

    /**
     * Unwrap storage to find the underlying implementation.
     * Storages are wrapped by DiskCacheStorage, SubStorage, etc.
     *
     * @param storage Storage to unwrap
     * @return Underlying storage implementation
     */
    private static Storage unwrapStorage(final Storage storage) {
        Storage current = storage;
        int maxDepth = 10; // Prevent infinite loops
        
        // Unwrap common wrappers (may be nested)
        for (int depth = 0; depth < maxDepth; depth++) {
            final String className = current.getClass().getSimpleName();
            boolean unwrapped = false;
            
            try {
                // Try DiskCacheStorage unwrapping
                if ("DiskCacheStorage".equals(className)) {
                    final java.lang.reflect.Field backend = 
                        current.getClass().getDeclaredField("backend");
                    backend.setAccessible(true);
                    current = (Storage) backend.get(current);
                    unwrapped = true;
                }
                
                // Try SubStorage unwrapping
                if ("SubStorage".equals(className)) {
                    final java.lang.reflect.Field origin = 
                        current.getClass().getDeclaredField("origin");
                    origin.setAccessible(true);
                    current = (Storage) origin.get(current);
                    unwrapped = true;
                }
                
                // No more wrappers found, stop unwrapping
                if (!unwrapped) {
                    break;
                }
                
            } catch (Exception e) {
                // EXPECTED: reflection-based storage unwrapping fails
                // (no `origin` field, security manager, etc.) → stop
                // unwrapping and use what we have. Correctness is
                // preserved by the standard storage.value() path.
                break;
            }
        }
        
        return current;
    }

    /**
     * Get artifact content with storage-specific optimizations.
     * This is a helper method that can be used as a drop-in replacement for
     * {@code storage.value(key)} with automatic performance optimization.
     *
     * <p><b>Usage:</b></p>
     * <pre>{@code
     * // Instead of:
     * storage.value(artifact)
     *
     * // Use:
     * StorageArtifactSlice.optimizedValue(storage, artifact)
     * }</pre>
     *
     * @param storage Storage to read from
     * @param key Artifact key
     * @return CompletableFuture with artifact content
     */
    public static CompletableFuture<Content> optimizedValue(
        final Storage storage,
        final Key key
    ) {
        // Delegate to OptimizedStorageCache from asto-core
        return OptimizedStorageCache.optimizedValue(storage, key);
    }

    /**
     * Generic artifact serving slice using storage abstraction.
     * This is the fallback for storage types without specific optimizations.
     */
    private static final class GenericArtifactSlice implements Slice {

        /**
         * Storage instance.
         */
        private final Storage storage;

        /**
         * Ctor.
         *
         * @param storage Storage to serve artifacts from
         */
        GenericArtifactSlice(final Storage storage) {
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

            return this.storage.exists(key).thenCompose(exists -> {
                if (!exists) {
                    return CompletableFuture.completedFuture(
                        ResponseBuilder.notFound().build()
                    );
                }

                return this.storage.value(key).thenApply(content -> {
                    final ResponseBuilder builder = ResponseBuilder.ok()
                        .header("Accept-Ranges", "bytes")
                        .body(content);
                    // Add Content-Length if size is known
                    content.size().ifPresent(size -> 
                        builder.header("Content-Length", String.valueOf(size))
                    );
                    return builder.build();
                });
            }).exceptionally(throwable -> {
                EcsLogger.error("com.auto1.pantera.http")
                    .message("Failed to serve artifact at key: " + key.string())
                    .eventCategory("file")
                    .eventAction("artifact_serve")
                    .eventOutcome("failure")
                    .error(throwable)
                    .field("log.source", "application")
                    .log();
                return ResponseBuilder.internalError()
                    .textBody("Failed to serve artifact: " + throwable.getMessage())
                    .build();
            });
        }
    }

    /**
     * Index-backed artifact serving slice for {@link CachedBlobStorage}
     * (WS1.1 {@code cache.mode: index}) -- WS1.6, spec {@code
     * WS1-storage-for-scale.md} &sect;3.F.
     *
     * <p>Calls {@link Storage#value(Key)} directly, with NO upfront {@link
     * Storage#exists(Key)} probe: {@link CachedBlobStorage#exists(Key)} and
     * {@link CachedBlobStorage#value(Key)} each independently consult the
     * {@code StorageIndex} (and, on a miss, independently single-flight a
     * blob-store call -- a {@code HEAD} for {@code exists()}, a {@code GET}
     * for {@code value()}), so calling both in sequence, as {@link
     * GenericArtifactSlice} does, is a redundant SECOND index consult on a
     * warm hit and a redundant {@code HEAD} before the {@code GET} on a cold
     * key. Existence is instead derived from whether {@code value()} itself
     * fails with a {@link ValueNotFoundException} -- the single path the
     * spec's acceptance criterion calls for.</p>
     *
     * <p>Response shape (status, {@code Accept-Ranges}, {@code
     * Content-Length}, body) is intentionally identical to {@link
     * GenericArtifactSlice}'s so {@link RangeSlice} (which wraps whichever
     * slice {@link #selectArtifactSlice()} picks) and HEAD handling continue
     * to work unchanged.</p>
     *
     * @since 2.3.0
     */
    private static final class IndexBackedArtifactSlice implements Slice {

        /**
         * Storage instance (index-backed; may be {@code SubStorage}-wrapped
         * for repo scoping).
         */
        private final Storage storage;

        /**
         * Ctor.
         *
         * @param storage Storage to serve artifacts from.
         */
        IndexBackedArtifactSlice(final Storage storage) {
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
            return this.storage.value(key).thenApply(content -> {
                final ResponseBuilder builder = ResponseBuilder.ok()
                    .header("Accept-Ranges", "bytes")
                    .body(content);
                content.size().ifPresent(size -> builder.header("Content-Length", String.valueOf(size)));
                return builder.build();
            }).exceptionally(throwable -> IndexBackedArtifactSlice.toResponse(key, throwable));
        }

        private static Response toResponse(final Key key, final Throwable throwable) {
            final Response response;
            if (IndexBackedArtifactSlice.isNotFound(throwable)) {
                response = ResponseBuilder.notFound().build();
            } else {
                EcsLogger.error("com.auto1.pantera.http")
                    .message("Failed to serve artifact at key: " + key.string())
                    .eventCategory("file")
                    .eventAction("artifact_serve")
                    .eventOutcome("failure")
                    .error(throwable)
                    .field("log.source", "application")
                    .log();
                response = ResponseBuilder.internalError()
                    .textBody("Failed to serve artifact: " + throwable.getMessage())
                    .build();
            }
            return response;
        }

        private static boolean isNotFound(final Throwable throwable) {
            Throwable cause = throwable;
            while (cause != null) {
                if (cause instanceof ValueNotFoundException) {
                    return true;
                }
                cause = cause.getCause();
            }
            return false;
        }
    }
}
