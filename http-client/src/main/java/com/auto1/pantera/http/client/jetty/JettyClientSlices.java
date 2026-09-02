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
import com.auto1.pantera.http.client.circuitbreaker.CircuitBreakerConfig;
import com.auto1.pantera.http.client.circuitbreaker.CircuitBreakingClientSlice;
import com.auto1.pantera.http.client.circuitbreaker.UpstreamCircuitBreaker;
import com.auto1.pantera.http.client.circuitbreaker.UpstreamCircuitBreakerRegistry;
import com.auto1.pantera.http.client.ratelimit.RateLimitedClientSlice;
import com.auto1.pantera.http.client.ratelimit.UpstreamRateLimiter;
import java.time.Clock;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import com.google.common.base.Strings;
import org.eclipse.jetty.client.BasicAuthentication;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpProxy;
import org.eclipse.jetty.client.Origin;
import org.eclipse.jetty.client.transport.HttpClientTransportOverHTTP;
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
 * <p>The outbound transport is pure HTTP/1.1 with a keep-alive connection pool
 * (the Nexus / JFrog Artifactory pattern). HTTP/2 was briefly the default in the
 * v2.2.0 perf-pack but Jetty issue
 * <a href="https://github.com/jetty/jetty.project/issues/12776">#12776</a>
 * corrupts the shared {@code ByteBufferPool} when any in-flight H2 stream is
 * cancelled, causing siblings on the same connection to fail with
 * {@code EOFException: Stream has been reset}. Every artifact registry Pantera
 * proxies accepts HTTP/1.1 with keep-alive, so HTTP/2 is unnecessary and
 * actively unsafe for this workload.
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
     * Outbound rate limiter. Shared across the JVM so every per-repo
     * client honours the same per-upstream-host budget. M3 of
     * {@code analysis/plan/v1/PLAN.md}.
     */
    private final UpstreamRateLimiter rateLimiter;

    /**
     * Per-host circuit-breaker registry. Shared across the JVM so
     * every per-repo client funnels through the same per-upstream
     * breaker. T-P02 of {@code analysis/plan/v2/IMPLEMENTATION.md},
     * closing gap G6 from {@code analysis/reference/gap-analysis.md}.
     */
    private final UpstreamCircuitBreakerRegistry circuitBreakerRegistry;

    /**
     * Daemon executor that runs HEAD recovery probes when the circuit
     * breaker opens. One per JVM; threads are named
     * {@code pantera-circuit-probe-N}. Shut down on {@link #stop()}.
     */
    private final ScheduledExecutorService circuitProbeExecutor;

    /**
     * Static circuit-breaker config (seed, cap, trip predicates).
     */
    /**
     * Live circuit-breaker configuration source. Defaults to the
     * hardcoded {@link CircuitBreakerConfig#defaults()}; pantera-main
     * swaps in the DB-backed admin-settings supplier via
     * {@link #circuitBreakerConfig(java.util.function.Supplier)}.
     * Volatile: breakers and slices read through an indirection lambda
     * so a swap applies immediately to every existing breaker.
     */
    private volatile java.util.function.Supplier<CircuitBreakerConfig> circuitBreakerConfig;

    /**
     * Clock used by the circuit breaker slice for probe scheduling.
     */
    private final Clock circuitBreakerClock;

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
     * Build a Jetty client from static YAML-driven settings only.
     *
     * <p>The per-destination keep-alive connection cap comes from
     * {@code settings.maxConnectionsPerDestination()}. No HTTP/2,
     * no ALPN-negotiated dynamic transport — the wire protocol is
     * HTTP/1.1 only.
     *
     * @param settings Settings.
     */
    public JettyClientSlices(final HttpClientSettings settings) {
        this(settings, settings.maxConnectionsPerDestination());
    }

    /**
     * Build a Jetty client with an explicit per-destination connection cap.
     *
     * <p>{@code maxConnectionsPerDestination} is the size of the HTTP/1.1
     * keep-alive pool per upstream destination. The runtime cache uses this
     * overload so DB-backed tuning can override the YAML cap without a
     * restart; production callers reach it via the 3-arg overload, which
     * also injects the shared rate limiter.
     *
     * @param settings Static YAML-driven settings (TLS, proxies, timeouts, …).
     * @param maxConnectionsPerDestination Per-destination keep-alive pool cap.
     */
    public JettyClientSlices(
        final HttpClientSettings settings,
        final int maxConnectionsPerDestination
    ) {
        this(settings, maxConnectionsPerDestination,
            new UpstreamRateLimiter.Default(Clock.systemUTC()));
    }

    /**
     * Constructor with an explicit {@link UpstreamRateLimiter} only.
     * Defaults the circuit breaker registry to a JVM-default with
     * production-tuned trip predicates ({@link CircuitBreakerConfig#defaults()})
     * and a fresh daemon probe executor.
     *
     * @param settings Static settings.
     * @param maxConnectionsPerDestination Per-destination keep-alive pool cap.
     * @param rateLimiter Per-host reactive 429/503 gate.
     */
    public JettyClientSlices(
        final HttpClientSettings settings,
        final int maxConnectionsPerDestination,
        final UpstreamRateLimiter rateLimiter
    ) {
        this(
            settings, maxConnectionsPerDestination,
            rateLimiter, CircuitBreakerConfig.defaults(), Clock.systemUTC()
        );
    }

    /**
     * Full constructor with explicit rate limiter and circuit-breaker
     * config. Used by the perf harness + integration tests to inject a
     * test-friendly clock / trip predicates; production callers use the
     * 3-arg overload which builds JVM defaults.
     *
     * @param settings                     Static settings.
     * @param maxConnectionsPerDestination Per-destination keep-alive pool cap.
     * @param rateLimiter                  Per-host reactive 429/503 gate.
     * @param breakerConfig                Circuit-breaker trip predicates + backoff.
     * @param clock                        Clock for breaker scheduling.
     */
    public JettyClientSlices(
        final HttpClientSettings settings,
        final int maxConnectionsPerDestination,
        final UpstreamRateLimiter rateLimiter,
        final CircuitBreakerConfig breakerConfig,
        final Clock clock
    ) {
        this.clnt = create(settings, maxConnectionsPerDestination);
        this.acquireTimeoutMillis = settings.connectionAcquireTimeout();
        this.rateLimiter = rateLimiter;
        this.circuitBreakerConfig = () -> breakerConfig;
        this.circuitBreakerClock = clock;
        // The registry (and every breaker it creates) reads through this
        // indirection, so circuitBreakerConfig(Supplier) swaps apply to
        // breakers that already exist.
        this.circuitBreakerRegistry = new UpstreamCircuitBreakerRegistry.Default(
            () -> this.circuitBreakerConfig.get(), clock
        );
        this.circuitProbeExecutor = Executors.newSingleThreadScheduledExecutor(
            r -> {
                final Thread thread = new Thread(r, "pantera-circuit-probe");
                thread.setDaemon(true);
                return thread;
            }
        );
    }

    /**
     * @return Shared per-JVM rate limiter so callers (e.g. the proxy
     *     slice's 429 fallback path) can inspect gate state without
     *     re-resolving the singleton.
     */
    public UpstreamRateLimiter rateLimiter() {
        return this.rateLimiter;
    }

    /**
     * @return Shared per-JVM circuit-breaker registry so callers can
     *     inspect breaker state (e.g. for diagnostics, metrics export).
     */
    public UpstreamCircuitBreakerRegistry circuitBreakerRegistry() {
        return this.circuitBreakerRegistry;
    }

    /**
     * Prepare for usage.
     */
    public void start() {
        if (started.compareAndSet(false, true)) {
            try {
                this.clnt.start();
                // Jetty's HttpClient.doStart() registers the built-in
                // WWWAuthenticationProtocolHandler (handles 401) and
                // ProxyAuthenticationProtocolHandler (handles 407) into
                // its protocol-handler map. We MUST remove them AFTER
                // start() — removing before is a no-op because the map
                // is empty at that point.
                //
                // Why remove them: Pantera does its own bearer / basic
                // auth via AuthClientSlice + BearerAuthenticator. Jetty's
                // built-in handlers also attempt to react to 401/407,
                // and they throw {@code HttpResponseException("HTTP
                // protocol violation: Authentication challenge without
                // WWW-Authenticate header")} whenever an upstream replies
                // 401 / 407 without the matching challenge header — which
                // happens with several real-world registries (some Docker
                // mirrors, Maven repos behind permission-denied gates,
                // PyPI internal authn paths). The thrown exception
                // surfaces as a hard failure on what would otherwise be
                // a normal 401-then-auth-retry flow.
                this.clnt.getProtocolHandlers().remove(
                    org.eclipse.jetty.client.WWWAuthenticationProtocolHandler.NAME
                );
                this.clnt.getProtocolHandlers().remove(
                    org.eclipse.jetty.client.ProxyAuthenticationProtocolHandler.NAME
                );
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
                    .field("log.source", "application")
                    .log();

                // First, stop accepting new requests
                this.clnt.stop();

                // Then destroy to release all resources (connection pools, threads)
                // This is critical to prevent connection leaks
                this.clnt.destroy();

                // Shut down the circuit-breaker probe executor. Daemon
                // threads exit when the JVM does, but in test / hot-reload
                // scenarios we want deterministic teardown.
                this.circuitProbeExecutor.shutdownNow();
                if (!this.circuitProbeExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    EcsLogger.warn("com.auto1.pantera.http.client")
                        .message("Circuit-breaker probe executor did not terminate within 5s")
                        .eventCategory("web")
                        .eventAction("http_client_stop")
                        .field("log.source", "application")
                        .log();
                }

                EcsLogger.debug("com.auto1.pantera.http.client")
                    .message("Jetty HTTP client stopped and destroyed successfully")
                    .eventCategory("web")
                    .eventAction("http_client_stop")
                    .eventOutcome("success")
                    .field("log.source", "application")
                    .log();
            } catch (final InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new PanteraException(
                    "Interrupted while stopping Jetty HTTP client.", ie
                );
            } catch (Exception e) {
                // B7: middle-layer log-and-rethrow — wrapping caller
                // (Vert.x shutdown / VertxMain stop path) is the
                // boundary and will log the full PanteraException. Emit
                // TRACE here so debug-on still shows the original cause.
                EcsLogger.trace("com.auto1.pantera.http.client")
                    .message("Failed to stop Jetty HTTP client cleanly")
                    .eventCategory("web")
                    .eventAction("http_client_stop")
                    .field("error.type", e.getClass().getSimpleName())
                    .field("log.source", "application")
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
     * Create slice backed by client. The returned slice is wrapped in
     * (outer-to-inner) {@link RateLimitedClientSlice} → {@link
     * CircuitBreakingClientSlice} → raw Jetty slice, so every outbound
     * request funnels through both the per-host reactive 429 gate AND
     * the per-host 5xx / IO circuit breaker. Loopback hosts
     * ({@code localhost}, {@code 127.x.x.x}, {@code ::1}) bypass both —
     * these are exclusively dev / test fixtures.
     *
     * <p>Ordering rationale: gate-closed (rate-limit) and circuit-open
     * are independent fast-fail conditions. A gate-closed response is a
     * synthesised 429 with Retry-After; a circuit-open response is a
     * synthesised 502 with Retry-After + {@code X-Pantera-Circuit-Open}.
     * Putting rate-limit outside the breaker means a 429 takes priority
     * over a 502 when both could fire — preferring the more specific
     * upstream-side signal.
     *
     * @param secure Secure connection flag.
     * @param host Host name.
     * @param port Port.
     * @return Client slice (rate-limited + circuit-broken for non-loopback hosts).
     */
    /**
     * Swap the circuit-breaker configuration source. Called by
     * pantera-main after the DB layer is up to install the
     * admin-settings-backed supplier; applies immediately to every
     * existing and future breaker (they read through an indirection).
     *
     * @param supplier Live configuration source; must be non-null and
     *     must never return null.
     */
    public void circuitBreakerConfig(
        final java.util.function.Supplier<CircuitBreakerConfig> supplier
    ) {
        this.circuitBreakerConfig = java.util.Objects.requireNonNull(supplier, "supplier");
    }

    private Slice slice(final boolean secure, final String host, final int port) {
        final JettyClientSlice raw = new JettyClientSlice(
            this.clnt, secure, host, port, this.acquireTimeoutMillis
        );
        if (isLoopback(host)) {
            return raw;
        }
        // Breaker keyed by scheme://host:port, NOT bare host: one
        // hostname can front several registries (different ports /
        // schemes), and a bare-host key made them share one failure
        // domain — a 5xx from one took down all of them. The rate
        // limiter stays host-keyed on purpose: upstream throttles
        // apply per origin host, not per port.
        final String breakerKey = (secure ? "https://" : "http://") + host + ':' + port;
        final UpstreamCircuitBreaker breaker = this.circuitBreakerRegistry.breakerFor(breakerKey);
        final Slice withBreaker = new CircuitBreakingClientSlice(
            raw, breakerKey, breaker, () -> this.circuitBreakerConfig.get(),
            this.circuitBreakerClock, this.circuitProbeExecutor
        );
        return new RateLimitedClientSlice(
            withBreaker, host, this.rateLimiter, Clock.systemUTC()
        );
    }

    private static boolean isLoopback(final String host) {
        if (host == null) {
            return false;
        }
        final String h = host.toLowerCase(java.util.Locale.ROOT);
        return "localhost".equals(h)
            || "::1".equals(h) // NOPMD AvoidUsingHardCodedIP - IPv6 loopback literal is the value we need to detect
            || h.startsWith("127.");
    }

    /**
     * Creates the underlying Jetty {@link HttpClient} from
     * {@link HttpClientSettings} with the given per-destination
     * keep-alive pool cap.
     *
     * @param settings Static YAML-sourced settings.
     * @param maxConnectionsPerDestination Per-destination keep-alive pool cap.
     * @return HTTP client built from settings.
     */
    private static HttpClient create(
        final HttpClientSettings settings,
        final int maxConnectionsPerDestination
    ) {
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
        // T-S06: restrict outbound TLS to 1.2 / 1.3 only, matching the
        // server-side TlsHardening contract. setIncludeProtocols is a
        // strict allow-list — anything not listed is rejected even if
        // the JVM enabled it by default. Mozilla "intermediate" cipher
        // suites — TLS 1.3 selects from its own AEAD-only set spec'd
        // by the protocol, so we restrict TLS 1.2 only.
        factory.setIncludeProtocols("TLSv1.2", "TLSv1.3");
        factory.setExcludeProtocols("SSLv2", "SSLv2Hello", "SSLv3", "TLSv1", "TLSv1.1");
        factory.setIncludeCipherSuites(
            "TLS_AES_256_GCM_SHA384",
            "TLS_CHACHA20_POLY1305_SHA256",
            "TLS_AES_128_GCM_SHA256",
            "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
            "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
            "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256",
            "TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256",
            "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
            "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
            "TLS_DHE_RSA_WITH_AES_256_GCM_SHA384",
            "TLS_DHE_RSA_WITH_AES_128_GCM_SHA256"
        );
        // Hostname verification is on by default in Jetty 12
        // (endpointIdentificationAlgorithm = "HTTPS"); calling the
        // setter explicitly documents the requirement and prevents a
        // future setTrustAll(true) from accidentally implying "skip
        // hostname check too" the way it does in some HTTP client libs.
        // T-S06 rejects any deployment that opts out of hostname
        // verification — there is no Pantera setting to turn it off.
        factory.setEndpointIdentificationAlgorithm("HTTPS");
        if (!Strings.isNullOrEmpty(settings.jksPath())) {
            factory.setKeyStoreType("jks");
            factory.setKeyStorePath(settings.jksPath());
            factory.setKeyStorePassword(settings.jksPwd());
        }

        // HTTP/1.1-only transport over a shared ClientConnector that owns
        // the selector / SSL / buffer pool. ClientConnector is owned by
        // HttpClientTransportOverHTTP, which is owned by HttpClient — so
        // its lifecycle is managed transitively by the client's start/stop.
        final ClientConnector connector = new ClientConnector();
        connector.setSslContextFactory(factory);
        connector.setByteBufferPool(bufferPool);

        final HttpClientTransportOverHTTP transport = new HttpClientTransportOverHTTP(connector);

        final HttpClient result = new HttpClient(transport);
        result.setByteBufferPool(bufferPool);
        // SSL is set on the connector, but Jetty's HttpClient also exposes
        // a top-level setter that some internal paths still consult; keep
        // both wired to the same factory to avoid surprises.
        result.setSslContextFactory(factory);

        EcsLogger.info("com.auto1.pantera.http.client")
            .message(String.format(
                "Configured Jetty client: protocol=HTTP/1.1, maxConnectionsPerDestination=%d, "
                    + "bufferPool maxBucketSize=%d, maxHeapMB=%d, maxDirectMB=%d",
                maxConnectionsPerDestination,
                maxBucketSize, maxHeapMemory / (1024 * 1024), maxDirectMemory / (1024 * 1024)))
            .eventCategory("web")
            .eventAction("http_client_init")
            .field("log.source", "application")
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
        // Note: Jetty's built-in WWW/Proxy authentication protocol
        // handlers are removed in {@link Lease#start()} AFTER
        // {@code HttpClient.start()} runs — they are added by
        // {@code HttpClient.doStart()}, so removing them here in the
        // constructor body is a no-op (the handler map is still empty).

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
        // SECURITY (2.2.9): every outbound connect — proxy upstreams,
        // upstream index links, Bearer token realms, redirect hops — resolves
        // through the egress policy, AFTER DNS, so a destination in a denied
        // range (cloud metadata, link-local, ...) is refused even when it
        // hides behind a benign hostname. The real async resolver is built
        // lazily from the started client's executor/scheduler.
        result.setSocketAddressResolver(
            new com.auto1.pantera.http.client.egress.EgressFilteringResolver(
                com.auto1.pantera.http.client.egress.EgressSettingsRegistry.policy(),
                new LazyAsyncResolver(result)
            )
        );

        // Connection pool limits to prevent resource exhaustion. The
        // per-destination cap is the HTTP/1.1 keep-alive pool size —
        // production deployments typically run 20–50 here; runtime
        // overrides come from RuntimeSettingsCache via the 2-arg ctor.
        result.setMaxConnectionsPerDestination(maxConnectionsPerDestination);
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
     * Jetty's own async resolver, created on first use from the (by then
     * started) client's executor and scheduler — the same resolver the
     * client would have installed itself in {@code doStart()} had none
     * been set.
     */
    private static final class LazyAsyncResolver implements org.eclipse.jetty.util.SocketAddressResolver {

        /**
         * Owning client.
         */
        private final HttpClient owner;

        /**
         * Lazily-built delegate.
         */
        private volatile org.eclipse.jetty.util.SocketAddressResolver delegate;

        /**
         * Ctor.
         *
         * @param owner Owning client
         */
        LazyAsyncResolver(final HttpClient owner) {
            this.owner = owner;
        }

        @Override
        public void resolve(
            final String host,
            final int port,
            final java.util.Map<String, Object> context,
            final org.eclipse.jetty.util.Promise<java.util.List<java.net.InetSocketAddress>> promise
        ) {
            org.eclipse.jetty.util.SocketAddressResolver current = this.delegate;
            if (current == null) {
                current = new org.eclipse.jetty.util.SocketAddressResolver.Async(
                    this.owner.getExecutor(),
                    this.owner.getScheduler(),
                    this.owner.getAddressResolutionTimeout()
                );
                this.delegate = current;
            }
            current.resolve(host, port, context, promise);
        }
    }
}
