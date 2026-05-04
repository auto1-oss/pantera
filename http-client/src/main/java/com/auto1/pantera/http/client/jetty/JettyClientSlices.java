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
package com.auto1.pantera.http.client.jetty;

import com.auto1.pantera.PanteraException;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.client.ClientSlices;
import com.auto1.pantera.http.client.HttpClientSettings;
import java.util.concurrent.atomic.AtomicBoolean;
import com.google.common.base.Strings;
import org.eclipse.jetty.client.BasicAuthentication;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpClientTransport;
import org.eclipse.jetty.client.HttpProxy;
import org.eclipse.jetty.client.Origin;
import org.eclipse.jetty.client.transport.HttpClientConnectionFactory;
import org.eclipse.jetty.client.transport.HttpClientTransportDynamic;
import org.eclipse.jetty.client.transport.HttpClientTransportOverHTTP;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.client.transport.ClientConnectionFactoryOverHTTP2;
import org.eclipse.jetty.io.ArrayByteBufferPool;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.misc.ConfigDefaults;

/**
 * ClientSlices implementation using Jetty HTTP client as back-end.
 * <code>start()</code> method should be called before sending responses to initialize
 * underlying client. <code>stop()</code> methods should be used to release resources
 * and stop requests in progress.
 *
 * @since 0.1
 */
public final class JettyClientSlices implements ClientSlices, AutoCloseable {

    /**
     * HTTP protocol selection for upstream connections.
     *
     * <p>This is a primitive mirror of the runtime
     * {@code com.auto1.pantera.settings.runtime.HttpTuning.Protocol} enum.
     * It lives in {@code http-client} (rather than {@code pantera-main}
     * where {@code HttpTuning} resides) so that the http-client module
     * stays self-contained — pantera-main → http-client is the dependency
     * direction; the reverse would be circular.</p>
     *
     * <ul>
     *   <li>{@code H1} — pure HTTP/1.1 transport
     *       ({@link HttpClientTransportOverHTTP}).</li>
     *   <li>{@code H2} — ALPN-negotiated HTTP/2 with HTTP/1.1 fallback
     *       ({@link HttpClientTransportDynamic} preferring h2).</li>
     *   <li>{@code AUTO} — same dynamic transport as {@code H2}; ALPN
     *       picks h2 when the upstream supports it, h1.1 otherwise.</li>
     * </ul>
     */
    public enum HttpProtocol {
        /** HTTP/1.1 only. */
        H1,
        /** HTTP/2 with HTTP/1.1 fallback via ALPN. */
        H2,
        /** Negotiated, currently identical to {@link #H2}. */
        AUTO
    }

    /**
     * Default HTTP port.
     */
    private static final int HTTP_PORT = 80;

    /**
     * Default HTTPS port.
     */
    private static final int HTTPS_PORT = 443;

    /**
     * HTTP client.
     */
    private final HttpClient clnt;

    /**
     * Max time to wait for connection acquisition in milliseconds.
     */
    private final long acquireTimeoutMillis;

    /**
     * Started flag.
     */
    private final AtomicBoolean started = new AtomicBoolean(false);

    /**
     * Stopped flag.
     */
    private final AtomicBoolean stopped = new AtomicBoolean(false);

    /**
     * Ctor.
     */
    public JettyClientSlices() {
        this(new HttpClientSettings());
    }

    /**
     * Legacy ctor — the runtime tuning (protocol + h2 pool size + h2
     * multiplexing limit) is sourced from {@link HttpClientSettings}
     * itself rather than the v2.2 RuntimeSettingsCache.
     *
     * <p>Specifically, the per-destination cap continues to come from
     * {@code settings.maxConnectionsPerDestination()}, preserving any
     * YAML overrides users had in v2.1. The protocol is upgraded to
     * ALPN-negotiated h2/h1.1 (matching {@code HttpTuning.defaults()}'s
     * Protocol = H2), but the connection pool size is left untouched
     * so existing YAML config keeps working.</p>
     *
     * <p>Task 9 will introduce the runtime-cache-driven path that prefers
     * DB tuning over YAML; until then, callers wanting the new behaviour
     * use the 4-arg ctor explicitly.</p>
     *
     * @param settings Settings.
     */
    public JettyClientSlices(final HttpClientSettings settings) {
        this(settings, HttpProtocol.H2, settings.maxConnectionsPerDestination(), 100);
    }

    /**
     * Ctor with explicit HTTP/2 tunables sourced from the runtime
     * settings cache (see {@code com.auto1.pantera.settings.runtime.HttpTuning}).
     *
     * <p>These primitives are wired by pantera-main when constructing a
     * client. The http-client module deliberately accepts plain values
     * rather than depending on the {@code HttpTuning} record — pantera-main
     * already depends on http-client, so the inverse would be circular.</p>
     *
     * @param settings Static YAML-driven settings (TLS, proxies, timeouts, …).
     * @param protocol Wire-protocol selection ({@link HttpProtocol}).
     * @param h2MaxPoolSize Maps to Jetty's
     *     {@code HttpClient.setMaxConnectionsPerDestination}. With HTTP/2
     *     a single TCP connection multiplexes many concurrent streams, so
     *     a value of {@code 1} is the recommended deployment; raise only
     *     if a single connection becomes a throughput bottleneck. Also
     *     applied to the H1 transport for symmetry.
     * @param h2MultiplexingLimit Maps to
     *     {@code HTTP2Client.setMaxConcurrentPushedStreams=0} and
     *     {@code setMaxLocalStreams(...)} — the per-connection cap on
     *     concurrent client-initiated streams. Ignored when protocol
     *     is {@link HttpProtocol#H1}.
     */
    public JettyClientSlices(
        final HttpClientSettings settings,
        final HttpProtocol protocol,
        final int h2MaxPoolSize,
        final int h2MultiplexingLimit
    ) {
        this.clnt = create(settings, protocol, h2MaxPoolSize, h2MultiplexingLimit);
        this.acquireTimeoutMillis = settings.connectionAcquireTimeout();
    }

    /**
     * Prepare for usage.
     */
    public void start() {
        if (started.compareAndSet(false, true)) {
            try {
                this.clnt.start();
            } catch (Exception e) {
                started.set(false);  // Reset on failure
                throw new PanteraException(
                    "Failed to start Jetty HTTP client. Check logs for connection/SSL issues.",
                    e
                );
            }
        }
    }

    /**
     * Release used resources and stop requests in progress.
     * This properly closes all connections and releases thread pools.
     */
    public void stop() {
        if (stopped.compareAndSet(false, true)) {
            try {
                EcsLogger.debug("com.auto1.pantera.http.client")
                    .message("Stopping Jetty HTTP client (" + this.clnt.getDestinations().size() + " destinations)")
                    .eventCategory("web")
                    .eventAction("http_client_stop")
                    .log();

                // First, stop accepting new requests
                this.clnt.stop();

                // Then destroy to release all resources (connection pools, threads)
                // This is critical to prevent connection leaks
                this.clnt.destroy();

                EcsLogger.debug("com.auto1.pantera.http.client")
                    .message("Jetty HTTP client stopped and destroyed successfully")
                    .eventCategory("web")
                    .eventAction("http_client_stop")
                    .eventOutcome("success")
                    .log();
            } catch (Exception e) {
                EcsLogger.error("com.auto1.pantera.http.client")
                    .message("Failed to stop Jetty HTTP client cleanly")
                    .eventCategory("web")
                    .eventAction("http_client_stop")
                    .eventOutcome("failure")
                    .error(e)
                    .log();
                throw new PanteraException(
                    "Failed to stop Jetty HTTP client. Some connections may not be closed properly.",
                    e
                );
            }
        }
    }

    /**
     * Checks whether the HTTP client subsystem is operational.
     * @return True if started and not stopped and Jetty client is running
     */
    public boolean isOperational() {
        return this.started.get() && !this.stopped.get() && this.clnt.isRunning();
    }

    /**
     * Close and release resources (implements AutoCloseable).
     */
    @Override
    public void close() {
        stop();
    }

    /**
     * Expose underlying Jetty client for instrumentation.
     * @return Jetty HttpClient instance.
     */
    public HttpClient httpClient() {
        return this.clnt;
    }

    /**
     * Get buffer pool statistics for monitoring and testing.
     * This exposes internal Jetty buffer pool metrics to detect leaks.
     * @return Buffer pool statistics, or null if pool is not an ArrayByteBufferPool.
     */
    public BufferPoolStats getBufferPoolStats() {
        if (this.clnt.getByteBufferPool() instanceof ArrayByteBufferPool pool) {
            return new BufferPoolStats(
                pool.getHeapByteBufferCount(),
                pool.getDirectByteBufferCount(),
                pool.getHeapMemory(),
                pool.getDirectMemory()
            );
        }
        return null;
    }

    /**
     * Buffer pool statistics for monitoring and leak detection.
     * @param heapBufferCount Number of heap buffers in the pool
     * @param directBufferCount Number of direct buffers in the pool
     * @param heapMemory Total heap memory used by buffers (bytes)
     * @param directMemory Total direct memory used by buffers (bytes)
     */
    public record BufferPoolStats(
        long heapBufferCount,
        long directBufferCount,
        long heapMemory,
        long directMemory
    ) {
        /**
         * Total buffer count (heap + direct).
         * @return Total number of buffers
         */
        public long totalBufferCount() {
            return heapBufferCount + directBufferCount;
        }

        /**
         * Total memory used (heap + direct).
         * @return Total memory in bytes
         */
        public long totalMemory() {
            return heapMemory + directMemory;
        }
    }

    @Override
    public Slice http(final String host) {
        return this.slice(false, host, JettyClientSlices.HTTP_PORT);
    }

    @Override
    public Slice http(final String host, final int port) {
        return this.slice(false, host, port);
    }

    @Override
    public Slice https(final String host) {
        return this.slice(true, host, JettyClientSlices.HTTPS_PORT);
    }

    @Override
    public Slice https(final String host, final int port) {
        return this.slice(true, host, port);
    }

    /**
     * Create slice backed by client.
     *
     * @param secure Secure connection flag.
     * @param host Host name.
     * @param port Port.
     * @return Client slice.
     */
    private Slice slice(final boolean secure, final String host, final int port) {
        return new JettyClientSlice(this.clnt, secure, host, port, this.acquireTimeoutMillis);
    }

    /**
     * Creates {@link HttpClient} from {@link HttpClientSettings} with
     * runtime-tuned protocol + HTTP/2 pooling parameters.
     *
     * @param settings Static YAML-sourced settings.
     * @param protocol Wire-protocol selection.
     * @param h2MaxPoolSize Per-destination connection cap (Jetty's
     *     {@code maxConnectionsPerDestination}). With HTTP/2 multiplexing
     *     1 is normal; raised values create extra parallel TCP connections.
     * @param h2MultiplexingLimit Per-connection cap on concurrent
     *     client-initiated HTTP/2 streams. Ignored for {@link HttpProtocol#H1}.
     * @return HTTP client built from settings.
     */
    @SuppressWarnings("PMD.NPathComplexity")
    private static HttpClient create(
        final HttpClientSettings settings,
        final HttpProtocol protocol,
        final int h2MaxPoolSize,
        final int h2MultiplexingLimit
    ) {
        // NOTE: HTTP/3 support temporarily disabled in Jetty 12.1+ due to significant API changes
        // The HTTP3Client and related classes require extensive refactoring
        // This is acceptable as HTTP/3 is rarely used and the critical fix is the ArrayByteBufferPool
        if (settings.http3()) {
            EcsLogger.warn("com.auto1.pantera.http.client")
                .message("HTTP/3 transport requested but not supported in Jetty 12.1+")
                .eventCategory("web")
                .eventAction("http_client_init")
                .log();
        }

        // ByteBufferPool configuration for high-traffic production workloads
        //
        // CRITICAL: Jetty 12.x has O(n) eviction that causes 100% CPU spikes
        // when the pool has too many buffers. The fix is to:
        // 1. Limit maxBucketSize to cap buffers per size class
        // 2. Set reasonable memory limits
        //
        // Sizing for production (15 CPU, 4GB direct, 16GB heap, 1000 req/s):
        // - maxBucketSize=1024: handles 1000+ concurrent requests with buffer reuse
        // - With 64 buckets, max ~64K buffers total (still fast O(n) eviction)
        // - Eviction of 64K buffers takes <100ms vs 150s+ for 500K buffers
        //
        // Trade-off:
        // - Lower value (256): more direct allocations, more GC pressure
        // - Higher value (1024): better reuse, but larger O(n) scan if eviction needed
        // - 1024 is sweet spot for 1000 req/s workloads
        final int maxBucketSize = ConfigDefaults.getInt("PANTERA_JETTY_BUCKET_SIZE", settings.jettyBucketSize());
        final long maxDirectMemory = ConfigDefaults.getLong("PANTERA_JETTY_DIRECT_MEMORY", settings.jettyDirectMemory());
        final long maxHeapMemory = ConfigDefaults.getLong("PANTERA_JETTY_HEAP_MEMORY", settings.jettyHeapMemory());
        final ArrayByteBufferPool bufferPool = new ArrayByteBufferPool(
            -1,           // minCapacity: use default (0)
            -1,           // factor: use default (1024) - bucket size increment
            -1,           // maxCapacity: use default (unbounded individual buffer sizes OK)
            maxBucketSize,// maxBucketSize: LIMIT buffers per bucket to prevent O(n) eviction!
            maxHeapMemory,
            maxDirectMemory
        );

        final SslContextFactory.Client factory = new SslContextFactory.Client();
        factory.setTrustAll(settings.trustAll());
        if (!Strings.isNullOrEmpty(settings.jksPath())) {
            factory.setKeyStoreType("jks");
            factory.setKeyStorePath(settings.jksPath());
            factory.setKeyStorePassword(settings.jksPwd());
        }

        // Shared ClientConnector — both H1 and H2 transports plug into the
        // same selector / SSL / buffer pool. ClientConnector is owned by
        // the HttpClientTransport, which is owned by HttpClient — so its
        // lifecycle is managed transitively by the client's start/stop.
        final ClientConnector connector = new ClientConnector();
        connector.setSslContextFactory(factory);
        connector.setByteBufferPool(bufferPool);

        final HttpClientTransport transport = buildTransport(
            connector, protocol, h2MultiplexingLimit, bufferPool
        );

        final HttpClient result = new HttpClient(transport);
        result.setByteBufferPool(bufferPool);
        // SSL is set on the connector, but Jetty's HttpClient also exposes
        // a top-level setter that some internal paths still consult; keep
        // both wired to the same factory to avoid surprises.
        result.setSslContextFactory(factory);

        EcsLogger.info("com.auto1.pantera.http.client")
            .message(String.format(
                "Configured Jetty client: protocol=%s, h2MaxPoolSize=%d, h2MultiplexingLimit=%d, "
                    + "bufferPool maxBucketSize=%d, maxHeapMB=%d, maxDirectMB=%d",
                protocol, h2MaxPoolSize, h2MultiplexingLimit,
                maxBucketSize, maxHeapMemory / (1024 * 1024), maxDirectMemory / (1024 * 1024)))
            .eventCategory("web")
            .eventAction("http_client_init")
            .log();

        settings.proxies().forEach(
            proxy -> {
                if (!Strings.isNullOrEmpty(proxy.basicRealm())) {
                    result.getAuthenticationStore().addAuthentication(
                        new BasicAuthentication(
                            proxy.uri(), proxy.basicRealm(), proxy.basicUser(), proxy.basicPwd()
                        )
                    );
                }
                result.getProxyConfiguration().addProxy(
                    new HttpProxy(new Origin.Address(proxy.host(), proxy.port()), proxy.secure())
                );
            }
        );
        result.setFollowRedirects(settings.followRedirects());
        // Remove Jetty's built-in AuthenticationProtocolHandler. Some upstream registries
        // return 401 without a WWW-Authenticate header (non-compliant but common), which
        // causes Jetty to throw "HTTP protocol violation". Pantera handles authentication
        // itself via AuthClientSlice, so the built-in handler is unnecessary.
        result.getProtocolHandlers().remove(
            org.eclipse.jetty.client.WWWAuthenticationProtocolHandler.NAME
        );
        
        // CRITICAL FIX: Jetty 12 has a NPE bug when connectTimeout is 0
        // When timeout is 0 (infinite), don't set it - let Jetty use its default behavior
        // This prevents: "Cannot invoke Scheduler$Task.cancel() because connect.timeout is null"
        final long connectTimeout = settings.connectTimeout();
        if (connectTimeout > 0) {
            result.setConnectTimeout(connectTimeout);
        }
        
        // Idle timeout can safely be 0 (infinite)
        result.setIdleTimeout(settings.idleTimeout());
        result.setAddressResolutionTimeout(5_000L);
        
        // Connection pool limits to prevent resource exhaustion.
        // The per-destination cap is sourced from the runtime tuning
        // (h2MaxPoolSize) rather than HttpClientSettings — the legacy
        // ctor passes settings.maxConnectionsPerDestination() so YAML
        // users keep their numbers; the new 4-arg ctor lets the DB
        // RuntimeSettingsCache override.
        result.setMaxConnectionsPerDestination(h2MaxPoolSize);
        result.setMaxRequestsQueuedPerDestination(settings.maxRequestsQueuedPerDestination());

        // No client-wide User-Agent: per-request UA is set by upper-layer
        // proxy slices, which forward the inbound client's UA so upstream
        // registries see the native tool (npm, mvn, go, pip...) rather than
        // a Pantera-branded UA — that latter triggers per-UA rate-limits.
        // Suppress Jetty's default "Jetty/<version>" header as well.
        result.setUserAgentField(null);

        return result;
    }

    /**
     * Builds the {@link HttpClientTransport} matching {@code protocol}.
     *
     * <ul>
     *   <li>{@link HttpProtocol#H1}: {@link HttpClientTransportOverHTTP}
     *       — plain HTTP/1.1 over the supplied {@link ClientConnector}.
     *       No ALPN, no TLS-h2 handshake overhead.</li>
     *   <li>{@link HttpProtocol#H2} / {@link HttpProtocol#AUTO}:
     *       {@link HttpClientTransportDynamic} listing
     *       {@link HttpClientConnectionFactory.HTTP11} first (the
     *       cleartext default; h2c is rare among artifact registries
     *       and can confuse legacy HTTP proxies) and
     *       {@link ClientConnectionFactoryOverHTTP2.HTTP2} second.
     *       For TLS connections Jetty offers both protocols to ALPN,
     *       and h2 wins when the upstream supports it.</li>
     * </ul>
     *
     * <p>The {@code h2MultiplexingLimit} is applied to the underlying
     * {@link HTTP2Client} via {@code setMaxLocalStreams} — Jetty 12 caps
     * concurrent client-initiated streams there. Server push is disabled
     * unconditionally (registries don't push, and push amplification
     * would only waste pool entries).</p>
     */
    private static HttpClientTransport buildTransport(
        final ClientConnector connector,
        final HttpProtocol protocol,
        final int h2MultiplexingLimit,
        final ArrayByteBufferPool bufferPool
    ) {
        final HttpClientTransport result;
        if (protocol == HttpProtocol.H1) {
            result = new HttpClientTransportOverHTTP(connector);
        } else {
            final HTTP2Client h2Client = new HTTP2Client(connector);
            // Disable server push: registries don't push, and push streams
            // would just consume pool slots.
            h2Client.setMaxConcurrentPushedStreams(0);
            // Per-connection cap on concurrent client-initiated streams.
            // h2MultiplexingLimit comes from RuntimeSettingsCache.HttpTuning.
            if (h2MultiplexingLimit > 0) {
                h2Client.setMaxLocalStreams(h2MultiplexingLimit);
            }
            // Share the same buffer pool so h2 frames hit the same bounded
            // bucket allocator as h1.1 — keeps direct-memory accounting honest.
            h2Client.setByteBufferPool(bufferPool);
            final ClientConnectionFactoryOverHTTP2.HTTP2 h2Factory =
                new ClientConnectionFactoryOverHTTP2.HTTP2(h2Client);
            // IMPORTANT: do NOT pass the static singleton
            // HttpClientConnectionFactory.HTTP11. Jetty 12 will register
            // it as a managed bean of HttpClientTransportDynamic and call
            // destroy() on it when the transport (and thus client) stops.
            // The next client built in the same JVM (e.g. test reruns,
            // hot-reload) would then fail with "Destroyed container
            // cannot be restarted". Use a fresh per-client instance.
            final HttpClientConnectionFactory.HTTP11 h11Factory =
                new HttpClientConnectionFactory.HTTP11();
            // Order matters: the FIRST factory is the default for plain
            // (cleartext) HTTP connections, where there's no ALPN. We put
            // HTTP/1.1 first so plain `http://` upstreams (and HTTP-only
            // proxies) keep speaking h1.1 — h2c (cleartext HTTP/2) is rare
            // among artifact registries and would lock up legacy proxies.
            // For TLS connections, both factories are offered to ALPN and
            // h2 is negotiated when supported.
            result = new HttpClientTransportDynamic(
                connector, h11Factory, h2Factory
            );
        }
        return result;
    }
}
