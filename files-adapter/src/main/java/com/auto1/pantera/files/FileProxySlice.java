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
package com.auto1.pantera.files;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.cache.Cache;
import com.auto1.pantera.asto.cache.CacheControl;
import com.auto1.pantera.asto.cache.StreamThroughCache;
import com.auto1.pantera.asto.cache.FromStorageCache;
import com.auto1.pantera.asto.cache.Remote;
import com.auto1.pantera.cooldown.api.CooldownRequest;
import com.auto1.pantera.cooldown.response.CooldownResponseRegistry;
import com.auto1.pantera.cooldown.api.CooldownService;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.client.ClientSlices;
import com.auto1.pantera.http.client.UriClientSlice;
import com.auto1.pantera.http.client.auth.AuthClientSlice;
import com.auto1.pantera.http.client.auth.Authenticator;
import com.auto1.pantera.http.headers.Login;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.slice.KeyFromPath;
import com.auto1.pantera.scheduling.ArtifactEvent;
import com.auto1.pantera.scheduling.RepositoryEvents;
import io.reactivex.Flowable;

import java.net.URI;
import java.nio.ByteBuffer;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Binary files proxy {@link Slice} implementation.
 */
public final class FileProxySlice implements Slice {

    /**
     * Repository type.
     */
    private static final String REPO_TYPE = "file-proxy";

    /**
     * Remote slice.
     */
    private final Slice remote;

    /**
     * Cache.
     */
    private final Cache cache;

    /**
     * Artifact events queue.
     */
    private final Optional<Queue<ArtifactEvent>> events;

    /**
     * Reository name.
     */
    private final String rname;

    /**
     * Cooldown service.
     */
    private final CooldownService cooldown;

    /**
     * Cooldown inspector.
     */
    private final FilesCooldownInspector inspector;

    /**
     * Upstream URL for metrics.
     */
    private final String upstreamUrl;

    /**
     * Optional storage for cache-first lookup (offline mode support).
     */
    private final Optional<Storage> storage;


    /**
     * New files proxy slice.
     * @param clients HTTP clients
     * @param remote Remote URI
     */
    public FileProxySlice(final ClientSlices clients, final URI remote) {
        this(new UriClientSlice(clients, remote), Cache.NOP, Optional.empty(), FilesSlice.ANY_REPO,
            com.auto1.pantera.cooldown.impl.NoopCooldownService.INSTANCE, "unknown", Optional.empty());
    }

    /**
     * New files proxy slice.
     * @param clients HTTP clients
     * @param remote Remote URI
     * @param auth Authenticator
     * @param asto Cache storage
     */
    public FileProxySlice(final ClientSlices clients, final URI remote,
        final Authenticator auth, final Storage asto) {
        this(
            new AuthClientSlice(new UriClientSlice(clients, remote), auth),
            new StreamThroughCache(asto), Optional.empty(), FilesSlice.ANY_REPO,
            com.auto1.pantera.cooldown.impl.NoopCooldownService.INSTANCE, remote.toString(), Optional.of(asto)
        );
    }

    /**
     * New files proxy slice.
     * @param clients HTTP clients
     * @param remote Remote URI
     * @param asto Cache storage
     * @param events Artifact events
     * @param rname Repository name
     */
    public FileProxySlice(final ClientSlices clients, final URI remote, final Storage asto,
        final Queue<ArtifactEvent> events, final String rname) {
        this(
            new AuthClientSlice(new UriClientSlice(clients, remote), Authenticator.ANONYMOUS),
            new StreamThroughCache(asto), Optional.of(events), rname,
            com.auto1.pantera.cooldown.impl.NoopCooldownService.INSTANCE, remote.toString(), Optional.of(asto)
        );
    }

    /**
     * @param remote Remote slice
     * @param cache Cache
     */
    FileProxySlice(final Slice remote, final Cache cache) {
        this(remote, cache, Optional.empty(), FilesSlice.ANY_REPO,
            com.auto1.pantera.cooldown.impl.NoopCooldownService.INSTANCE, "unknown", Optional.empty());
    }

    /**
     * @param remote Remote slice
     * @param cache Cache
     * @param events Artifact events
     * @param rname Repository name
     * @param cooldown Cooldown service
     */
    public FileProxySlice(
        final Slice remote, final Cache cache,
        final Optional<Queue<ArtifactEvent>> events, final String rname,
        final CooldownService cooldown
    ) {
        this(remote, cache, events, rname, cooldown, "unknown", Optional.empty());
    }

    /**
     * Full constructor with upstream URL for metrics.
     * @param remote Remote slice
     * @param cache Cache
     * @param events Artifact events
     * @param rname Repository name
     * @param cooldown Cooldown service
     * @param upstreamUrl Upstream URL for metrics
     */
    public FileProxySlice(
        final Slice remote, final Cache cache,
        final Optional<Queue<ArtifactEvent>> events, final String rname,
        final CooldownService cooldown, final String upstreamUrl
    ) {
        this(remote, cache, events, rname, cooldown, upstreamUrl, Optional.empty());
    }

    /**
     * Full constructor with upstream URL and storage for cache-first lookup.
     * @param remote Remote slice
     * @param cache Cache
     * @param events Artifact events
     * @param rname Repository name
     * @param cooldown Cooldown service
     * @param upstreamUrl Upstream URL for metrics
     * @param storage Optional storage for cache-first lookup
     */
    public FileProxySlice(
        final Slice remote, final Cache cache,
        final Optional<Queue<ArtifactEvent>> events, final String rname,
        final CooldownService cooldown, final String upstreamUrl,
        final Optional<Storage> storage
    ) {
        this.remote = remote;
        this.cache = cache;
        this.events = events;
        this.rname = rname;
        this.cooldown = cooldown;
        this.inspector = new FilesCooldownInspector(remote);
        this.upstreamUrl = upstreamUrl;
        this.storage = storage;
    }

    @Override
    public CompletableFuture<Response> response(
        RequestLine line, Headers rqheaders, Content pub
    ) {
        final AtomicReference<Headers> rshdr = new AtomicReference<>();
        final KeyFromPath key = new KeyFromPath(line.uri().getPath());
        final String artifact = line.uri().getPath();
        final String user = new Login(rqheaders).getValue();

        // CRITICAL FIX: Check cache FIRST before any network calls (cooldown/inspector)
        // This ensures offline mode works - serve cached content even when upstream is down
        return this.checkCacheFirst(line, key, artifact, user, rshdr);
    }

    /**
     * Check cache first before evaluating cooldown. This ensures offline mode works -
     * cached content is served even when upstream/network is unavailable.
     *
     * @param line Request line
     * @param key Cache key
     * @param artifact Artifact path
     * @param user User name
     * @param rshdr Response headers reference
     * @return Response future
     */
    private CompletableFuture<Response> checkCacheFirst(
        final RequestLine line,
        final KeyFromPath key,
        final String artifact,
        final String user,
        final AtomicReference<Headers> rshdr
    ) {
        // If no storage is configured, skip cache-first check and go directly to cooldown
        if (this.storage.isEmpty()) {
            return this.evaluateCooldownAndFetch(line, key, artifact, user, rshdr);
        }
        // Check storage cache FIRST before any network calls
        // Use FromStorageCache pattern: check storage directly, serve if present
        return new FromStorageCache(this.storage.get()).load(key, Remote.EMPTY, CacheControl.Standard.ALWAYS)
            .thenCompose(cached -> {
                if (cached.isPresent()) {
                    // Cache HIT - serve immediately without any network calls
                    return CompletableFuture.completedFuture(
                        ResponseBuilder.ok()
                            .body(cached.get())
                            .build()
                    );
                }
                // Cache MISS - now we need network, evaluate cooldown first
                return this.evaluateCooldownAndFetch(line, key, artifact, user, rshdr);
            }).toCompletableFuture();
    }

    /**
     * Evaluate cooldown (if applicable) then fetch from upstream.
     * Only called when cache miss - requires network access.
     *
     * @param line Request line
     * @param key Cache key
     * @param artifact Artifact path
     * @param user User name
     * @param rshdr Response headers reference
     * @return Response future
     */
    private CompletableFuture<Response> evaluateCooldownAndFetch(
        final RequestLine line,
        final KeyFromPath key,
        final String artifact,
        final String user,
        final AtomicReference<Headers> rshdr
    ) {
        final CooldownRequest request = new CooldownRequest(
            FileProxySlice.REPO_TYPE,
            this.rname,
            artifact,
            "latest",
            user,
            java.time.Instant.now()
        );

        return this.cooldown.evaluate(request, this.inspector)
            .thenCompose(result -> {
                if (result.blocked()) {
                    return java.util.concurrent.CompletableFuture.completedFuture(
                        CooldownResponseRegistry.instance()
                            .get(FileProxySlice.REPO_TYPE)
                            .forbidden(result.block().orElseThrow())
                    );
                }
                final long startTime = System.currentTimeMillis();
                return this.cache.load(key,
                new Remote.WithErrorHandling(
                    () -> {
                        final CompletableFuture<Optional<? extends Content>> promise = new CompletableFuture<>();
                        this.remote.response(line, Headers.EMPTY, Content.EMPTY)
                            .thenApply(
                                response -> {
                                    final long duration = System.currentTimeMillis() - startTime;
                                    final CompletableFuture<Void> term = new CompletableFuture<>();
                                    rshdr.set(response.headers());

                                    if (response.status().success()) {
                                        this.recordProxyMetric("success", duration);
                                        final java.util.concurrent.atomic.AtomicLong totalSize =
                                            new java.util.concurrent.atomic.AtomicLong(0);
                                        final Flowable<ByteBuffer> body = Flowable.fromPublisher(response.body())
                                            .doOnNext(buf -> totalSize.addAndGet(buf.remaining()))
                                            .doOnError(term::completeExceptionally)
                                            .doOnTerminate(() -> term.complete(null));

                                        promise.complete(Optional.of(new Content.From(body)));

                                        if (this.events.isPresent()) {
                                            final String finalArtifact = key.string();
                                            term.thenRun(() -> {
                                                final long size = totalSize.get();
                                                String aname = finalArtifact;
                                                // Exclude repo name prefix if present
                                                if (this.rname != null && !this.rname.isEmpty()
                                                    && aname.startsWith(this.rname + "/")) {
                                                    aname = aname.substring(this.rname.length() + 1);
                                                }
                                                // Replace folder separators with dots
                                                aname = aname.replace('/', '.');
                                                this.events.get().add(
                                                    new ArtifactEvent(
                                                        FileProxySlice.REPO_TYPE, this.rname, user,
                                                        aname,
                                                        RepositoryEvents.detectFileVersion(
                                                            FileProxySlice.REPO_TYPE, aname
                                                        ),
                                                        size
                                                    )
                                                );
                                            });
                                        }
                                    } else {
                                        // CRITICAL: Consume body to prevent Vert.x request leak
                                        response.body().asBytesFuture().whenComplete((ignored, error) -> {
                                            final String metricResult = response.status().code() == 404 ? "not_found" :
                                                (response.status().code() >= 500 ? "error" : "client_error");
                                            this.recordProxyMetric(metricResult, duration);
                                            if (response.status().code() >= 500) {
                                                this.recordUpstreamErrorMetric(new RuntimeException("HTTP " + response.status().code()));
                                            }
                                            promise.complete(Optional.empty());
                                            term.complete(null);
                                        });
                                    }
                                    return term;
                                })
                            .exceptionally(error -> {
                                final long duration = System.currentTimeMillis() - startTime;
                                this.recordProxyMetric("exception", duration);
                                this.recordUpstreamErrorMetric(error);
                                promise.complete(Optional.empty());
                                return null;
                            });
                        return promise;
                    }
                ),
                CacheControl.Standard.ALWAYS
            ).toCompletableFuture()
            .handle((content, throwable) -> {
                    if (throwable == null && content.isPresent()) {
                        return ResponseBuilder.ok()
                            .headers(rshdr.get())
                            .body(content.get())
                            .build();
                    }
                    return ResponseBuilder.notFound().build();
                }
            );
            });
    }

    /**
     * Record proxy request metric.
     */
    private void recordProxyMetric(final String result, final long duration) {
        this.recordMetric(() -> {
            if (com.auto1.pantera.metrics.MicrometerMetrics.isInitialized()) {
                com.auto1.pantera.metrics.MicrometerMetrics.getInstance()
                    .recordProxyRequest(this.rname, this.upstreamUrl, result, duration);
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
                    .recordUpstreamError(this.rname, this.upstreamUrl, errorType);
            }
        });
    }

    /**
     * Record metric safely (only if metrics are enabled).
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private void recordMetric(final Runnable metric) {
        try {
            if (com.auto1.pantera.metrics.PanteraMetrics.isEnabled()) {
                metric.run();
            }
        } catch (final Exception ex) {
            EcsLogger.debug("com.auto1.pantera.files")
                .message("Failed to record metric")
                .error(ex)
                .log();
        }
    }
}
