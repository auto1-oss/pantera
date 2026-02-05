/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.http.client.vertx;

import com.artipie.http.Slice;
import com.artipie.http.client.ClientSlices;
import com.artipie.http.client.HttpClientSettings;
import com.artipie.http.log.EcsLogger;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.http.PoolOptions;
import io.vertx.core.net.JksOptions;

import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Vert.x-based implementation of {@link ClientSlices}.
 * <p>
 * Creates HTTP client slices using Vert.x HttpClient with:
 * - HTTP/2 with ALPN support and HTTP/1.1 fallback
 * - True streaming (response returned when headers arrive, body streams concurrently)
 * - Connection pooling per destination
 * - Retry policy with exponential backoff
 * - Circuit breaker per destination
 * <p>
 * Uses HttpClient (not WebClient) for all requests to enable streaming.
 * WebClient buffers entire responses which causes timeouts for large downloads.
 */
public final class VertxClientSlices implements ClientSlices, AutoCloseable {

    /**
     * Default HTTP port.
     */
    private static final int HTTP_PORT = 80;

    /**
     * Default HTTPS port.
     */
    private static final int HTTPS_PORT = 443;

    /**
     * Vert.x instance.
     */
    private final Vertx vertx;

    /**
     * Vert.x HTTP client for non-SSL connections.
     */
    private final HttpClient httpClient;

    /**
     * Vert.x HTTPS client for SSL connections with ALPN support.
     */
    private final HttpClient httpsClient;

    /**
     * HTTP client settings.
     */
    private final HttpClientSettings settings;

    /**
     * Retry policy.
     */
    private final RetryPolicy retryPolicy;

    /**
     * Whether circuit breaker is enabled.
     */
    private final boolean circuitBreakerEnabled;

    /**
     * Circuit breaker failure rate threshold.
     */
    private final int circuitBreakerFailureRate;

    /**
     * Circuit breaker sliding window size.
     */
    private final int circuitBreakerWindowSize;

    /**
     * Circuit breaker timeout in ms.
     */
    private final long circuitBreakerTimeout;

    /**
     * Circuit breaker success threshold.
     */
    private final int circuitBreakerSuccessThreshold;

    /**
     * Circuit breakers per destination.
     */
    private final ConcurrentMap<String, CircuitBreaker> circuitBreakers;

    /**
     * Whether this instance owns the Vert.x instance and should close it.
     */
    private final boolean ownsVertx;

    /**
     * Constructor with default settings.
     */
    public VertxClientSlices() {
        this(new HttpClientSettings());
    }

    /**
     * Constructor with custom settings.
     *
     * @param settings HTTP client settings
     */
    public VertxClientSlices(final HttpClientSettings settings) {
        this(Vertx.vertx(), settings, true);
    }

    /**
     * Constructor with shared Vert.x instance.
     *
     * @param vertx Shared Vert.x instance
     * @param settings HTTP client settings
     */
    public VertxClientSlices(final Vertx vertx, final HttpClientSettings settings) {
        this(vertx, settings, false);
    }

    /**
     * Full constructor.
     *
     * @param vertx Vert.x instance
     * @param settings HTTP client settings
     * @param ownsVertx Whether this instance owns the Vert.x instance
     */
    private VertxClientSlices(
        final Vertx vertx,
        final HttpClientSettings settings,
        final boolean ownsVertx
    ) {
        this.vertx = vertx;
        this.settings = settings;
        this.ownsVertx = ownsVertx;
        this.circuitBreakers = new ConcurrentHashMap<>();

        // Create HTTP clients - using HttpClient (not WebClient) for streaming
        // HTTPS client has SSL enabled at client level for proper ALPN support
        // Vert.x 5 uses separate PoolOptions for connection pooling
        final PoolOptions poolOptions = this.createPoolOptions(settings);
        final HttpClientOptions httpOptions = this.createHttpClientOptions(settings, false);
        final HttpClientOptions httpsOptions = this.createHttpClientOptions(settings, true);
        this.httpClient = vertx.createHttpClient(httpOptions, poolOptions);
        this.httpsClient = vertx.createHttpClient(httpsOptions, poolOptions);

        // Create retry policy from settings
        this.retryPolicy = this.createRetryPolicy(settings);

        // Circuit breaker configuration from settings
        this.circuitBreakerEnabled = settings.circuitBreakerEnabled();
        this.circuitBreakerFailureRate = settings.circuitBreakerFailureRateThreshold();
        this.circuitBreakerWindowSize = settings.circuitBreakerSlidingWindowSize();
        this.circuitBreakerTimeout = settings.circuitBreakerTimeout();
        this.circuitBreakerSuccessThreshold = settings.circuitBreakerSuccessThreshold();

        EcsLogger.info("com.artipie.http.client")
            .message(String.format("VertxClientSlices initialized (http2=%b, circuit_breaker=%b, streaming=true)",
                settings.http2Enabled(), this.circuitBreakerEnabled))
            .eventCategory("http")
            .eventAction("client_init")
            .log();
    }

    /**
     * Create HttpClientOptions from settings.
     *
     * @param settings HTTP client settings
     * @param ssl Whether this client is for SSL connections
     * @return Configured HttpClientOptions
     */
    /**
     * Create PoolOptions from settings.
     * Vert.x 5 separates pool configuration from HttpClientOptions.
     *
     * @param settings HTTP client settings
     * @return Configured PoolOptions
     */
    private PoolOptions createPoolOptions(final HttpClientSettings settings) {
        return new PoolOptions()
            .setHttp1MaxSize(settings.maxConnectionsPerDestination())
            .setHttp2MaxSize(settings.maxConnectionsPerDestination());
    }

    /**
     * Create HttpClientOptions from settings.
     *
     * @param settings HTTP client settings
     * @param ssl Whether this client is for SSL connections
     * @return Configured HttpClientOptions
     */
    private HttpClientOptions createHttpClientOptions(final HttpClientSettings settings, final boolean ssl) {
        final HttpClientOptions options = new HttpClientOptions()
            .setConnectTimeout((int) settings.connectTimeout())
            .setIdleTimeout((int) (settings.idleTimeout() / 1000)) // Vert.x uses seconds
            .setReadIdleTimeout((int) (settings.readIdleTimeout() / 1000))
            .setTrustAll(settings.trustAll())
            .setVerifyHost(!settings.trustAll())
            .setKeepAlive(true)
            .setTcpKeepAlive(true)
            .setTcpNoDelay(true);

        // For HTTPS client, enable SSL at the client level
        // This is required for ALPN to work properly during TLS handshake
        if (ssl) {
            options.setSsl(true);
        }

        // HTTP/2 with ALPN support and HTTP/1.1 fallback
        if (settings.http2Enabled()) {
            if (ssl) {
                // For HTTPS: Use ALPN to negotiate HTTP/2 during TLS handshake
                // ALPN allows graceful fallback to HTTP/1.1 if server doesn't support HTTP/2
                options.setProtocolVersion(HttpVersion.HTTP_2)
                    .setUseAlpn(true)
                    .setAlpnVersions(Arrays.asList(HttpVersion.HTTP_2, HttpVersion.HTTP_1_1));
            } else {
                // For HTTP: Use cleartext HTTP/2 (h2c) with upgrade
                options.setProtocolVersion(HttpVersion.HTTP_2)
                    .setHttp2ClearTextUpgrade(true);
            }
        }

        // JKS keystore configuration
        if (settings.jksPath() != null && !settings.jksPath().isEmpty()) {
            options.setTrustOptions(
                new JksOptions()
                    .setPath(settings.jksPath())
                    .setPassword(settings.jksPwd())
            );
        }

        return options;
    }

    /**
     * Create RetryPolicy from settings.
     */
    private RetryPolicy createRetryPolicy(final HttpClientSettings settings) {
        return RetryPolicy.builder()
            .maxAttempts(settings.retryMaxAttempts())
            .initialDelay(settings.retryInitialDelay())
            .maxDelay(settings.retryMaxDelay())
            .multiplier(settings.retryMultiplier())
            .jitter(settings.retryJitter())
            .retryableStatusCodes(settings.retryableStatusCodes())
            .build();
    }

    /**
     * Get or create a circuit breaker for a destination.
     */
    private CircuitBreaker getCircuitBreaker(final String host, final int port) {
        final String key = host + ":" + port;
        if (!this.circuitBreakerEnabled) {
            return CircuitBreaker.disabled(key);
        }
        return this.circuitBreakers.computeIfAbsent(key, k ->
            CircuitBreaker.builder()
                .destination(k)
                .failureRateThreshold(this.circuitBreakerFailureRate)
                .slidingWindowSize(this.circuitBreakerWindowSize)
                .timeoutMs(this.circuitBreakerTimeout)
                .successThreshold(this.circuitBreakerSuccessThreshold)
                .build()
        );
    }

    @Override
    public Slice http(final String host) {
        return this.http(host, HTTP_PORT);
    }

    @Override
    public Slice http(final String host, final int port) {
        final int effectivePort = port <= 0 ? HTTP_PORT : port;
        return new VertxClientSlice(
            this.httpClient,
            host,
            effectivePort,
            false, // No SSL
            this.retryPolicy,
            this.getCircuitBreaker(host, effectivePort),
            this.settings.connectTimeout(),
            this.settings.idleTimeout()
        );
    }

    @Override
    public Slice https(final String host) {
        return this.https(host, HTTPS_PORT);
    }

    @Override
    public Slice https(final String host, final int port) {
        final int effectivePort = port <= 0 ? HTTPS_PORT : port;
        return new VertxClientSlice(
            this.httpsClient,
            host,
            effectivePort,
            true, // SSL must be enabled in RequestOptions (client-level SSL alone is not enough)
            this.retryPolicy,
            this.getCircuitBreaker(host, effectivePort),
            this.settings.connectTimeout(),
            this.settings.idleTimeout()
        );
    }

    /**
     * Get the underlying Vert.x instance.
     *
     * @return Vert.x instance
     */
    public Vertx vertx() {
        return this.vertx;
    }

    /**
     * Get the underlying HTTP client (for non-SSL connections).
     *
     * @return HTTP client instance
     */
    public HttpClient httpClient() {
        return this.httpClient;
    }

    /**
     * Get the underlying HTTPS client (for SSL connections).
     *
     * @return HTTPS client instance
     */
    public HttpClient httpsClient() {
        return this.httpsClient;
    }

    /**
     * Get the HTTP client settings.
     *
     * @return Settings
     */
    public HttpClientSettings settings() {
        return this.settings;
    }

    @Override
    public void close() {
        EcsLogger.info("com.artipie.http.client")
            .message("Closing VertxClientSlices")
            .eventCategory("http")
            .eventAction("client_close")
            .log();

        this.httpClient.close();
        this.httpsClient.close();

        if (this.ownsVertx) {
            this.vertx.close();
        }
    }

    /**
     * Start the client (no-op for Vert.x, included for API compatibility).
     */
    public void start() {
        // Vert.x HttpClient is ready immediately after creation
        EcsLogger.debug("com.artipie.http.client")
            .message("VertxClientSlices started")
            .eventCategory("http")
            .eventAction("client_start")
            .log();
    }

    /**
     * Stop the client (alias for close).
     */
    public void stop() {
        this.close();
    }
}
