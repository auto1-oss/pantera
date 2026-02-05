/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.http.client;

import com.amihaiemil.eoyaml.YamlMapping;
import com.amihaiemil.eoyaml.YamlSequence;
import com.google.common.base.Strings;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.StreamSupport;

/**
 * HTTP client settings for Vert.x-based HTTP client.
 *
 * <p>Default values are tuned for production workloads targeting 1000+ req/s:</p>
 * <ul>
 *   <li>{@code connectTimeout}: 15 seconds - reasonable for most networks</li>
 *   <li>{@code idleTimeout}: 30 seconds - prevents connection accumulation</li>
 *   <li>{@code connectionAcquireTimeout}: 30 seconds - fail fast under back-pressure</li>
 *   <li>{@code maxConnectionsPerDestination}: 64 - balanced for typical proxy scenarios</li>
 *   <li>{@code maxRequestsQueuedPerDestination}: 256 - prevents unbounded queuing</li>
 *   <li>{@code http2Enabled}: true - HTTP/2 with ALPN for HTTPS, h2c for HTTP</li>
 *   <li>{@code retryMaxAttempts}: 3 - exponential backoff retry</li>
 *   <li>{@code circuitBreakerEnabled}: true - per-destination circuit breaker</li>
 * </ul>
 *
 * <p>HTTP/2 uses ALPN negotiation for HTTPS connections, which automatically falls back
 * to HTTP/1.1 if the server doesn't support HTTP/2. For cleartext HTTP, h2c upgrade
 * mechanism is used.</p>
 *
 * <p>These defaults prevent "pseudo-leaks" where requests queue indefinitely
 * under back-pressure, making it appear as if connections are leaking.</p>
 *
 * @since 0.2
 */
public class HttpClientSettings {

    public static HttpClientSettings from(YamlMapping mapping) {
        final HttpClientSettings res = new HttpClientSettings();
        if (mapping != null) {
            final String conTimeout = mapping.string("connection_timeout");
            if (!Strings.isNullOrEmpty(conTimeout)) {
                res.setConnectTimeout(Long.parseLong(conTimeout));
            }
            final String idleTimeout = mapping.string("idle_timeout");
            if (!Strings.isNullOrEmpty(idleTimeout)) {
                res.setIdleTimeout(Long.parseLong(idleTimeout));
            }
            final String trustAll = mapping.string("trust_all");
            if (!Strings.isNullOrEmpty(trustAll)) {
                res.setTrustAll(Boolean.parseBoolean(trustAll));
            }
            final String http3 = mapping.string("http3");
            if (!Strings.isNullOrEmpty(http3)) {
                res.setHttp3(Boolean.parseBoolean(http3));
            }
            final String followRedirects = mapping.string("follow_redirects");
            if (!Strings.isNullOrEmpty(followRedirects)) {
                res.setFollowRedirects(Boolean.parseBoolean(followRedirects));
            }
            final String proxyTimeout = mapping.string("proxy_timeout");
            if (!Strings.isNullOrEmpty(proxyTimeout)) {
                res.setProxyTimeout(Long.parseLong(proxyTimeout));
            }
            final String acquireTimeout = mapping.string("connection_acquire_timeout");
            if (!Strings.isNullOrEmpty(acquireTimeout)) {
                res.setConnectionAcquireTimeout(Long.parseLong(acquireTimeout));
            }
            final String maxConnPerDest = mapping.string("max_connections_per_destination");
            if (!Strings.isNullOrEmpty(maxConnPerDest)) {
                res.setMaxConnectionsPerDestination(Integer.parseInt(maxConnPerDest));
            }
            final String maxQueuedPerDest = mapping.string("max_requests_queued_per_destination");
            if (!Strings.isNullOrEmpty(maxQueuedPerDest)) {
                res.setMaxRequestsQueuedPerDestination(Integer.parseInt(maxQueuedPerDest));
            }
            final YamlMapping jks = mapping.yamlMapping("jks");
            if (jks != null) {
                res.setJksPath(
                    Objects.requireNonNull(jks.string("path"),
                        "'path' element is not in mapping `jks` settings")
                    )
                    .setJksPwd(jks.string("password"));
            }
            final YamlSequence proxies = mapping.yamlSequence("proxies");
            if (proxies != null) {
                StreamSupport.stream(proxies.spliterator(), false)
                    .forEach(proxy -> {
                        if (proxy instanceof YamlMapping yml) {
                            res.addProxy(ProxySettings.from(yml));
                        } else {
                            throw new IllegalStateException(
                                "`proxies` element is not mapping in meta config"
                            );
                        }
                    });
            }
            // HTTP/2 and read idle timeout
            final String http2 = mapping.string("http2_enabled");
            if (!Strings.isNullOrEmpty(http2)) {
                res.setHttp2Enabled(Boolean.parseBoolean(http2));
            }
            final String readIdle = mapping.string("read_idle_timeout");
            if (!Strings.isNullOrEmpty(readIdle)) {
                res.setReadIdleTimeout(Long.parseLong(readIdle));
            }
            // Retry settings
            final YamlMapping retry = mapping.yamlMapping("retry");
            if (retry != null) {
                final String maxAttempts = retry.string("max_attempts");
                if (!Strings.isNullOrEmpty(maxAttempts)) {
                    res.setRetryMaxAttempts(Integer.parseInt(maxAttempts));
                }
                final String initialDelay = retry.string("initial_delay");
                if (!Strings.isNullOrEmpty(initialDelay)) {
                    res.setRetryInitialDelay(Long.parseLong(initialDelay));
                }
                final String maxDelay = retry.string("max_delay");
                if (!Strings.isNullOrEmpty(maxDelay)) {
                    res.setRetryMaxDelay(Long.parseLong(maxDelay));
                }
                final String multiplier = retry.string("multiplier");
                if (!Strings.isNullOrEmpty(multiplier)) {
                    res.setRetryMultiplier(Double.parseDouble(multiplier));
                }
                final String jitter = retry.string("jitter");
                if (!Strings.isNullOrEmpty(jitter)) {
                    res.setRetryJitter(Double.parseDouble(jitter));
                }
                final YamlSequence codes = retry.yamlSequence("retryable_status_codes");
                if (codes != null) {
                    final Set<Integer> statusCodes = new HashSet<>();
                    codes.forEach(code -> statusCodes.add(Integer.parseInt(code.asScalar().value())));
                    res.setRetryableStatusCodes(statusCodes);
                }
            }
            // Circuit breaker settings
            final YamlMapping cb = mapping.yamlMapping("circuit_breaker");
            if (cb != null) {
                final String enabled = cb.string("enabled");
                if (!Strings.isNullOrEmpty(enabled)) {
                    res.setCircuitBreakerEnabled(Boolean.parseBoolean(enabled));
                }
                final String failureRate = cb.string("failure_rate_threshold");
                if (!Strings.isNullOrEmpty(failureRate)) {
                    res.setCircuitBreakerFailureRateThreshold(Integer.parseInt(failureRate));
                }
                final String windowSize = cb.string("sliding_window_size");
                if (!Strings.isNullOrEmpty(windowSize)) {
                    res.setCircuitBreakerSlidingWindowSize(Integer.parseInt(windowSize));
                }
                final String timeout = cb.string("timeout");
                if (!Strings.isNullOrEmpty(timeout)) {
                    res.setCircuitBreakerTimeout(Long.parseLong(timeout));
                }
                final String successThreshold = cb.string("success_threshold");
                if (!Strings.isNullOrEmpty(successThreshold)) {
                    res.setCircuitBreakerSuccessThreshold(Integer.parseInt(successThreshold));
                }
            }
        }
        return res;
    }

    private static Optional<ProxySettings> proxySettingsFromSystem(String scheme) {
        final String host = System.getProperty(scheme + ".proxyHost");
        if (!Strings.isNullOrEmpty(host)) {
            int port = -1;
            final String httpPort = System.getProperty(scheme + ".proxyPort");
            if (!Strings.isNullOrEmpty(httpPort)) {
                port = Integer.parseInt(httpPort);
            }
            try {
                return Optional.of(new ProxySettings(scheme, host, port));
            } catch (URISyntaxException e) {
                throw new IllegalArgumentException(e);
            }
        }
        return Optional.empty();
    }

    /**
     * Read HTTP proxy settings if enabled.
     */
    private final List<ProxySettings> proxies;

    /**
     * Determine if it is required to trust all SSL certificates.
     */
    private boolean trustAll;

    /**
     * Java key store path.
     */
    private String jksPath;

    /**
     * Java key store pwd.
     */
    private String jksPwd;

    /**
     * Determine if redirects should be followed.
     */
    private boolean followRedirects;

    /**
     * Use http3 transport.
     */
    private boolean http3;

    /**
     * Max time, in milliseconds, a connection can take to connect to destination.
     * Zero means infinite wait time.
     * Default: 15000ms (15 seconds)
     */
    private long connectTimeout;

    /**
     * The max time, in milliseconds, a connection can be idle (no incoming or outgoing traffic).
     * Zero means infinite wait time.
     * 
     * <p><b>Important:</b> Setting this to 0 (infinite) can cause connections to accumulate
     * indefinitely, appearing as a connection leak. A reasonable default (30 seconds)
     * ensures idle connections are cleaned up.</p>
     * 
     * Default: 30000ms (30 seconds)
     */
    private long idleTimeout;

    /**
     * Proxy request timeout in seconds.
     * Default is 60 seconds.
     */
    private long proxyTimeout;

    /**
     * Maximum time in milliseconds to wait for a pooled connection.
     * Zero means wait indefinitely.
     * 
     * <p><b>Important:</b> Long timeouts (e.g., 2 minutes) can cause requests to queue
     * for extended periods under back-pressure, appearing as stuck requests.
     * A shorter timeout (30 seconds) allows faster failure and retry.</p>
     * 
     * Default: 30000ms (30 seconds)
     */
    private long connectionAcquireTimeout;

    /**
     * Max connections per destination (upstream host).
     * 
     * <p>Higher values allow more parallelism but consume more resources.
     * For proxy scenarios with many upstream hosts, lower values (64) are usually sufficient.
     * For single high-throughput upstream, consider increasing to 128-256.</p>
     * 
     * Default: 64
     */
    private int maxConnectionsPerDestination;

    /**
     * Max queued requests per destination.
     *
     * <p>When the connection pool is exhausted, requests queue up to this limit.
     * Very high values (2048+) can cause memory pressure and long latencies.
     * Lower values (256) cause faster failure under back-pressure.</p>
     *
     * Default: 256
     */
    private int maxRequestsQueuedPerDestination;

    /**
     * Read idle timeout in milliseconds (for slow downloads).
     */
    private long readIdleTimeout;

    /**
     * Enable HTTP/2 with ALPN support.
     */
    private boolean http2Enabled;

    /**
     * Retry max attempts.
     */
    private int retryMaxAttempts;

    /**
     * Retry initial delay in milliseconds.
     */
    private long retryInitialDelay;

    /**
     * Retry max delay in milliseconds.
     */
    private long retryMaxDelay;

    /**
     * Retry multiplier for exponential backoff.
     */
    private double retryMultiplier;

    /**
     * Retry jitter factor.
     */
    private double retryJitter;

    /**
     * Retryable HTTP status codes.
     */
    private Set<Integer> retryableStatusCodes;

    /**
     * Circuit breaker enabled.
     */
    private boolean circuitBreakerEnabled;

    /**
     * Circuit breaker failure rate threshold percentage.
     */
    private int circuitBreakerFailureRateThreshold;

    /**
     * Circuit breaker sliding window size.
     */
    private int circuitBreakerSlidingWindowSize;

    /**
     * Circuit breaker timeout in milliseconds.
     */
    private long circuitBreakerTimeout;

    /**
     * Circuit breaker success threshold.
     */
    private int circuitBreakerSuccessThreshold;

    /**
     * Default connect timeout in milliseconds.
     */
    public static final long DEFAULT_CONNECT_TIMEOUT = 15_000L;

    /**
     * Default idle timeout in milliseconds.
     * Non-zero to prevent connection accumulation.
     */
    public static final long DEFAULT_IDLE_TIMEOUT = 30_000L;

    /**
     * Default connection acquire timeout in milliseconds.
     * Shorter than before to fail fast under back-pressure.
     */
    public static final long DEFAULT_CONNECTION_ACQUIRE_TIMEOUT = 30_000L;

    /**
     * Default max connections per destination.
     * Balanced for typical proxy scenarios.
     */
    public static final int DEFAULT_MAX_CONNECTIONS_PER_DESTINATION = 64;

    /**
     * Default max queued requests per destination.
     * Lower than before to prevent unbounded queuing.
     */
    public static final int DEFAULT_MAX_REQUESTS_QUEUED_PER_DESTINATION = 256;

    /**
     * Default proxy timeout in seconds.
     */
    public static final long DEFAULT_PROXY_TIMEOUT = 60L;

    /**
     * Default read idle timeout in milliseconds.
     */
    public static final long DEFAULT_READ_IDLE_TIMEOUT = 60_000L;

    /**
     * Default HTTP/2 enabled.
     * HTTP/2 is enabled by default with ALPN support for HTTPS connections.
     * ALPN negotiation provides automatic fallback to HTTP/1.1 if server doesn't support HTTP/2.
     */
    public static final boolean DEFAULT_HTTP2_ENABLED = true;

    /**
     * Default retry max attempts.
     */
    public static final int DEFAULT_RETRY_MAX_ATTEMPTS = 3;

    /**
     * Default retry initial delay in milliseconds.
     */
    public static final long DEFAULT_RETRY_INITIAL_DELAY = 100L;

    /**
     * Default retry max delay in milliseconds.
     */
    public static final long DEFAULT_RETRY_MAX_DELAY = 1000L;

    /**
     * Default retry multiplier.
     */
    public static final double DEFAULT_RETRY_MULTIPLIER = 2.0;

    /**
     * Default retry jitter factor.
     */
    public static final double DEFAULT_RETRY_JITTER = 0.2;

    /**
     * Default retryable status codes.
     */
    public static final Set<Integer> DEFAULT_RETRYABLE_STATUS_CODES =
        Set.of(502, 503, 504);

    /**
     * Default circuit breaker enabled.
     */
    public static final boolean DEFAULT_CIRCUIT_BREAKER_ENABLED = true;

    /**
     * Default circuit breaker failure rate threshold (80%).
     */
    public static final int DEFAULT_CIRCUIT_BREAKER_FAILURE_RATE = 80;

    /**
     * Default circuit breaker sliding window size.
     */
    public static final int DEFAULT_CIRCUIT_BREAKER_WINDOW_SIZE = 10;

    /**
     * Default circuit breaker timeout in milliseconds.
     */
    public static final long DEFAULT_CIRCUIT_BREAKER_TIMEOUT = 30_000L;

    /**
     * Default circuit breaker success threshold.
     */
    public static final int DEFAULT_CIRCUIT_BREAKER_SUCCESS_THRESHOLD = 3;

    public HttpClientSettings() {
        this.trustAll = false;
        this.followRedirects = true;
        this.connectTimeout = DEFAULT_CONNECT_TIMEOUT;
        this.idleTimeout = DEFAULT_IDLE_TIMEOUT;
        this.readIdleTimeout = DEFAULT_READ_IDLE_TIMEOUT;
        this.http3 = false;
        this.http2Enabled = DEFAULT_HTTP2_ENABLED;
        this.proxyTimeout = DEFAULT_PROXY_TIMEOUT;
        this.connectionAcquireTimeout = DEFAULT_CONNECTION_ACQUIRE_TIMEOUT;
        this.maxConnectionsPerDestination = DEFAULT_MAX_CONNECTIONS_PER_DESTINATION;
        this.maxRequestsQueuedPerDestination = DEFAULT_MAX_REQUESTS_QUEUED_PER_DESTINATION;
        // Retry settings
        this.retryMaxAttempts = DEFAULT_RETRY_MAX_ATTEMPTS;
        this.retryInitialDelay = DEFAULT_RETRY_INITIAL_DELAY;
        this.retryMaxDelay = DEFAULT_RETRY_MAX_DELAY;
        this.retryMultiplier = DEFAULT_RETRY_MULTIPLIER;
        this.retryJitter = DEFAULT_RETRY_JITTER;
        this.retryableStatusCodes = new HashSet<>(DEFAULT_RETRYABLE_STATUS_CODES);
        // Circuit breaker settings
        this.circuitBreakerEnabled = DEFAULT_CIRCUIT_BREAKER_ENABLED;
        this.circuitBreakerFailureRateThreshold = DEFAULT_CIRCUIT_BREAKER_FAILURE_RATE;
        this.circuitBreakerSlidingWindowSize = DEFAULT_CIRCUIT_BREAKER_WINDOW_SIZE;
        this.circuitBreakerTimeout = DEFAULT_CIRCUIT_BREAKER_TIMEOUT;
        this.circuitBreakerSuccessThreshold = DEFAULT_CIRCUIT_BREAKER_SUCCESS_THRESHOLD;
        this.proxies = new ArrayList<>();
        proxySettingsFromSystem("http")
            .ifPresent(this::addProxy);
        proxySettingsFromSystem("https")
            .ifPresent(this::addProxy);
    }

    /**
     * Create settings optimized for high-throughput single-upstream scenarios.
     * Use this when proxying to a single high-capacity upstream server.
     * 
     * <p>Increases connection limits for better parallelism:</p>
     * <ul>
     *   <li>maxConnectionsPerDestination: 128</li>
     *   <li>maxRequestsQueuedPerDestination: 512</li>
     * </ul>
     * 
     * @return Settings optimized for high throughput
     */
    public static HttpClientSettings forHighThroughput() {
        return new HttpClientSettings()
            .setMaxConnectionsPerDestination(128)
            .setMaxRequestsQueuedPerDestination(512);
    }

    /**
     * Create settings optimized for many-upstream proxy scenarios.
     * Use this when proxying to many different upstream servers (e.g., group repositories).
     * 
     * <p>Uses conservative connection limits to prevent resource exhaustion:</p>
     * <ul>
     *   <li>maxConnectionsPerDestination: 32</li>
     *   <li>maxRequestsQueuedPerDestination: 128</li>
     *   <li>idleTimeout: 15 seconds (faster cleanup)</li>
     * </ul>
     * 
     * @return Settings optimized for many upstreams
     */
    public static HttpClientSettings forManyUpstreams() {
        return new HttpClientSettings()
            .setMaxConnectionsPerDestination(32)
            .setMaxRequestsQueuedPerDestination(128)
            .setIdleTimeout(15_000L);
    }

    public HttpClientSettings addProxy(ProxySettings ps) {
        proxies.add(ps);
        return this;
    }

    public List<ProxySettings> proxies() {
        return Collections.unmodifiableList(this.proxies);
    }

    public boolean trustAll() {
        return trustAll;
    }

    public HttpClientSettings setTrustAll(final boolean trustAll) {
        this.trustAll = trustAll;
        return this;
    }

    public String jksPath() {
        return jksPath;
    }

    public HttpClientSettings setJksPath(final String jksPath) {
        this.jksPath = jksPath;
        return this;
    }

    public String jksPwd() {
        return jksPwd;
    }

    public HttpClientSettings setJksPwd(final String jksPwd) {
        this.jksPwd = jksPwd;
        return this;
    }

    public boolean followRedirects() {
        return followRedirects;
    }

    public HttpClientSettings setFollowRedirects(final boolean followRedirects) {
        this.followRedirects = followRedirects;
        return this;
    }

    public boolean http3() {
        return http3;
    }

    public HttpClientSettings setHttp3(final boolean http3) {
        this.http3 = http3;
        return this;
    }

    public long connectTimeout() {
        return connectTimeout;
    }

    public HttpClientSettings setConnectTimeout(final long connectTimeout) {
        this.connectTimeout = connectTimeout;
        return this;
    }

    public long idleTimeout() {
        return idleTimeout;
    }

    public HttpClientSettings setIdleTimeout(final long idleTimeout) {
        this.idleTimeout = idleTimeout;
        return this;
    }

    public long proxyTimeout() {
        return proxyTimeout;
    }

    public HttpClientSettings setProxyTimeout(final long proxyTimeout) {
        this.proxyTimeout = proxyTimeout;
        return this;
    }

    public long connectionAcquireTimeout() {
        return connectionAcquireTimeout;
    }

    public HttpClientSettings setConnectionAcquireTimeout(final long connectionAcquireTimeout) {
        this.connectionAcquireTimeout = connectionAcquireTimeout;
        return this;
    }

    public int maxConnectionsPerDestination() {
        return maxConnectionsPerDestination;
    }

    public HttpClientSettings setMaxConnectionsPerDestination(final int maxConnectionsPerDestination) {
        this.maxConnectionsPerDestination = maxConnectionsPerDestination;
        return this;
    }

    public int maxRequestsQueuedPerDestination() {
        return maxRequestsQueuedPerDestination;
    }

    public HttpClientSettings setMaxRequestsQueuedPerDestination(final int maxRequestsQueuedPerDestination) {
        this.maxRequestsQueuedPerDestination = maxRequestsQueuedPerDestination;
        return this;
    }

    public long readIdleTimeout() {
        return readIdleTimeout;
    }

    public HttpClientSettings setReadIdleTimeout(final long readIdleTimeout) {
        this.readIdleTimeout = readIdleTimeout;
        return this;
    }

    public boolean http2Enabled() {
        return http2Enabled;
    }

    public HttpClientSettings setHttp2Enabled(final boolean http2Enabled) {
        this.http2Enabled = http2Enabled;
        return this;
    }

    public int retryMaxAttempts() {
        return retryMaxAttempts;
    }

    public HttpClientSettings setRetryMaxAttempts(final int retryMaxAttempts) {
        this.retryMaxAttempts = retryMaxAttempts;
        return this;
    }

    public long retryInitialDelay() {
        return retryInitialDelay;
    }

    public HttpClientSettings setRetryInitialDelay(final long retryInitialDelay) {
        this.retryInitialDelay = retryInitialDelay;
        return this;
    }

    public long retryMaxDelay() {
        return retryMaxDelay;
    }

    public HttpClientSettings setRetryMaxDelay(final long retryMaxDelay) {
        this.retryMaxDelay = retryMaxDelay;
        return this;
    }

    public double retryMultiplier() {
        return retryMultiplier;
    }

    public HttpClientSettings setRetryMultiplier(final double retryMultiplier) {
        this.retryMultiplier = retryMultiplier;
        return this;
    }

    public double retryJitter() {
        return retryJitter;
    }

    public HttpClientSettings setRetryJitter(final double retryJitter) {
        this.retryJitter = retryJitter;
        return this;
    }

    public Set<Integer> retryableStatusCodes() {
        return Collections.unmodifiableSet(retryableStatusCodes);
    }

    public HttpClientSettings setRetryableStatusCodes(final Set<Integer> retryableStatusCodes) {
        this.retryableStatusCodes = new HashSet<>(retryableStatusCodes);
        return this;
    }

    public boolean circuitBreakerEnabled() {
        return circuitBreakerEnabled;
    }

    public HttpClientSettings setCircuitBreakerEnabled(final boolean circuitBreakerEnabled) {
        this.circuitBreakerEnabled = circuitBreakerEnabled;
        return this;
    }

    public int circuitBreakerFailureRateThreshold() {
        return circuitBreakerFailureRateThreshold;
    }

    public HttpClientSettings setCircuitBreakerFailureRateThreshold(final int circuitBreakerFailureRateThreshold) {
        this.circuitBreakerFailureRateThreshold = circuitBreakerFailureRateThreshold;
        return this;
    }

    public int circuitBreakerSlidingWindowSize() {
        return circuitBreakerSlidingWindowSize;
    }

    public HttpClientSettings setCircuitBreakerSlidingWindowSize(final int circuitBreakerSlidingWindowSize) {
        this.circuitBreakerSlidingWindowSize = circuitBreakerSlidingWindowSize;
        return this;
    }

    public long circuitBreakerTimeout() {
        return circuitBreakerTimeout;
    }

    public HttpClientSettings setCircuitBreakerTimeout(final long circuitBreakerTimeout) {
        this.circuitBreakerTimeout = circuitBreakerTimeout;
        return this;
    }

    public int circuitBreakerSuccessThreshold() {
        return circuitBreakerSuccessThreshold;
    }

    public HttpClientSettings setCircuitBreakerSuccessThreshold(final int circuitBreakerSuccessThreshold) {
        this.circuitBreakerSuccessThreshold = circuitBreakerSuccessThreshold;
        return this;
    }
}
