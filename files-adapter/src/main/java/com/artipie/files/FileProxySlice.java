/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.files;

import com.artipie.asto.Content;
import com.artipie.asto.Storage;
import com.artipie.asto.cache.Cache;
import com.artipie.asto.cache.CacheControl;
import com.artipie.asto.cache.FromRemoteCache;
import com.artipie.asto.cache.Remote;
import com.artipie.cooldown.CooldownRequest;
import com.artipie.cooldown.CooldownResponses;
import com.artipie.cooldown.CooldownService;
import com.artipie.http.Headers;
import com.artipie.http.ResponseBuilder;
import com.artipie.http.Response;
import com.artipie.http.Slice;
import com.artipie.http.client.ClientSlices;
import com.artipie.http.client.UriClientSlice;
import com.artipie.http.client.auth.AuthClientSlice;
import com.artipie.http.client.auth.Authenticator;
import com.artipie.http.headers.Login;
import com.artipie.http.headers.ContentLength;
import com.artipie.http.rq.RequestLine;
import com.artipie.http.rq.RqHeaders;
import com.artipie.http.slice.KeyFromPath;
import com.artipie.scheduling.ArtifactEvent;
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
     * New files proxy slice.
     * @param clients HTTP clients
     * @param remote Remote URI
     */
    public FileProxySlice(final ClientSlices clients, final URI remote) {
        this(new UriClientSlice(clients, remote), Cache.NOP, Optional.empty(), FilesSlice.ANY_REPO,
            com.artipie.cooldown.NoopCooldownService.INSTANCE, "unknown");
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
            new FromRemoteCache(asto), Optional.empty(), FilesSlice.ANY_REPO,
            com.artipie.cooldown.NoopCooldownService.INSTANCE, remote.toString()
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
            new FromRemoteCache(asto), Optional.of(events), rname,
            com.artipie.cooldown.NoopCooldownService.INSTANCE, remote.toString()
        );
    }

    /**
     * @param remote Remote slice
     * @param cache Cache
     */
    FileProxySlice(final Slice remote, final Cache cache) {
        this(remote, cache, Optional.empty(), FilesSlice.ANY_REPO,
            com.artipie.cooldown.NoopCooldownService.INSTANCE, "unknown");
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
        this(remote, cache, events, rname, cooldown, "unknown");
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
        this.remote = remote;
        this.cache = cache;
        this.events = events;
        this.rname = rname;
        this.cooldown = cooldown;
        this.inspector = new FilesCooldownInspector(remote);
        this.upstreamUrl = upstreamUrl;
    }

    @Override
    public CompletableFuture<Response> response(
        RequestLine line, Headers rqheaders, Content pub
    ) {
        final AtomicReference<Headers> rshdr = new AtomicReference<>();
        final KeyFromPath key = new KeyFromPath(line.uri().getPath());
        final String artifact = line.uri().getPath();
        final String user = new Login(rqheaders).getValue();
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
                        CooldownResponses.forbidden(result.block().orElseThrow())
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
                                        final Flowable<ByteBuffer> body = Flowable.fromPublisher(response.body())
                                            .doOnError(term::completeExceptionally)
                                            .doOnTerminate(() -> term.complete(null));

                                        promise.complete(Optional.of(new Content.From(body)));

                                        if (this.events.isPresent()) {
                                            final long size =
                                                new RqHeaders(rshdr.get(), ContentLength.NAME)
                                                    .stream().findFirst().map(Long::parseLong)
                                                    .orElse(0L);
                                            String aname = key.string();
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
                                                    aname, "UNKNOWN", size
                                                )
                                            );
                                        }
                                    } else {
                                        final String metricResult = response.status().code() == 404 ? "not_found" :
                                            (response.status().code() >= 500 ? "error" : "client_error");
                                        this.recordProxyMetric(metricResult, duration);
                                        if (response.status().code() >= 500) {
                                            this.recordUpstreamErrorMetric(new RuntimeException("HTTP " + response.status().code()));
                                        }
                                        promise.complete(Optional.empty());
                                    }
                                    return term;
                                })
                            .exceptionally(error -> {
                                final long duration = System.currentTimeMillis() - startTime;
                                this.recordProxyMetric("exception", duration);
                                this.recordUpstreamErrorMetric(error);
                                throw new java.util.concurrent.CompletionException(error);
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
            if (com.artipie.metrics.MicrometerMetrics.isInitialized()) {
                com.artipie.metrics.MicrometerMetrics.getInstance()
                    .recordProxyRequest(this.rname, this.upstreamUrl, result, duration);
            }
        });
    }

    /**
     * Record upstream error metric.
     */
    private void recordUpstreamErrorMetric(final Throwable error) {
        this.recordMetric(() -> {
            if (com.artipie.metrics.MicrometerMetrics.isInitialized()) {
                String errorType = "unknown";
                if (error instanceof java.util.concurrent.TimeoutException) {
                    errorType = "timeout";
                } else if (error instanceof java.net.ConnectException) {
                    errorType = "connection";
                }
                com.artipie.metrics.MicrometerMetrics.getInstance()
                    .recordUpstreamError(this.rname, this.upstreamUrl, errorType);
            }
        });
    }

    /**
     * Record metric safely (only if metrics are enabled).
     */
    @SuppressWarnings({"PMD.AvoidCatchingGenericException", "PMD.EmptyCatchBlock"})
    private void recordMetric(final Runnable metric) {
        try {
            if (com.artipie.metrics.ArtipieMetrics.isEnabled()) {
                metric.run();
            }
        } catch (final Exception ex) {
            // Ignore metric errors - don't fail requests
        }
    }
}
