/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.http.client;

import com.amihaiemil.eoyaml.YamlMapping;
import com.amihaiemil.eoyaml.YamlSequence;
import com.google.common.base.Strings;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.StreamSupport;

/**
 * Http client settings for Jetty-based HTTP client.
 * 
 * <p>Default values are tuned for production workloads targeting 1000+ req/s:</p>
 * <ul>
 *   <li>{@code connectTimeout}: 15 seconds - reasonable for most networks</li>
 *   <li>{@code idleTimeout}: 30 seconds - prevents connection accumulation</li>
 *   <li>{@code connectionAcquireTimeout}: 30 seconds - fail fast under back-pressure</li>
 *   <li>{@code maxConnectionsPerDestination}: 64 - balanced for typical proxy scenarios</li>
 *   <li>{@code maxRequestsQueuedPerDestination}: 256 - prevents unbounded queuing</li>
 * </ul>
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
            final String bucketSize = mapping.string("jetty_bucket_size");
            if (!Strings.isNullOrEmpty(bucketSize)) {
                res.setJettyBucketSize(Integer.parseInt(bucketSize));
            }
            final String directMem = mapping.string("jetty_direct_memory");
            if (!Strings.isNullOrEmpty(directMem)) {
                res.setJettyDirectMemory(Long.parseLong(directMem));
            }
            final String heapMem = mapping.string("jetty_heap_memory");
            if (!Strings.isNullOrEmpty(heapMem)) {
                res.setJettyHeapMemory(Long.parseLong(heapMem));
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
     * Jetty buffer pool max bucket size (buffers per size class).
     */
    private int jettyBucketSize;

    /**
     * Jetty buffer pool max direct memory in bytes.
     */
    private long jettyDirectMemory;

    /**
     * Jetty buffer pool max heap memory in bytes.
     */
    private long jettyHeapMemory;

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
     * Default Jetty buffer pool bucket size.
     * Controls max buffers per size class to prevent O(n) eviction spikes.
     */
    public static final int DEFAULT_JETTY_BUCKET_SIZE = 1024;

    /**
     * Default Jetty buffer pool max direct memory in bytes (2 GB).
     */
    public static final long DEFAULT_JETTY_DIRECT_MEMORY = 2L * 1024L * 1024L * 1024L;

    /**
     * Default Jetty buffer pool max heap memory in bytes (1 GB).
     */
    public static final long DEFAULT_JETTY_HEAP_MEMORY = 1L * 1024L * 1024L * 1024L;

    public HttpClientSettings() {
        this.trustAll = false;
        this.followRedirects = true;
        this.connectTimeout = DEFAULT_CONNECT_TIMEOUT;
        this.idleTimeout = DEFAULT_IDLE_TIMEOUT;
        this.http3 = false;
        this.proxyTimeout = DEFAULT_PROXY_TIMEOUT;
        this.connectionAcquireTimeout = DEFAULT_CONNECTION_ACQUIRE_TIMEOUT;
        this.maxConnectionsPerDestination = DEFAULT_MAX_CONNECTIONS_PER_DESTINATION;
        this.maxRequestsQueuedPerDestination = DEFAULT_MAX_REQUESTS_QUEUED_PER_DESTINATION;
        this.jettyBucketSize = DEFAULT_JETTY_BUCKET_SIZE;
        this.jettyDirectMemory = DEFAULT_JETTY_DIRECT_MEMORY;
        this.jettyHeapMemory = DEFAULT_JETTY_HEAP_MEMORY;
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

    public int jettyBucketSize() {
        return jettyBucketSize;
    }

    public HttpClientSettings setJettyBucketSize(final int jettyBucketSize) {
        this.jettyBucketSize = jettyBucketSize;
        return this;
    }

    public long jettyDirectMemory() {
        return jettyDirectMemory;
    }

    public HttpClientSettings setJettyDirectMemory(final long jettyDirectMemory) {
        this.jettyDirectMemory = jettyDirectMemory;
        return this;
    }

    public long jettyHeapMemory() {
        return jettyHeapMemory;
    }

    public HttpClientSettings setJettyHeapMemory(final long jettyHeapMemory) {
        this.jettyHeapMemory = jettyHeapMemory;
        return this;
    }
}
