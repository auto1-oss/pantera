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
import com.auto1.pantera.asto.metrics.BlobStoreMetricsCollector;
import java.util.Collection;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * {@link BlobStore} decorator recording count + latency + error/throttle
 * metrics for every GET/HEAD/PUT/DELETE/LIST call (WS1.6, spec {@code
 * WS1-storage-for-scale.md} &sect;3.G) -- reverses the {@code RepoConfig}
 * "no MicrometerStorage" decision, but SCOPED to the blob-store tier only:
 * the outer {@link com.auto1.pantera.asto.Storage} surface repositories are
 * built from (the {@code exists}/{@code value}/{@code save}/... hot path
 * {@code CachedBlobStorage}/{@code DiskCacheStorage} expose) stays
 * unwrapped, exactly as that decision intended -- wrapping THAT layer would
 * re-add per-call overhead on the very hot path WS1.1 removed the S3 HEAD
 * from. This decorator instead wraps the reference {@link
 * com.auto1.pantera.asto.s3.S3Storage} BEFORE it is handed to {@link
 * CachedBlobStorage} (see {@code S3StorageFactory}), so only the calls that
 * actually reach the object store are metered -- a hit inside {@code
 * CachedBlobStorage} never reaches this class at all.
 *
 * <p>Backend classification is a small, code-defined, bounded set (never the
 * bucket/endpoint identifier, which is unbounded per deployment) -- see
 * {@link #backendKind(BlobStore)}. Outcome classification distinguishes
 * throttling (a distinct, alertable "backend is rate-limiting us" signal)
 * from a hard error via a best-effort, backend-agnostic heuristic on the
 * exception's class name/message (see {@link #classify(Throwable)}) -- this
 * class deliberately has no compile-time dependency on the AWS SDK or any
 * other backend-specific exception type, so it works unchanged for a future
 * native GCS/Azure {@link BlobStore} (WS1.8).</p>
 *
 * @since 2.3.0
 */
public final class MeteredBlobStore implements BlobStore, AutoCloseable {

    /**
     * Delegate this decorator times and counts calls against.
     */
    private final BlobStore delegate;

    /**
     * Bounded backend-kind tag, derived once at construction from {@link
     * #delegate}'s implementation class.
     */
    private final String backend;

    /**
     * New metered view over {@code delegate}.
     *
     * @param delegate Real {@link BlobStore} implementation.
     */
    public MeteredBlobStore(final BlobStore delegate) {
        this.delegate = delegate;
        this.backend = MeteredBlobStore.backendKind(delegate);
    }

    @Override
    public CompletableFuture<Boolean> exists(final Key key) {
        return this.timed("exists", this.delegate.exists(key));
    }

    @Override
    public CompletableFuture<? extends Meta> head(final Key key) {
        return this.timed("head", this.delegate.head(key));
    }

    @Override
    public CompletableFuture<Content> get(final Key key) {
        return this.timed("get", this.delegate.get(key));
    }

    @Override
    public CompletableFuture<Void> put(final Key key, final Content content) {
        return this.timed("put", this.delegate.put(key, content));
    }

    @Override
    public CompletableFuture<Void> delete(final Key key) {
        return this.timed("delete", this.delegate.delete(key));
    }

    @Override
    public CompletableFuture<Collection<Key>> list(final Key prefix) {
        return this.timed("list", this.delegate.list(prefix));
    }

    @Override
    public CompletableFuture<ListResult> list(final Key prefix, final String delimiter) {
        return this.timed("list", this.delegate.list(prefix, delimiter));
    }

    @Override
    public String identifier() {
        return this.delegate.identifier();
    }

    @Override
    public void close() throws Exception {
        if (this.delegate instanceof AutoCloseable) {
            ((AutoCloseable) this.delegate).close();
        }
    }

    /**
     * Times {@code future} and records its outcome under {@code operation}
     * once it completes, then returns it unchanged (transparent pass-through
     * -- this decorator never alters the delegate's result or error).
     *
     * @param operation Bounded {@code BlobStore} verb.
     * @param future Delegate call already in flight.
     * @param <T> Result type.
     * @return {@code future}, unchanged.
     */
    private <T> CompletableFuture<T> timed(final String operation, final CompletableFuture<T> future) {
        final long startNs = System.nanoTime();
        return future.whenComplete((result, err) -> {
            final long durationNs = System.nanoTime() - startNs;
            final String outcome = err == null
                ? BlobStoreMetricsCollector.OUTCOME_SUCCESS
                : MeteredBlobStore.classify(err);
            BlobStoreMetricsCollector.record(this.backend, operation, outcome, durationNs);
        });
    }

    /**
     * Bounded backend-kind classification from the delegate's implementation
     * class -- NEVER the bucket/endpoint (unbounded per deployment). Extend
     * this switch, not the tag's shape, when a native GCS/Azure {@link
     * BlobStore} lands (WS1.8).
     *
     * @param delegate Real {@link BlobStore} implementation.
     * @return Bounded backend-kind string.
     */
    private static String backendKind(final BlobStore delegate) {
        final String name = delegate.getClass().getSimpleName().toLowerCase(Locale.ROOT);
        final String kind;
        if (name.contains("s3")) {
            kind = "s3";
        } else if (name.contains("gcs") || name.contains("googlecloud")) {
            kind = "gcs";
        } else if (name.contains("azure")) {
            kind = "azure";
        } else {
            kind = "other";
        }
        return kind;
    }

    /**
     * Best-effort, backend-agnostic throttling classification: a backend
     * that signals rate-limiting typically does so via an exception class
     * name or message containing one of a small set of well-known tokens
     * (AWS S3's {@code SlowDown}/{@code TooManyRequests}/{@code
     * RequestLimitExceeded}, or a raw HTTP {@code 429}/{@code 503}). This
     * intentionally has no compile-time dependency on any backend SDK's
     * exception hierarchy -- {@code MeteredBlobStore} wraps whatever {@link
     * BlobStore} it is given.
     *
     * @param err Failure from the delegate call.
     * @return {@link BlobStoreMetricsCollector#OUTCOME_THROTTLED} or {@link
     *  BlobStoreMetricsCollector#OUTCOME_ERROR}.
     */
    private static String classify(final Throwable err) {
        Throwable cause = err;
        while (cause != null) {
            if (MeteredBlobStore.looksThrottled(cause)) {
                return BlobStoreMetricsCollector.OUTCOME_THROTTLED;
            }
            cause = cause.getCause();
        }
        return BlobStoreMetricsCollector.OUTCOME_ERROR;
    }

    private static boolean looksThrottled(final Throwable err) {
        final String message = err.getMessage();
        final String haystack = (
            err.getClass().getSimpleName() + ' ' + (message == null ? "" : message)
        ).toLowerCase(Locale.ROOT);
        return haystack.contains("slowdown")
            || haystack.contains("toomanyrequests")
            || haystack.contains("requestlimitexceeded")
            || haystack.contains("throttl")
            || haystack.contains(" 429")
            || haystack.contains(" 503");
    }
}
