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
package com.auto1.pantera.docker.cache;

import com.auto1.pantera.docker.Blob;
import com.auto1.pantera.docker.Digest;
import com.auto1.pantera.docker.Layers;
import com.auto1.pantera.docker.asto.BlobSource;
import com.auto1.pantera.http.log.EcsLogger;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

/**
 * Cache implementation of {@link Layers}.
 *
 * @since 0.3
 */
public final class CacheLayers implements Layers {

    /**
     * Origin layers.
     */
    private final Layers origin;

    /**
     * Cache layers.
     */
    private final Layers cache;

    /**
     * Repository name for metrics.
     */
    private final String repoName;

    /**
     * Upstream URL for metrics.
     */
    private final String upstreamUrl;

    /**
     * Ctor.
     *
     * @param origin Origin layers.
     * @param cache Cache layers.
     */
    public CacheLayers(final Layers origin, final Layers cache) {
        this(origin, cache, "unknown", "unknown");
    }

    /**
     * Ctor with metrics parameters.
     *
     * @param origin Origin layers.
     * @param cache Cache layers.
     * @param repoName Repository name for metrics.
     * @param upstreamUrl Upstream URL for metrics.
     */
    public CacheLayers(final Layers origin, final Layers cache, final String repoName, final String upstreamUrl) {
        this.origin = origin;
        this.cache = cache;
        this.repoName = repoName;
        this.upstreamUrl = upstreamUrl;
    }

    @Override
    public CompletableFuture<Digest> put(final BlobSource source) {
        throw new UnsupportedOperationException();
    }

    @Override
    public CompletableFuture<Void> mount(final Blob blob) {
        throw new UnsupportedOperationException();
    }

    @Override
    public CompletableFuture<Optional<Blob>> get(final Digest digest) {
        return this.cache.get(digest).handle(
            (cached, throwable) -> {
                final CompletionStage<Optional<Blob>> result;
                if (throwable == null) {
                    if (cached.isPresent()) {
                        result = CompletableFuture.completedFuture(cached);
                    } else {
                        // Cache miss - fetch from origin, wrap with CachingBlob for streaming cache
                        final long startTime = System.currentTimeMillis();
                        result = this.origin.get(digest)
                            .thenApply(blob -> {
                                final long duration = System.currentTimeMillis() - startTime;
                                if (blob.isPresent()) {
                                    this.recordProxyMetric("success", duration);
                                    return Optional.<Blob>of(
                                        new CachingBlob(blob.get(), this.cache)
                                    );
                                } else {
                                    this.recordProxyMetric("not_found", duration);
                                    return blob;
                                }
                            })
                            .exceptionally(error -> {
                                final long duration = System.currentTimeMillis() - startTime;
                                this.recordProxyMetric("exception", duration);
                                this.recordUpstreamErrorMetric(error);
                                return cached;
                            });
                    }
                } else {
                    // Cache error - fetch from origin, wrap with CachingBlob
                    final long startTime = System.currentTimeMillis();
                    result = this.origin.get(digest)
                        .thenApply(blob -> {
                            final long duration = System.currentTimeMillis() - startTime;
                            if (blob.isPresent()) {
                                this.recordProxyMetric("success", duration);
                                return Optional.<Blob>of(
                                    new CachingBlob(blob.get(), this.cache)
                                );
                            } else {
                                this.recordProxyMetric("not_found", duration);
                                return blob;
                            }
                        })
                        .exceptionally(error -> {
                            final long duration = System.currentTimeMillis() - startTime;
                            this.recordProxyMetric("exception", duration);
                            this.recordUpstreamErrorMetric(error);
                            EcsLogger.warn("com.auto1.pantera.docker")
                                .message("Both cache and origin failed for blob")
                                .eventCategory("web")
                                .eventAction("blob_get")
                                .eventOutcome("failure")
                                .error(error)
                                .log();
                            return Optional.empty();
                        });
                }
                return result;
            }
        ).thenCompose(Function.identity());
    }

    /**
     * Record proxy request metric.
     */
    private void recordProxyMetric(final String result, final long duration) {
        this.recordMetric(() -> {
            if (com.auto1.pantera.metrics.MicrometerMetrics.isInitialized()) {
                com.auto1.pantera.metrics.MicrometerMetrics.getInstance()
                    .recordProxyRequest(this.repoName, this.upstreamUrl, result, duration);
            }
        });
    }

    /**
     * Record upstream error metric.
     */
    private void recordUpstreamErrorMetric(final Throwable error) {
        this.recordMetric(() -> {
            if (com.auto1.pantera.metrics.MicrometerMetrics.isInitialized()) {
                String errorType = "unknown";
                if (error instanceof java.util.concurrent.TimeoutException) {
                    errorType = "timeout";
                } else if (error instanceof java.net.ConnectException) {
                    errorType = "connection";
                }
                com.auto1.pantera.metrics.MicrometerMetrics.getInstance()
                    .recordUpstreamError(this.repoName, this.upstreamUrl, errorType);
            }
        });
    }

    /**
     * Record metric safely (only if metrics are enabled).
     */
    private void recordMetric(final Runnable metric) {
        try {
            if (com.auto1.pantera.metrics.PanteraMetrics.isEnabled()) {
                metric.run();
            }
        } catch (final Exception ex) {
            EcsLogger.debug("com.auto1.pantera.docker")
                .message("Failed to record metric")
                .error(ex)
                .log();
        }
    }
}
