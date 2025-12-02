/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.http.client.jetty;

import com.artipie.ArtipieException;
import com.artipie.http.Slice;
import com.artipie.http.client.ClientSlices;
import com.artipie.http.client.HttpClientSettings;
import java.util.concurrent.atomic.AtomicBoolean;
import com.google.common.base.Strings;
import org.eclipse.jetty.client.BasicAuthentication;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpProxy;
import org.eclipse.jetty.client.Origin;
import org.eclipse.jetty.io.ArrayByteBufferPool;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import com.artipie.http.log.EcsLogger;

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
                throw new ArtipieException(
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
                EcsLogger.debug("com.artipie.http.client")
                    .message("Stopping Jetty HTTP client (" + this.clnt.getDestinations().size() + " destinations)")
                    .eventCategory("http")
                    .eventAction("http_client_stop")
                    .log();

                // First, stop accepting new requests
                this.clnt.stop();

                // Then destroy to release all resources (connection pools, threads)
                // This is critical to prevent connection leaks
                this.clnt.destroy();

                EcsLogger.debug("com.artipie.http.client")
                    .message("Jetty HTTP client stopped and destroyed successfully")
                    .eventCategory("http")
                    .eventAction("http_client_stop")
                    .eventOutcome("success")
                    .log();
            } catch (Exception e) {
                EcsLogger.error("com.artipie.http.client")
                    .message("Failed to stop Jetty HTTP client cleanly")
                    .eventCategory("http")
                    .eventAction("http_client_stop")
                    .eventOutcome("failure")
                    .error(e)
                    .log();
                throw new ArtipieException(
                    "Failed to stop Jetty HTTP client. Some connections may not be closed properly.",
                    e
                );
            }
        }
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
            EcsLogger.warn("com.artipie.http.client")
                .message("HTTP/3 transport requested but not supported in Jetty 12.1+")
                .eventCategory("http")
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
        final int maxBucketSize = 1024;  // Handles 1000 req/s with good buffer reuse
        final long maxDirectMemory = 2L * 1024L * 1024L * 1024L;  // 2GB (50% of 4GB budget)
        final long maxHeapMemory = 1L * 1024L * 1024L * 1024L;    // 1GB (6% of 16GB heap)
        final ArrayByteBufferPool bufferPool = new ArrayByteBufferPool(
            -1,           // minCapacity: use default (0)
            -1,           // factor: use default (1024) - bucket size increment
            -1,           // maxCapacity: use default (unbounded individual buffer sizes OK)
            maxBucketSize,// maxBucketSize: LIMIT buffers per bucket to prevent O(n) eviction!
            maxHeapMemory,
            maxDirectMemory
        );
        result.setByteBufferPool(bufferPool);
        
        EcsLogger.info("com.artipie.http.client")
            .message("Configured Jetty ByteBufferPool with bounded buckets")
            .eventCategory("http")
            .eventAction("http_client_init")
            .field("buffer_pool.max_bucket_size", maxBucketSize)
            .field("buffer_pool.max_heap_memory_mb", maxHeapMemory / (1024 * 1024))
            .field("buffer_pool.max_direct_memory_mb", maxDirectMemory / (1024 * 1024))
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
        
        return result;
    }
}
