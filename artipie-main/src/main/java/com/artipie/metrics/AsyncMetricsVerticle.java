/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.metrics;

import com.artipie.http.log.EcsLogger;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpServerRequest;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Async Metrics Verticle that handles Prometheus metrics scraping off the event loop.
 *
 * <p>This verticle solves the critical issue of Prometheus scraping blocking the Vert.x
 * event loop. The scrape() operation can take several seconds when there are many metrics
 * with high cardinality labels, causing all HTTP requests to stall.</p>
 *
 * <p>Key features:</p>
 * <ul>
 *   <li>Executes metrics scraping on worker thread pool, not event loop</li>
 *   <li>Caches scraped metrics to reduce CPU overhead (configurable TTL)</li>
 *   <li>Provides concurrent request deduplication (only one scrape in flight)</li>
 *   <li>Graceful degradation with stale cache on scrape errors</li>
 * </ul>
 *
 * <p>Deploy as worker verticle:</p>
 * <pre>
 * DeploymentOptions options = new DeploymentOptions()
 *     .setWorker(true)
 *     .setWorkerPoolName("metrics-pool")
 *     .setWorkerPoolSize(2);
 * vertx.deployVerticle(new AsyncMetricsVerticle(registry, port), options);
 * </pre>
 *
 * @since 1.20.11
 */
public final class AsyncMetricsVerticle extends AbstractVerticle {

    /**
     * Default metrics cache TTL in milliseconds.
     * Prometheus typically scrapes every 15-60 seconds, so 5s cache is reasonable.
     */
    private static final long DEFAULT_CACHE_TTL_MS = 5000L;

    /**
     * Maximum time to wait for a scrape operation before returning stale data.
     */
    private static final long SCRAPE_TIMEOUT_MS = 30000L;

    /**
     * Content-Type header for Prometheus metrics.
     */
    private static final String PROMETHEUS_CONTENT_TYPE = "text/plain; version=0.0.4; charset=utf-8";

    /**
     * The meter registry to scrape.
     */
    private final MeterRegistry registry;

    /**
     * Port to listen on for metrics endpoint.
     */
    private final int port;

    /**
     * Metrics endpoint path.
     */
    private final String path;

    /**
     * Cache TTL in milliseconds.
     */
    private final long cacheTtlMs;

    /**
     * Cached metrics content.
     */
    private final AtomicReference<CachedMetrics> cachedMetrics;

    /**
     * Lock to prevent concurrent scrapes.
     */
    private final ReentrantLock scrapeLock;

    /**
     * HTTP server instance.
     */
    private HttpServer server;

    /**
     * Create async metrics verticle with default settings.
     *
     * @param registry Meter registry to scrape
     * @param port Port to listen on
     */
    public AsyncMetricsVerticle(final MeterRegistry registry, final int port) {
        this(registry, port, "/metrics", DEFAULT_CACHE_TTL_MS);
    }

    /**
     * Create async metrics verticle with custom path.
     *
     * @param registry Meter registry to scrape
     * @param port Port to listen on
     * @param path Endpoint path (e.g., "/metrics")
     */
    public AsyncMetricsVerticle(final MeterRegistry registry, final int port, final String path) {
        this(registry, port, path, DEFAULT_CACHE_TTL_MS);
    }

    /**
     * Create async metrics verticle with full configuration.
     *
     * @param registry Meter registry to scrape
     * @param port Port to listen on
     * @param path Endpoint path
     * @param cacheTtlMs Cache TTL in milliseconds
     */
    public AsyncMetricsVerticle(
        final MeterRegistry registry,
        final int port,
        final String path,
        final long cacheTtlMs
    ) {
        this.registry = registry;
        this.port = port;
        this.path = path.startsWith("/") ? path : "/" + path;
        this.cacheTtlMs = cacheTtlMs;
        this.cachedMetrics = new AtomicReference<>(new CachedMetrics("", 0L));
        this.scrapeLock = new ReentrantLock();
    }

    @Override
    public void start(final Promise<Void> startPromise) {
        final HttpServerOptions options = new HttpServerOptions()
            .setPort(this.port)
            .setHost("0.0.0.0")
            .setIdleTimeout(60)
            .setTcpKeepAlive(true)
            .setTcpNoDelay(true);

        this.server = vertx.createHttpServer(options);

        this.server.requestHandler(this::handleRequest);

        this.server.listen(ar -> {
            if (ar.succeeded()) {
                EcsLogger.info("com.artipie.metrics.AsyncMetricsVerticle")
                    .message("Async metrics server started")
                    .eventCategory("configuration")
                    .eventAction("metrics_server_start")
                    .eventOutcome("success")
                    .field("destination.port", this.port)
                    .field("url.path", this.path)
                    .field("cache.ttl.ms", this.cacheTtlMs)
                    .log();
                startPromise.complete();
            } else {
                EcsLogger.error("com.artipie.metrics.AsyncMetricsVerticle")
                    .message("Failed to start async metrics server")
                    .eventCategory("configuration")
                    .eventAction("metrics_server_start")
                    .eventOutcome("failure")
                    .error(ar.cause())
                    .log();
                startPromise.fail(ar.cause());
            }
        });
    }

    @Override
    public void stop(final Promise<Void> stopPromise) {
        if (this.server != null) {
            this.server.close(ar -> {
                if (ar.succeeded()) {
                    EcsLogger.info("com.artipie.metrics.AsyncMetricsVerticle")
                        .message("Async metrics server stopped")
                        .eventCategory("configuration")
                        .eventAction("metrics_server_stop")
                        .eventOutcome("success")
                        .log();
                    stopPromise.complete();
                } else {
                    stopPromise.fail(ar.cause());
                }
            });
        } else {
            stopPromise.complete();
        }
    }

    /**
     * Handle incoming HTTP request.
     *
     * @param request HTTP request
     */
    private void handleRequest(final HttpServerRequest request) {
        final String requestPath = request.path();

        if (requestPath.equals(this.path) || requestPath.equals(this.path + "/")) {
            handleMetricsRequest(request);
        } else if (requestPath.equals("/health") || requestPath.equals("/healthz")) {
            handleHealthRequest(request);
        } else if (requestPath.equals("/ready") || requestPath.equals("/readyz")) {
            handleReadyRequest(request);
        } else {
            request.response()
                .setStatusCode(404)
                .putHeader("Content-Type", "text/plain")
                .end("Not Found");
        }
    }

    /**
     * Handle metrics scrape request.
     *
     * @param request HTTP request
     */
    private void handleMetricsRequest(final HttpServerRequest request) {
        final long startTime = System.currentTimeMillis();

        // Check cache first
        final CachedMetrics cached = this.cachedMetrics.get();
        final long now = System.currentTimeMillis();

        if (cached.isValid(now, this.cacheTtlMs)) {
            // Cache hit - return immediately
            respondWithMetrics(request, cached.content, true, System.currentTimeMillis() - startTime);
            return;
        }

        // Cache miss or stale - need to scrape
        // Execute scrape on worker pool (this verticle already runs as worker)
        vertx.executeBlocking(promise -> {
            try {
                final String metrics = scrapeMetrics();
                final CachedMetrics newCache = new CachedMetrics(metrics, System.currentTimeMillis());
                this.cachedMetrics.set(newCache);
                promise.complete(metrics);
            } catch (Exception e) {
                EcsLogger.warn("com.artipie.metrics.AsyncMetricsVerticle")
                    .message("Metrics scrape failed, using stale cache")
                    .eventCategory("metrics")
                    .eventAction("scrape")
                    .eventOutcome("failure")
                    .error(e)
                    .log();

                // Return stale cache on error
                final CachedMetrics stale = this.cachedMetrics.get();
                if (stale.content != null && !stale.content.isEmpty()) {
                    promise.complete(stale.content);
                } else {
                    promise.fail(e);
                }
            }
        }, false, ar -> { // false = don't order results, allow concurrent execution
            if (ar.succeeded()) {
                respondWithMetrics(request, ar.result().toString(), false,
                    System.currentTimeMillis() - startTime);
            } else {
                request.response()
                    .setStatusCode(500)
                    .putHeader("Content-Type", "text/plain")
                    .end("Metrics scrape failed: " + ar.cause().getMessage());
            }
        });
    }

    /**
     * Scrape metrics from the registry.
     * Uses lock to prevent concurrent scrapes (deduplication).
     *
     * @return Prometheus format metrics string
     */
    private String scrapeMetrics() {
        // Try to acquire lock to prevent concurrent scrapes
        if (!this.scrapeLock.tryLock()) {
            // Another scrape in progress - wait for it or use cache
            try {
                if (this.scrapeLock.tryLock(SCRAPE_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    try {
                        return doScrape();
                    } finally {
                        this.scrapeLock.unlock();
                    }
                } else {
                    // Timeout waiting for lock - return current cache
                    final CachedMetrics cached = this.cachedMetrics.get();
                    return cached.content != null ? cached.content : "";
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                final CachedMetrics cached = this.cachedMetrics.get();
                return cached.content != null ? cached.content : "";
            }
        }

        try {
            return doScrape();
        } finally {
            this.scrapeLock.unlock();
        }
    }

    /**
     * Actually perform the scrape operation.
     *
     * @return Prometheus format metrics string
     */
    private String doScrape() {
        final long scrapeStart = System.currentTimeMillis();

        String result;
        if (this.registry instanceof PrometheusMeterRegistry) {
            result = ((PrometheusMeterRegistry) this.registry).scrape();
        } else {
            // Fallback for non-Prometheus registries
            result = "# No Prometheus registry configured\n";
        }

        final long scrapeDuration = System.currentTimeMillis() - scrapeStart;

        // Log slow scrapes (> 1 second)
        if (scrapeDuration > 1000) {
            EcsLogger.warn("com.artipie.metrics.AsyncMetricsVerticle")
                .message("Slow metrics scrape detected")
                .eventCategory("metrics")
                .eventAction("scrape")
                .eventOutcome("slow")
                .field("event.duration", scrapeDuration)
                .field("metrics.size.bytes", result.getBytes(StandardCharsets.UTF_8).length)
                .log();
        }

        return result;
    }

    /**
     * Send metrics response.
     *
     * @param request HTTP request
     * @param metrics Metrics content
     * @param fromCache Whether this was a cache hit
     * @param totalDuration Total request duration in ms
     */
    private void respondWithMetrics(
        final HttpServerRequest request,
        final String metrics,
        final boolean fromCache,
        final long totalDuration
    ) {
        request.response()
            .setStatusCode(200)
            .putHeader("Content-Type", PROMETHEUS_CONTENT_TYPE)
            .putHeader("X-Artipie-Metrics-Cache", fromCache ? "hit" : "miss")
            .putHeader("X-Artipie-Metrics-Duration-Ms", String.valueOf(totalDuration))
            .end(metrics);
    }

    /**
     * Handle health check request.
     *
     * @param request HTTP request
     */
    private void handleHealthRequest(final HttpServerRequest request) {
        request.response()
            .setStatusCode(200)
            .putHeader("Content-Type", "application/json")
            .end("{\"status\":\"UP\",\"component\":\"metrics-server\"}");
    }

    /**
     * Handle readiness check request.
     *
     * @param request HTTP request
     */
    private void handleReadyRequest(final HttpServerRequest request) {
        // Check if we can scrape metrics
        final CachedMetrics cached = this.cachedMetrics.get();
        final boolean hasMetrics = cached.content != null && !cached.content.isEmpty();

        if (hasMetrics || this.registry != null) {
            request.response()
                .setStatusCode(200)
                .putHeader("Content-Type", "application/json")
                .end("{\"status\":\"READY\",\"hasCache\":" + hasMetrics + "}");
        } else {
            request.response()
                .setStatusCode(503)
                .putHeader("Content-Type", "application/json")
                .end("{\"status\":\"NOT_READY\",\"reason\":\"No metrics registry configured\"}");
        }
    }

    /**
     * Get current cache statistics.
     *
     * @return Cache statistics
     */
    public CacheStats getCacheStats() {
        final CachedMetrics cached = this.cachedMetrics.get();
        final long now = System.currentTimeMillis();
        return new CacheStats(
            cached.timestamp,
            now - cached.timestamp,
            cached.content != null ? cached.content.length() : 0,
            cached.isValid(now, this.cacheTtlMs)
        );
    }

    /**
     * Force cache refresh.
     */
    public void refreshCache() {
        vertx.executeBlocking(promise -> {
            try {
                final String metrics = scrapeMetrics();
                final CachedMetrics newCache = new CachedMetrics(metrics, System.currentTimeMillis());
                this.cachedMetrics.set(newCache);
                promise.complete();
            } catch (Exception e) {
                promise.fail(e);
            }
        }, false, ar -> {
            if (ar.failed()) {
                EcsLogger.warn("com.artipie.metrics.AsyncMetricsVerticle")
                    .message("Cache refresh failed")
                    .eventCategory("metrics")
                    .eventAction("cache_refresh")
                    .eventOutcome("failure")
                    .error(ar.cause())
                    .log();
            }
        });
    }

    /**
     * Cached metrics container.
     */
    private static final class CachedMetrics {
        final String content;
        final long timestamp;

        CachedMetrics(final String content, final long timestamp) {
            this.content = content;
            this.timestamp = timestamp;
        }

        boolean isValid(final long now, final long ttlMs) {
            return this.content != null
                && !this.content.isEmpty()
                && (now - this.timestamp) < ttlMs;
        }
    }

    /**
     * Cache statistics for monitoring.
     */
    public static final class CacheStats {
        private final long lastUpdateTimestamp;
        private final long ageMs;
        private final int sizeChars;
        private final boolean valid;

        CacheStats(final long lastUpdateTimestamp, final long ageMs,
                   final int sizeChars, final boolean valid) {
            this.lastUpdateTimestamp = lastUpdateTimestamp;
            this.ageMs = ageMs;
            this.sizeChars = sizeChars;
            this.valid = valid;
        }

        public long getLastUpdateTimestamp() {
            return lastUpdateTimestamp;
        }

        public long getAgeMs() {
            return ageMs;
        }

        public int getSizeChars() {
            return sizeChars;
        }

        public boolean isValid() {
            return valid;
        }
    }
}
