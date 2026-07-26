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
import com.auto1.pantera.asto.SubStorage;
import com.auto1.pantera.asto.ValueNotFoundException;
import com.auto1.pantera.asto.blob.CachedBlobStorage;
import com.auto1.pantera.asto.blob.DownloadMode;
import com.auto1.pantera.asto.blob.DownloadPolicy;
import com.auto1.pantera.asto.blob.PresignResolver;
import com.auto1.pantera.asto.cache.OptimizedStorageCache;
import com.auto1.pantera.asto.fs.FileStorage;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.headers.Location;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.metrics.MicrometerMetrics;

import java.net.URI;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

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
     * WS1.7 (spec {@code WS1-storage-for-scale.md} &sect;3.B2) per-repo
     * presigned-direct-download policy. {@link DownloadPolicy#streamOnly()}
     * (the one-arg-ctor default) is byte-identical to pre-WS1.7 behaviour:
     * the redirect branch below is never entered, every GET streams.
     */
    private final DownloadPolicy policy;

    /**
     * Repo name for the {@code pantera.storage.download.decision} metric tag,
     * derived from the outermost {@link SubStorage} prefix (a repo's storage
     * is {@code SubStorage(repoName, &lt;alias storage&gt;)} -- see {@code
     * RepoConfig#from}). Only consulted when a redirect decision is actually
     * recorded (i.e. {@code policy.mode() != STREAM}).
     */
    private final String repoName;

    /**
     * Per-key redirect gate. Returns {@code true} only for keys that are pure
     * binary artifacts -- never metadata, indexes, signatures, or checksum
     * sidecars. Formats whose byte and metadata routes share ONE catch-all
     * {@code StorageArtifactSlice} (gem, rpm, helm, debian, files) pass a
     * predicate that isolates the redirectable binaries; byte-only routes
     * (npm {@code .tgz}, pypi {@code .whl}, conda, go) pass the always-true
     * default. A key the predicate rejects is streamed exactly as under
     * {@link DownloadPolicy#streamOnly()}, so a redirect can never bypass a
     * client-visible metadata path.
     */
    private final Predicate<Key> redirectable;

    /**
     * Ctor -- stream-only (pre-WS1.7 behaviour, no redirect ever attempted).
     *
     * @param storage Storage to serve artifacts from
     */
    public StorageArtifactSlice(final Storage storage) {
        this(storage, DownloadPolicy.streamOnly());
    }

    /**
     * Ctor with an explicit WS1.7 download policy -- every key on the route is
     * redirect-eligible (byte-only routes such as npm {@code .tgz}). Delegates
     * to the three-arg ctor with an always-true predicate, so the four
     * already-wired byte-only routes are byte-identical.
     *
     * <p>Only the concrete artifact-byte route(s) of a format should pass a
     * non-{@link DownloadPolicy#streamOnly()} policy here -- metadata routes
     * (packument, {@code maven-metadata.xml}, PyPI simple index, checksum/
     * signature sidecars, ...) MUST keep the stream-only default so a redirect
     * never bypasses cooldown filtering, checksum recomputation, or
     * generated-metadata correctness.</p>
     *
     * @param storage Storage to serve artifacts from
     * @param policy WS1.7 download policy for this route
     */
    public StorageArtifactSlice(final Storage storage, final DownloadPolicy policy) {
        this(storage, policy, key -> true);
    }

    /**
     * Ctor with an explicit WS1.7 download policy and a per-key redirect gate.
     *
     * <p>For formats that serve BOTH artifact bytes AND metadata/sidecars
     * through one catch-all route (gem, rpm, helm, debian, files), {@code
     * redirectable} MUST return {@code true} ONLY for pure binary-artifact
     * keys and {@code false} for every metadata/index/signature/checksum key.
     * A rejected key streams exactly as {@link DownloadPolicy#streamOnly()}
     * would -- streaming is always safe; redirecting a metadata file is
     * never acceptable.</p>
     *
     * @param storage Storage to serve artifacts from
     * @param policy WS1.7 download policy for this route
     * @param redirectable Predicate that is {@code true} only for binary
     *  artifact keys (never metadata/sidecars)
     */
    public StorageArtifactSlice(
        final Storage storage,
        final DownloadPolicy policy,
        final Predicate<Key> redirectable
    ) {
        this.storage = storage;
        this.policy = policy;
        this.redirectable = redirectable;
        this.repoName = StorageArtifactSlice.repoNameOf(storage);
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line,
        final Headers headers,
        final Content body
    ) {
        // WS1.7: on a redirect-eligible GET, answer 302 to a presigned URL
        // when one is currently possible (durably present + presigner
        // configured); otherwise fall through to the UNCHANGED streaming
        // serve. STREAM mode and non-GET methods never reach the resolver.
        final Optional<URI> presigned = this.presignedFor(line);
        if (presigned.isPresent()) {
            this.recordDecision("redirect");
            final Response redirect = ResponseBuilder.found()
                .header(new Location(presigned.get().toString()))
                .build();
            // Consume the (empty) GET body -- reactive bodies must always be
            // drained, even when we do not serve them.
            return body.asBytesFuture().thenApply(ignored -> redirect);
        }
        if (this.policy.mode() != DownloadMode.STREAM && line.method() == RqMethod.GET) {
            this.recordDecision("stream");
        }
        // Dispatch to storage-specific implementation
        final Slice delegate = this.selectArtifactSlice();
        return delegate.response(line, headers, body);
    }

    /**
     * Resolve the presigned URL for this request, or empty when a redirect is
     * not applicable (stream-only policy, non-GET method, no presigner in the
     * storage composition, or the object is not yet durably present).
     *
     * @param line Request line
     * @return Presigned URL to redirect to, or empty to stream
     */
    private Optional<URI> presignedFor(final RequestLine line) {
        if (this.policy.mode() == DownloadMode.STREAM || line.method() != RqMethod.GET) {
            return Optional.empty();
        }
        final Key key = new Key.From(line.uri().getPath().replaceAll("^/+", ""));
        if (!this.redirectable.test(key)) {
            // Metadata/sidecar key on a shared catch-all route: stream it,
            // exactly as stream-only would -- never a 302.
            return Optional.empty();
        }
        return PresignResolver.resolve(this.storage, key)
            .flatMap(target -> target.presignIfDurable(this.policy.presignTtlSeconds()));
    }

    /**
     * Record the WS1.7 redirect-vs-stream serving decision (only ever called
     * for redirect-eligible routes -- {@code policy.mode() != STREAM}).
     *
     * @param decision {@code "redirect"} or {@code "stream"}
     */
    private void recordDecision(final String decision) {
        if (MicrometerMetrics.isInitialized()) {
            MicrometerMetrics.getInstance().recordDownloadDecision(this.repoName, decision);
        }
    }

    /**
     * Best-effort repo name for the download-decision metric tag: the
     * outermost {@link SubStorage} prefix of a repo-scoped storage is the repo
     * name by construction. Bounded by {@code RepoNameMeterFilter} regardless.
     *
     * @param storage Repo-scoped storage
     * @return Repo name, or {@code "unknown"} if the storage is not prefixed
     */
    private static String repoNameOf(final Storage storage) {
        return storage instanceof SubStorage sub ? sub.prefix().string() : "unknown";
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
