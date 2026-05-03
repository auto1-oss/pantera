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
import org.eclipse.jetty.client.HttpProxy;
import org.eclipse.jetty.client.Origin;
import org.eclipse.jetty.io.ArrayByteBufferPool;
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
     * Ctor.
     *
     * @param settings Settings.
     */
    public JettyClientSlices(final HttpClientSettings settings) {
        this.clnt = create(settings);
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
     * Creates {@link HttpClient} from {@link HttpClientSettings}.
     *
     * @param settings Settings.
     * @return HTTP client built from settings.
     */
    private static HttpClient create(final HttpClientSettings settings) {
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
        
        // Always use HTTP/1.1 or HTTP/2 transport
        final HttpClient result = new HttpClient();
        
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
        result.setByteBufferPool(bufferPool);
        
        EcsLogger.info("com.auto1.pantera.http.client")
            .message(String.format(
                "Configured Jetty ByteBufferPool with bounded buckets: maxBucketSize=%d, maxHeapMB=%d, maxDirectMB=%d",
                maxBucketSize, maxHeapMemory / (1024 * 1024), maxDirectMemory / (1024 * 1024)))
            .eventCategory("web")
            .eventAction("http_client_init")
            .log();
        
        final SslContextFactory.Client factory = new SslContextFactory.Client();
        factory.setTrustAll(settings.trustAll());
        if (!Strings.isNullOrEmpty(settings.jksPath())) {
            factory.setKeyStoreType("jks");
            factory.setKeyStorePath(settings.jksPath());
            factory.setKeyStorePassword(settings.jksPwd());
        }
        result.setSslContextFactory(factory);
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
        
        // Connection pool limits to prevent resource exhaustion
        // These prevent unlimited connection accumulation to upstream repositories
        result.setMaxConnectionsPerDestination(settings.maxConnectionsPerDestination());
        result.setMaxRequestsQueuedPerDestination(settings.maxRequestsQueuedPerDestination());

        // No client-wide User-Agent: per-request UA is set by upper-layer
        // proxy slices, which forward the inbound client's UA so upstream
        // registries see the native tool (npm, mvn, go, pip...) rather than
        // a Pantera-branded UA — that latter triggers per-UA rate-limits.
        // Suppress Jetty's default "Jetty/<version>" header as well.
        result.setUserAgentField(null);

        return result;
    }
}
