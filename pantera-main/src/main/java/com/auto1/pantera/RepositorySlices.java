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
package com.auto1.pantera;

import com.auto1.pantera.adapters.docker.DockerProxy;
import com.auto1.pantera.adapters.file.FileProxy;
import com.auto1.pantera.adapters.go.GoProxy;

import com.auto1.pantera.adapters.maven.MavenProxy;
import com.auto1.pantera.adapters.php.ComposerGroupSlice;
import com.auto1.pantera.adapters.php.ComposerProxy;
import com.auto1.pantera.adapters.pypi.PypiProxy;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.SubStorage;
import com.auto1.pantera.auth.LoggingAuth;
import com.auto1.pantera.cache.NegativeCacheConfig;
import com.auto1.pantera.http.cache.NegativeCache;
import com.auto1.pantera.composer.AstoRepository;
import com.auto1.pantera.composer.http.PhpComposer;
import com.auto1.pantera.conan.ItemTokenizer;
import com.auto1.pantera.conan.http.ConanSlice;
import com.auto1.pantera.conda.http.CondaSlice;
import com.auto1.pantera.debian.Config;
import com.auto1.pantera.debian.http.DebianSlice;
import com.auto1.pantera.docker.Docker;
import com.auto1.pantera.docker.asto.AstoDocker;
import com.auto1.pantera.docker.asto.RegistryRoot;
import com.auto1.pantera.docker.http.DockerSlice;
import com.auto1.pantera.docker.http.TrimmedDocker;
import com.auto1.pantera.cooldown.api.CooldownService;
import com.auto1.pantera.cooldown.CooldownSupport;
import com.auto1.pantera.files.FilesSlice;
import com.auto1.pantera.gem.http.GemSlice;

import com.auto1.pantera.helm.http.HelmSlice;
import com.auto1.pantera.hex.http.HexSlice;
import com.auto1.pantera.http.ContentLengthRestriction;
import com.auto1.pantera.http.DockerRoutingSlice;
import com.auto1.pantera.http.GoSlice;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.TimeoutSlice;
import com.auto1.pantera.group.GroupResolver;
import com.auto1.pantera.index.ArtifactIndex;
import com.auto1.pantera.http.auth.Authentication;
import com.auto1.pantera.http.auth.BasicAuthScheme;
import com.auto1.pantera.http.auth.CombinedAuthScheme;
import com.auto1.pantera.http.auth.CombinedAuthzSliceWrap;
import com.auto1.pantera.http.auth.TokenAuthentication;
import com.auto1.pantera.http.auth.OperationControl;
import com.auto1.pantera.http.auth.Tokens;
import com.auto1.pantera.http.client.HttpClientSettings;
import com.auto1.pantera.http.client.ProxySettings;
import com.auto1.pantera.http.client.jetty.JettyClientSlices;
import com.auto1.pantera.http.filter.FilterSlice;
import com.auto1.pantera.http.filter.Filters;
import com.auto1.pantera.http.timeout.AutoBlockRegistry;
import com.auto1.pantera.http.timeout.AutoBlockSettings;
import com.auto1.pantera.http.slice.PathPrefixStripSlice;
import com.auto1.pantera.http.slice.SliceSimple;
import com.auto1.pantera.http.slice.TrimPathSlice;
import com.auto1.pantera.maven.http.MavenSlice;
import com.auto1.pantera.npm.http.NpmSlice;
import com.auto1.pantera.npm.proxy.NpmProxy;
import com.auto1.pantera.npm.proxy.http.NpmProxySlice;
import com.auto1.pantera.nuget.http.NuGet;
import com.auto1.pantera.pypi.http.PySlice;
import com.auto1.pantera.rpm.http.RpmSlice;
import com.auto1.pantera.scheduling.ArtifactEvent;
import com.auto1.pantera.scheduling.MetadataEventQueues;
import com.auto1.pantera.security.policy.Policy;
import com.auto1.pantera.settings.Settings;
import com.auto1.pantera.settings.repo.RepoConfig;
import com.auto1.pantera.settings.runtime.HttpTuning;
import com.auto1.pantera.security.perms.Action;
import com.auto1.pantera.security.perms.AdapterBasicPermission;
import com.auto1.pantera.settings.repo.Repositories;
import com.google.common.base.Strings;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.cache.RemovalListener;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.vertx.core.Vertx;
import org.eclipse.jetty.client.AbstractConnectionPool;
import org.eclipse.jetty.client.Destination;

import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;
import java.util.regex.Pattern;

public class RepositorySlices {

    /**
     * Thread counter for the resolve executor pool.
     */
    private static final AtomicInteger RESOLVE_COUNTER = new AtomicInteger(0);

    /**
     * Dedicated executor for blocking slice resolution operations (e.g. Jetty client start).
     * Prevents blocking the Vert.x event loop when proxy repositories are initialized.
     */
    private static final ExecutorService RESOLVE_EXECUTOR =
        Executors.newFixedThreadPool(
            Math.max(4, Runtime.getRuntime().availableProcessors()),
            r -> {
                final Thread t = new Thread(
                    r, "slice-resolve-" + RESOLVE_COUNTER.incrementAndGet()
                );
                t.setDaemon(true);
                return t;
            }
        );

    /**
     * Pattern to trim path before passing it to adapters' slice.
     */
    private static final Pattern PATTERN = Pattern.compile("/(?:[^/]+)(/.*)?");

    /**
     * Pantera settings.
     */
    private final Settings settings;

    private final Repositories repos;

    /**
     * Tokens: authentication and generation.
     */
    private final Tokens tokens;

    /**
     * Slice's cache.
     */
    private final LoadingCache<SliceKey, SliceValue> slices;

    /**
     * Cooldown service.
     */
    private final CooldownService cooldown;

    /**
     * Cooldown metadata filtering service.
     */
    private final com.auto1.pantera.cooldown.metadata.CooldownMetadataService cooldownMetadata;

    /**
     * Shared Jetty HTTP clients keyed by settings signature.
     */
    private final SharedJettyClients sharedClients;

    /**
     * Negative cache configuration loaded from YAML.
     * <p>Read from {@code meta.caches.repo-negative} first; falls back to the
     * legacy {@code meta.caches.group-negative} key with a deprecation WARN.
     * When neither key is present, uses historical defaults (5 min / 10K).
     */
    private final NegativeCacheConfig negativeCacheConfig;

    /**
     * Single shared NegativeCache instance for the entire JVM.
     * All group, proxy, and hosted scopes share this bean. Keyed by
     * {@link com.auto1.pantera.http.cache.NegativeCacheKey}.
     */
    private final NegativeCache sharedNegativeCache;

    /**
     * Shared circuit-breaker registries keyed by physical repo name.
     * One {@link AutoBlockRegistry} per upstream — shared across every group that
     * references that upstream.  When maven-central is a member of both
     * libs-release and libs-snapshot, both groups trip the same circuit after N
     * failures; recovery is also detected once rather than independently per group.
     */
    private final ConcurrentMap<String, AutoBlockRegistry> memberRegistries =
        new ConcurrentHashMap<>();

    /**
     * Per-repo bulkheads keyed by repository name.
     * Each group repository gets exactly one {@link com.auto1.pantera.http.resilience.RepoBulkhead}
     * at first access. Saturation in one repo cannot starve another (WI-09).
     */
    private final ConcurrentMap<String, com.auto1.pantera.http.resilience.RepoBulkhead> repoBulkheads =
        new ConcurrentHashMap<>();

    /**
     * Supplier of circuit-breaker settings. Every new {@link AutoBlockRegistry}
     * constructed in {@link #getOrCreateMemberRegistry} receives this supplier
     * so admin-time settings updates (via the system-settings UI) flow into
     * existing registries on the next recorded outcome. Defaults to
     * {@link com.auto1.pantera.http.timeout.AutoBlockSettings#defaults()}
     * when the DB-backed loader is not wired at construction time (tests,
     * legacy boot sequences).
     */
    private final java.util.function.Supplier<
        com.auto1.pantera.http.timeout.AutoBlockSettings
    > circuitBreakerSettings;

    /**
     * Supplier of upstream HTTP-client tuning sourced from the v2.2
     * {@code RuntimeSettingsCache}. Each call to
     * {@link SharedJettyClients#acquire(HttpClientSettings)} reads the current
     * snapshot to construct the underlying {@link JettyClientSlices} with the
     * negotiated protocol, h2 pool size, and h2 multiplexing limit.
     *
     * <p>When {@code http_client.*} settings change, the boot wiring in
     * {@code VertxMain} subscribes a listener that calls
     * {@link #invalidateUpstreamClients()} so subsequent acquires miss the
     * cache and rebuild with the new tuning.</p>
     *
     * <p>Defaults to {@link HttpTuning#defaults()} for tests and legacy boot
     * paths that don't provide a runtime cache.</p>
     */
    private final Supplier<HttpTuning> httpTuningSupplier;

    /**
     * When {@code true}, {@link SharedJettyClients} routes
     * {@link SharedClient} construction through the legacy 1-arg
     * {@link JettyClientSlices#JettyClientSlices(HttpClientSettings)} ctor,
     * which preserves the pre-Task-9 contract of using
     * {@code settings.maxConnectionsPerDestination()} from YAML for the
     * connection-pool cap. Set by the 3-/4-arg legacy {@code RepositorySlices}
     * constructors so callers that did not opt into the runtime tuning
     * supplier keep their YAML-driven pool sizing
     * (typically 20-50) instead of silently dropping to
     * {@link HttpTuning#defaults()}'s {@code h2MaxPoolSize == 1}.
     *
     * <p>Cleared (false) for the 5-arg ctor used by {@code VertxMain},
     * which threads {@link HttpTuning} through the 4-arg
     * {@link JettyClientSlices} ctor as designed in Phase 2 / Task 9.</p>
     */
    private final boolean useLegacyHttpClientCtor;

    /**
     * @param settings Pantera settings
     * @param repos Repositories
     * @param tokens Tokens: authentication and generation
     */
    public RepositorySlices(
        final Settings settings,
        final Repositories repos,
        final Tokens tokens
    ) {
        this(
            settings, repos, tokens,
            com.auto1.pantera.circuit.CircuitBreakerSettingsLoader.activeSupplier()
        );
    }

    /**
     * @param settings Pantera settings
     * @param repos Repositories
     * @param tokens Tokens: authentication and generation
     * @param circuitBreakerSettings Supplier returning the current circuit-
     *                               breaker settings; used by
     *                               {@link AutoBlockRegistry} on every
     *                               record to pick up admin-time updates
     */
    public RepositorySlices(
        final Settings settings,
        final Repositories repos,
        final Tokens tokens,
        final java.util.function.Supplier<
            com.auto1.pantera.http.timeout.AutoBlockSettings
        > circuitBreakerSettings
    ) {
        // Legacy 4-arg ctor: route SharedClient construction through the
        // legacy 1-arg JettyClientSlices(HttpClientSettings) ctor so the
        // YAML-driven settings.maxConnectionsPerDestination() (typically
        // 20-50) is preserved. Without this flag the supplier fallback
        // would silently apply HttpTuning.defaults().h2MaxPoolSize() == 1.
        this(settings, repos, tokens, circuitBreakerSettings, HttpTuning::defaults, true);
    }

    /**
     * @param settings Pantera settings
     * @param repos Repositories
     * @param tokens Tokens: authentication and generation
     * @param circuitBreakerSettings Supplier returning the current circuit-
     *                               breaker settings; used by
     *                               {@link AutoBlockRegistry} on every
     *                               record to pick up admin-time updates
     * @param httpTuningSupplier Supplier returning the current HTTP-client
     *                           tuning snapshot from the runtime settings
     *                           cache. Read on every
     *                           {@link SharedJettyClients#acquire(HttpClientSettings)}
     *                           so cache misses rebuild with the latest values.
     */
    public RepositorySlices(
        final Settings settings,
        final Repositories repos,
        final Tokens tokens,
        final java.util.function.Supplier<
            com.auto1.pantera.http.timeout.AutoBlockSettings
        > circuitBreakerSettings,
        final Supplier<HttpTuning> httpTuningSupplier
    ) {
        // 5-arg ctor (current production path used by VertxMain): the caller
        // supplied a real HttpTuning supplier, so we want SharedClient
        // construction to use the 4-arg JettyClientSlices ctor and pull
        // h2MaxPoolSize/h2MultiplexingLimit/protocol from the supplier on
        // every cache miss. useLegacyHttpClientCtor=false.
        this(settings, repos, tokens, circuitBreakerSettings, httpTuningSupplier, false);
    }

    /**
     * Internal canonical constructor — all public ctors funnel here. The
     * {@code useLegacyHttpClientCtor} flag selects between the legacy 1-arg
     * {@link JettyClientSlices} ctor (preserves YAML
     * {@code maxConnectionsPerDestination}) and the 4-arg ctor (uses
     * {@link HttpTuning} from the supplier). Kept package-private so tests
     * can force either path explicitly.
     *
     * @param settings Pantera settings
     * @param repos Repositories
     * @param tokens Tokens: authentication and generation
     * @param circuitBreakerSettings Supplier returning the current circuit-
     *                               breaker settings
     * @param httpTuningSupplier Supplier returning the current HTTP-client
     *                           tuning snapshot
     * @param useLegacyHttpClientCtor When true, route through the legacy
     *                                {@code JettyClientSlices(HttpClientSettings)}
     *                                ctor; otherwise use the 4-arg ctor.
     */
    RepositorySlices(
        final Settings settings,
        final Repositories repos,
        final Tokens tokens,
        final java.util.function.Supplier<
            com.auto1.pantera.http.timeout.AutoBlockSettings
        > circuitBreakerSettings,
        final Supplier<HttpTuning> httpTuningSupplier,
        final boolean useLegacyHttpClientCtor
    ) {
        this.circuitBreakerSettings = circuitBreakerSettings;
        this.httpTuningSupplier = httpTuningSupplier;
        this.useLegacyHttpClientCtor = useLegacyHttpClientCtor;
        this.settings = settings;
        this.repos = repos;
        this.tokens = tokens;
        this.cooldown = CooldownSupport.create(settings);
        this.cooldownMetadata = CooldownSupport.createMetadataService(this.cooldown, settings);
        // Register per-repo cooldown durations from repo configurations
        if (repos != null) {
            for (final RepoConfig cfg : repos.configs()) {
                cfg.cooldownDuration().ifPresent(duration ->
                    settings.cooldown().setRepoNameOverride(cfg.name(), true, duration)
                );
            }
        }
        this.sharedClients = new SharedJettyClients(httpTuningSupplier, useLegacyHttpClientCtor);
        // Load negative cache config once at construction time.
        // Reads repo-negative first; falls back to group-negative with deprecation WARN.
        this.negativeCacheConfig = loadNegativeCacheConfig(settings);
        this.sharedNegativeCache = new NegativeCache(this.negativeCacheConfig);
        com.auto1.pantera.http.cache.NegativeCacheRegistry.instance()
            .setSharedCache(this.sharedNegativeCache);
        this.slices = CacheBuilder.newBuilder()
            .maximumSize(500)
            .expireAfterAccess(30, java.util.concurrent.TimeUnit.MINUTES)
            .removalListener(
                (RemovalListener<SliceKey, SliceValue>) notification -> notification.getValue()
                    .client()
                    .ifPresent(SharedJettyClients.Lease::close)
            )
            .build(
                new CacheLoader<>() {
                    @Override
                    public SliceValue load(final SliceKey key) {
                        // Should not normally be used as we avoid caching NOT_FOUND entries
                        return resolve(key.name(), key.port(), 0).orElseGet(
                            () -> new SliceValue(
                                new SliceSimple(
                                    () -> ResponseBuilder.notFound()
                                        .textBody(
                                            String.format(
                                                "Repository '%s' not found",
                                                key.name().string()
                                            )
                                        )
                                        .build()
                                ),
                                Optional.empty()
                            )
                        );
                    }
                }
            );
    }

    /**
     * Resolve slice by name and port (top-level call with depth=0).
     * @param name Repository name
     * @param port Server port
     * @return Resolved slice
     */
    public Slice slice(final Key name, final int port) {
        return slice(name, port, 0);
    }

    /**
     * Resolve slice by name, port, and nesting depth.
     * @param name Repository name
     * @param port Server port
     * @param depth Nesting depth (0 for top-level, incremented in nested groups)
     * @return Resolved slice
     */
    public Slice slice(final Key name, final int port, final int depth) {
        final SliceKey skey = new SliceKey(name, port);
        final SliceValue cached = this.slices.getIfPresent(skey);
        if (cached != null) {
            EcsLogger.debug("com.auto1.pantera.settings")
                .message("Repository slice resolved from cache")
                .eventCategory("web")
                .eventAction("slice_resolve")
                .eventOutcome("success")
                .field("repository.name", name.string())
                .field("url.port", port)
                .log();
            return cached.slice();
        }
        final Optional<SliceValue> resolved = resolve(name, port, depth);
        if (resolved.isPresent()) {
            this.slices.put(skey, resolved.get());
            EcsLogger.debug("com.auto1.pantera.settings")
                .message("Repository slice resolved and cached from config")
                .eventCategory("web")
                .eventAction("slice_resolve")
                .eventOutcome("success")
                .field("repository.name", name.string())
                .field("url.port", port)
                .log();
            return resolved.get().slice();
        }
        // Not found is NOT cached to allow dynamic repo addition without restart.
        // Logged at INFO (v2.1.4 WI-00): this is a client-config error, not a
        // Pantera failure — clients misconfigured with stale repo names produce
        // a steady stream that was previously drowning WARN output (§1.7 F2.2).
        EcsLogger.info("com.auto1.pantera.settings")
            .message("Repository not found in configuration")
            .eventCategory("web")
            .eventAction("slice_resolve")
            .eventOutcome("failure")
            .field("repository.name", name.string())
            .field("url.port", port)
            .log();
        return new SliceSimple(
            () -> ResponseBuilder.notFound()
                .textBody(String.format("Repository '%s' not found", name.string()))
                .build()
        );
    }

    /**
     * Resolve {@link Slice} by provided configuration.
     *
     * @param name Repository name
     * @param port Repository port
     * @param depth Nesting depth for group repositories
     * @return Slice for repo
     */
    private Optional<SliceValue> resolve(final Key name, final int port, final int depth) {
        Optional<RepoConfig> opt = repos.config(name.string());
        if (opt.isPresent()) {
            final RepoConfig cfg = opt.get();
            if (cfg.port().isEmpty() || cfg.port().getAsInt() == port) {
                return Optional.of(sliceFromConfig(cfg, port, depth));
            }
        }
        return Optional.empty();
    }

    /**
     * Invalidate all cached slices for repository name across all ports.
     * @param name Repository name
     */
    public void invalidateRepo(final String name) {
        this.slices.asMap().keySet().stream()
            .filter(k -> k.name().string().equals(name))
            .forEach(this.slices::invalidate);
    }

    public void enableJettyMetrics(final MeterRegistry registry) {
        this.sharedClients.enableMetrics(registry);
    }

    /**
     * Drop every cached upstream Jetty client so subsequent
     * {@link SharedJettyClients#acquire(HttpClientSettings)} calls miss
     * the cache and rebuild with the latest {@link HttpTuning} snapshot.
     *
     * <p>Active leases keep using their existing client until released —
     * the eviction marks each {@code SharedClient} so the per-lease
     * {@code release()} path stops the client when its last lease closes.
     * Subsequent acquires after this method returns will see new clients
     * built with the current tuning.</p>
     *
     * <p>Called from the boot wiring's {@code RuntimeSettingsCache}
     * listener on every {@code http_client.*} change.</p>
     *
     * <p><b>Slow-drain semantics (intentional).</b> The slice
     * {@link com.google.common.cache.LoadingCache} held in this class
     * caches per-repo lease references; warm slices keep using their
     * already-acquired clients until the slice cache itself evicts (the
     * 30-minute idle-eviction TTL applied via
     * {@code .expireAfterAccess(30, MINUTES)} in the slice-cache builder)
     * or a settings change triggers a slice rebuild. We deliberately do
     * not force-evict warm slices here: doing so would interrupt in-flight
     * upstream requests on those leases. The trade-off is that a
     * {@code http_client.*} setting flip can take up to 30 minutes to
     * propagate to a long-warm slice. See CONCERN-task9-slice-cache-lag in
     * the v2.2.0 perf-pack audit doc.</p>
     */
    public void invalidateUpstreamClients() {
        this.sharedClients.invalidateAll();
    }

    /**
     * Maps the runtime-settings {@link HttpTuning.Protocol} enum onto the
     * http-client module's {@link JettyClientSlices.HttpProtocol}. The two
     * mirror each other deliberately — http-client cannot depend on the
     * pantera-main settings.runtime package without creating a cycle.
     *
     * @param protocol Protocol selector from the runtime tuning snapshot.
     * @return Equivalent http-client primitive.
     */
    static JettyClientSlices.HttpProtocol mapProtocol(final HttpTuning.Protocol protocol) {
        return switch (protocol) {
            case H1 -> JettyClientSlices.HttpProtocol.H1;
            case H2 -> JettyClientSlices.HttpProtocol.H2;
            case AUTO -> JettyClientSlices.HttpProtocol.AUTO;
        };
    }

    /**
     * Pre-build slices for every configured repository so their shared Jetty
     * clients finish starting before request traffic begins. Without this,
     * the first request for an uninitialized repo blocks its event-loop thread
     * inside {@code SharedClient.client()} on the {@code startFuture.join()}
     * until SSL context setup and connection-pool construction complete
     * (~100–500 ms).
     *
     * <p>Must be called from startup code (never an event-loop thread). The
     * call builds slices sequentially; slice construction is cheap — the real
     * cost is in async Jetty start which happens on {@code RESOLVE_EXECUTOR}
     * in parallel across all acquired clients, and is then awaited once.
     *
     * @param timeout Maximum time to wait for all clients to finish starting.
     */
    public void warmUp(final java.time.Duration timeout) {
        if (this.repos == null) {
            return;
        }
        int warmed = 0;
        for (final RepoConfig cfg : this.repos.configs()) {
            final int port = cfg.port().orElse(0);
            try {
                this.slice(new Key.From(cfg.name()), port);
                warmed += 1;
            } catch (final RuntimeException ex) {
                EcsLogger.warn("com.auto1.pantera.settings")
                    .message("Repository slice warm-up failed")
                    .eventCategory("configuration")
                    .eventAction("slice_warmup")
                    .eventOutcome("failure")
                    .field("repository.name", cfg.name())
                    .error(ex)
                    .log();
            }
        }
        this.sharedClients.awaitAllStarted(timeout);
        EcsLogger.info("com.auto1.pantera.settings")
            .message("Repository slices warmed up (count=" + warmed + ")")
            .eventCategory("configuration")
            .eventAction("slice_warmup")
            .eventOutcome("success")
            .log();
    }

    /**
     * Access underlying repositories registry.
     *
     * @return Repositories instance
     */
    public Repositories repositories() {
        return this.repos;
    }

    /**
     * Shared {@link NegativeCache} bean. Returned for use by the prefetch
     * coordinator so its negative-cache short-circuit shares the same
     * 404 cache the proxies populate. Never null after construction.
     *
     * @return shared NegativeCache instance.
     * @since 2.2.0
     */
    public NegativeCache negativeCache() {
        return this.sharedNegativeCache;
    }

    /**
     * Look up a repo's type ({@code "maven-proxy"}, {@code "npm-proxy"}, etc.)
     * by repo name. Returns {@code null} when the repo is not configured
     * (e.g., recently deleted) so the dispatcher's null-guard short-circuits.
     *
     * @param name Repo name to resolve.
     * @return Repo type or {@code null} when unknown.
     * @since 2.2.0
     */
    public String repoTypeOf(final String name) {
        if (name == null) {
            return null;
        }
        return this.repos.config(name).map(RepoConfig::type).orElse(null);
    }

    /**
     * Look up the upstream URL for a repo. Returns the first
     * {@link com.auto1.pantera.http.client.RemoteConfig#uri()} for proxy
     * repos; an empty string for non-proxy or unknown repos.
     *
     * <p>Used by the dispatcher to stamp every {@link com.auto1.pantera.prefetch.PrefetchTask#upstreamUrl()}
     * so the coordinator can route the GET via the right per-host
     * semaphore bucket.</p>
     *
     * @param name Repo name to resolve.
     * @return Upstream URL string, or empty when unknown / not a proxy.
     * @since 2.2.0
     */
    public String upstreamUrlOf(final String name) {
        if (name == null) {
            return "";
        }
        final Optional<RepoConfig> cfg = this.repos.config(name);
        if (cfg.isEmpty()) {
            return "";
        }
        final List<com.auto1.pantera.http.client.RemoteConfig> remotes = cfg.get().remotes();
        if (remotes == null || remotes.isEmpty()) {
            return "";
        }
        final java.net.URI uri = remotes.get(0).uri();
        return uri == null ? "" : uri.toString();
    }

    /**
     * Per-repo prefetch enable flag. Reads the
     * {@link com.auto1.pantera.settings.repo.RepoConfig#prefetchEnabled()}
     * accessor — which honours an explicit {@code settings.prefetch}
     * boolean and falls back to {@code true} for {@code *-proxy} types
     * and {@code false} for everything else. The global kill-switch
     * ({@code prefetch.enabled} from
     * {@link com.auto1.pantera.settings.runtime.RuntimeSettingsCache})
     * remains the master off-switch.
     *
     * @param name Repo name to resolve.
     * @return {@code Boolean.TRUE} when the repo opts in to prefetch,
     *     {@code Boolean.FALSE} otherwise (including unknown repos).
     * @since 2.2.0
     */
    public Boolean prefetchEnabledFor(final String name) {
        if (name == null) {
            return Boolean.FALSE;
        }
        return this.repos.config(name)
            .map(RepoConfig::prefetchEnabled)
            .orElse(Boolean.FALSE);
    }

    /**
     * Cooldown service used by the proxy adapters. Exposed for the prefetch
     * coordinator's {@code CooldownGate} adapter so prefetch decisions
     * honour the same admin-configured cooldown windows the foreground
     * requests are gated by.
     *
     * @return shared CooldownService.
     * @since 2.2.0
     */
    public CooldownService cooldownService() {
        return this.cooldown;
    }

    /**
     * Snapshot of every {@code npm-proxy} repository's storage in
     * configuration order, paired with its repo name. Used by the prefetch
     * subsystem's {@link com.auto1.pantera.prefetch.parser.CachedNpmMetadataLookup}
     * to resolve npm version ranges against locally-cached packuments
     * without ever issuing an upstream metadata fetch.
     *
     * <p>The result is computed afresh on every call so live repo additions
     * or removals (config reloads) are picked up by callers that wrap the
     * accessor in a {@code Supplier}. Repos with no storage configured are
     * skipped silently.</p>
     *
     * @return Ordered list of (repoName, storage) pairs for npm-proxy
     *     repositories; empty when no npm-proxy is configured.
     * @since 2.2.0
     */
    public java.util.List<
        com.auto1.pantera.prefetch.parser.CachedNpmMetadataLookup.NamedStorage
    > npmProxyStorages() {
        final java.util.List<
            com.auto1.pantera.prefetch.parser.CachedNpmMetadataLookup.NamedStorage
        > out = new java.util.ArrayList<>();
        for (final RepoConfig cfg : this.repos.configs()) {
            if (!"npm-proxy".equals(cfg.type())) {
                continue;
            }
            cfg.storageOpt().ifPresent(
                store -> out.add(
                    new com.auto1.pantera.prefetch.parser.CachedNpmMetadataLookup.NamedStorage(
                        cfg.name(), store
                    )
                )
            );
        }
        return out;
    }

    private Optional<Queue<ArtifactEvent>> artifactEvents() {
        return this.settings.artifactMetadata()
            .map(MetadataEventQueues::eventQueue);
    }

    private SliceValue sliceFromConfig(final RepoConfig cfg, final int port, final int depth) {
        Slice slice;
        SharedJettyClients.Lease clientLease = null;
        JettyClientSlices clientSlices = null;
        try {
            switch (cfg.type()) {
            case "file":
                slice = browsableTrimPathSlice(
                    new FilesSlice(
                        cfg.storage(),
                        securityPolicy(),
                        authentication(),
                        tokens.auth(),
                        cfg.name(),
                        artifactEvents()
                    ),
                    cfg.storage()
                );
                break;
            case "file-proxy":
                clientLease = jettyClientSlices(cfg);
                clientSlices = clientLease.client();
                final Slice fileProxySlice = new TimeoutSlice(
                    new FileProxy(clientSlices, cfg, artifactEvents(), this.cooldown),
                    settings.httpClientSettings().proxyTimeout()
                );
                // Browsing disabled for proxy repos - files are fetched on-demand from upstream
                slice = trimPathSlice(fileProxySlice);
                break;
            case "npm":
                slice = browsableTrimPathSlice(
                    new NpmSlice(
                        cfg.url(), cfg.storage(), securityPolicy(), authentication(), tokens.auth(), tokens, cfg.name(), artifactEvents(), true,
                        this.settings.syncArtifactIndexer()
                    ),
                    cfg.storage()
                );
                break;
            case "gem":
                slice = browsableTrimPathSlice(
                    new GemSlice(
                        cfg.storage(),
                        securityPolicy(),
                        authentication(),
                        tokens.auth(),
                        cfg.name(),
                        artifactEvents(),
                        this.settings.syncArtifactIndexer()
                    ),
                    cfg.storage()
                );
                break;
            case "helm":
                slice = browsableTrimPathSlice(
                    new HelmSlice(
                        cfg.storage(), cfg.url().toString(), securityPolicy(), authentication(), tokens.auth(), cfg.name(), artifactEvents(),
                        this.settings.syncArtifactIndexer()
                    ),
                    cfg.storage()
                );
                break;
            case "rpm":
                slice = browsableTrimPathSlice(
                    new RpmSlice(cfg.storage(), securityPolicy(), authentication(),
                        tokens.auth(), new com.auto1.pantera.rpm.RepoConfig.FromYaml(cfg.settings(), cfg.name()),
                        artifactEvents(),
                        this.settings.syncArtifactIndexer()),
                    cfg.storage()
                );
                break;
            case "php":
                // Extract base URL from config, handling trailing slashes consistently
                // The URL should be the full path to the repository for provider URLs to work
                String baseUrl = cfg.settings()
                    .flatMap(yaml -> Optional.ofNullable(yaml.string("url")))
                    .orElseGet(() -> cfg.url().toString());
                
                // Normalize: remove all trailing slashes
                baseUrl = baseUrl.replaceAll("/+$", "");
                
                // Ensure URL ends with the repository name for correct routing
                // Provider URLs will be: {baseUrl}/p2/%package%.json
                String normalizedRepo = cfg.name().replaceAll("^/+", "").replaceAll("/+$", "");
                if (!baseUrl.endsWith("/" + normalizedRepo)) {
                    baseUrl = baseUrl + "/" + normalizedRepo;
                }
                
                slice = browsableTrimPathSlice(
                    new PathPrefixStripSlice(
                        new PhpComposer(
                            new AstoRepository(
                                cfg.storage(),
                                Optional.of(baseUrl),
                                Optional.of(cfg.name())
                            ),
                            securityPolicy(),
                            authentication(),
                            tokens.auth(),
                            cfg.name(),
                            artifactEvents(),
                            this.settings.syncArtifactIndexer()
                        ),
                        "direct-dists"
                    ),
                    cfg.storage()
                );
                break;
            case "php-proxy":
                clientLease = jettyClientSlices(cfg);
                clientSlices = clientLease.client();
                slice = trimPathSlice(
                    new PathPrefixStripSlice(
                        new TimeoutSlice(
                            new ComposerProxy(
                                clientSlices,
                                cfg,
                                settings.artifactMetadata().flatMap(queues -> queues.proxyEventQueues(cfg)),
                                this.cooldown
                            ),
                            settings.httpClientSettings().proxyTimeout()
                        ),
                        "direct-dists"
                    )
                );
                break;
            case "nuget":
                slice = browsableTrimPathSlice(
                    new NuGet(
                        cfg.url(), new com.auto1.pantera.nuget.AstoRepository(cfg.storage()),
                        securityPolicy(), authentication(), tokens.auth(), cfg.name(), artifactEvents(),
                        this.settings.syncArtifactIndexer()
                    ),
                    cfg.storage()
                );
                break;
            case "gradle":
            case "maven":
                slice = browsableTrimPathSlice(
                    new MavenSlice(cfg.storage(), securityPolicy(),
                        authentication(), tokens.auth(), cfg.name(), artifactEvents(),
                        this.settings.syncArtifactIndexer()),
                    cfg.storage()
                );
                break;
            case "gradle-proxy":
            case "maven-proxy":
                clientLease = jettyClientSlices(cfg);
                clientSlices = clientLease.client();
                final Slice mavenProxySlice = new CombinedAuthzSliceWrap(
                    new TimeoutSlice(
                        new MavenProxy(
                            clientSlices,
                            cfg,
                            settings.artifactMetadata().flatMap(queues -> queues.proxyEventQueues(cfg)),
                            this.cooldown,
                            this.cooldownMetadata
                        ),
                        settings.httpClientSettings().proxyTimeout()
                    ),
                    authentication(),
                    tokens.auth(),
                    new OperationControl(
                        securityPolicy(),
                        new AdapterBasicPermission(cfg.name(), Action.Standard.READ)
                    )
                );
                // Browsing disabled for proxy repos - files are fetched on-demand from upstream
                // Directory structure is not meaningful for proxies
                slice = trimPathSlice(mavenProxySlice);
                break;
            case "go":
                slice = browsableTrimPathSlice(
                    new GoSlice(
                        cfg.storage(),
                        securityPolicy(),
                        authentication(),
                        tokens.auth(),
                        cfg.name(),
                        artifactEvents(),
                        this.settings.syncArtifactIndexer()
                    ),
                    cfg.storage()
                );
                break;
            case "go-proxy":
                clientLease = jettyClientSlices(cfg);
                clientSlices = clientLease.client();
                slice = trimPathSlice(
                    new CombinedAuthzSliceWrap(
                        new TimeoutSlice(
                            new GoProxy(
                                clientSlices,
                                cfg,
                                settings.artifactMetadata().flatMap(queues -> queues.proxyEventQueues(cfg)),
                                this.cooldown
                            ),
                            settings.httpClientSettings().proxyTimeout()
                        ),
                        authentication(),
                        tokens.auth(),
                        new OperationControl(
                            securityPolicy(),
                            new AdapterBasicPermission(cfg.name(), Action.Standard.READ)
                        )
                    )
                );
                break;
            case "npm-proxy":
                clientLease = jettyClientSlices(cfg);
                clientSlices = clientLease.client();
                final Slice npmProxySlice = new TimeoutSlice(
                    new com.auto1.pantera.adapters.npm.NpmProxyAdapter(
                        clientSlices,
                        cfg,
                        settings.artifactMetadata().flatMap(queues -> queues.proxyEventQueues(cfg)),
                        this.cooldown,
                        this.cooldownMetadata
                    ),
                    settings.httpClientSettings().proxyTimeout()
                );
                // npm-proxy routing: audit anonymous (via SecurityAuditProxySlice), login blocked, downloads require JWT
                slice = trimPathSlice(
                    new com.auto1.pantera.http.rt.SliceRoute(
                    // Audit - anonymous, SecurityAuditProxySlice already strips headers
                    new com.auto1.pantera.http.rt.RtRulePath(
                        new com.auto1.pantera.http.rt.RtRule.All(
                            com.auto1.pantera.http.rt.MethodRule.POST,
                            new com.auto1.pantera.http.rt.RtRule.ByPath(".*/-/npm/v1/security/.*")
                        ),
                        npmProxySlice
                    ),
                    // Block login/adduser/whoami - proxy is read-only
                    // NOTE: Do NOT block generic /auth paths - they conflict with scoped packages
                    // like @verdaccio/auth. Standard NPM auth uses /-/user/ and /-/v1/login.
                    new com.auto1.pantera.http.rt.RtRulePath(
                        new com.auto1.pantera.http.rt.RtRule.Any(
                            new com.auto1.pantera.http.rt.RtRule.ByPath(".*/-/v1/login.*"),
                            new com.auto1.pantera.http.rt.RtRule.ByPath(".*/-/user/.*"),
                            new com.auto1.pantera.http.rt.RtRule.ByPath(".*/-/whoami.*")
                        ),
                        new com.auto1.pantera.http.slice.SliceSimple(
                            com.auto1.pantera.http.ResponseBuilder.forbidden()
                                .textBody("User management not supported on proxy. Use local npm repository.")
                                .build()
                        )
                    ),
                    // Downloads - require Keycloak JWT
                    new com.auto1.pantera.http.rt.RtRulePath(
                        com.auto1.pantera.http.rt.RtRule.FALLBACK,
                        new CombinedAuthzSliceWrap(
                            npmProxySlice,
                            authentication(),
                            tokens.auth(),
                            new OperationControl(
                                securityPolicy(),
                                new AdapterBasicPermission(cfg.name(), Action.Standard.READ)
                            )
                        )
                    )
                )
                );
                break;
            case "npm-group":
                warnIfLegacyMembersStrategy(cfg);
                final List<String> npmFlatMembers = flattenMembers(cfg.name(), cfg.members());
                final Slice npmGroupSlice = new GroupResolver(
                    this::slice, cfg.name(), npmFlatMembers, port, depth,
                    cfg.groupMemberTimeout().orElse(120L),
                    java.util.Collections.emptyList(),
                    Optional.of(this.settings.artifactIndex()),
                    proxyMembers(npmFlatMembers),
                    "npm-group",
                    this.sharedNegativeCache,
                    this::getOrCreateMemberRegistry,
                    getOrCreateBulkhead(cfg.name()).drainExecutor()
                );
                // Create audit slice that aggregates results from ALL members
                // This is critical for vulnerability scanning - local repos return {},
                // but proxy repos return actual vulnerabilities from upstream
                // CRITICAL: Pass member NAMES so GroupAuditSlice can rewrite paths!
                final java.util.List<String> auditMemberNames = cfg.members();
                final java.util.List<Slice> auditMemberSlices = auditMemberNames.stream()
                    .map(name -> this.slice(new Key.From(name), port, 0))
                    .collect(java.util.stream.Collectors.toList());
                final Slice npmGroupAuditSlice = new com.auto1.pantera.npm.http.audit.GroupAuditSlice(
                    auditMemberNames, auditMemberSlices
                );
                // npm-group: audit anonymous, user management blocked, all other operations require auth
                slice = trimPathSlice(
                    new com.auto1.pantera.http.rt.SliceRoute(
                        // Audit - anonymous, uses GroupAuditSlice to aggregate from all members
                        new com.auto1.pantera.http.rt.RtRulePath(
                            new com.auto1.pantera.http.rt.RtRule.All(
                                com.auto1.pantera.http.rt.MethodRule.POST,
                                new com.auto1.pantera.http.rt.RtRule.ByPath(".*/-/npm/v1/security/.*")
                            ),
                            npmGroupAuditSlice
                        ),
                        // Block login/adduser/whoami - group is read-only
                        // NOTE: Do NOT block generic /auth paths - they conflict with scoped packages
                        // like @verdaccio/auth. Standard NPM auth uses /-/user/ and /-/v1/login.
                        new com.auto1.pantera.http.rt.RtRulePath(
                            new com.auto1.pantera.http.rt.RtRule.Any(
                                new com.auto1.pantera.http.rt.RtRule.ByPath(".*/-/v1/login.*"),
                                new com.auto1.pantera.http.rt.RtRule.ByPath(".*/-/user/.*"),
                                new com.auto1.pantera.http.rt.RtRule.ByPath(".*/-/whoami.*")
                            ),
                            new com.auto1.pantera.http.slice.SliceSimple(
                                com.auto1.pantera.http.ResponseBuilder.forbidden()
                                    .textBody("User management not supported on group. Use local npm repository.")
                                    .build()
                            )
                        ),
                        // All other operations - require JWT
                        new com.auto1.pantera.http.rt.RtRulePath(
                            com.auto1.pantera.http.rt.RtRule.FALLBACK,
                            new CombinedAuthzSliceWrap(
                                npmGroupSlice,
                                authentication(),
                                tokens.auth(),
                                new OperationControl(
                                    securityPolicy(),
                                    new AdapterBasicPermission(cfg.name(), Action.Standard.READ)
                                )
                            )
                        )
                    )
                );
                break;
            case "file-group":
            case "php-group":
                warnIfLegacyMembersStrategy(cfg);
                final List<String> composerFlatMembers = flattenMembers(cfg.name(), cfg.members());
                final GroupResolver composerDelegate = new GroupResolver(
                    this::slice, cfg.name(), composerFlatMembers, port, depth,
                    cfg.groupMemberTimeout().orElse(120L),
                    java.util.Collections.emptyList(),
                    Optional.of(this.settings.artifactIndex()),
                    proxyMembers(composerFlatMembers),
                    cfg.type(),
                    this.sharedNegativeCache,
                    this::getOrCreateMemberRegistry,
                    getOrCreateBulkhead(cfg.name()).drainExecutor()
                );
                slice = trimPathSlice(
                    new CombinedAuthzSliceWrap(
                        new ComposerGroupSlice(
                            composerDelegate,
                            this::slice, cfg.name(), cfg.members(), port,
                            this.settings.prefixes().prefixes().stream()
                                .findFirst().orElse(""),
                            this.cooldownMetadata,
                            cfg.type()
                        ),
                        authentication(),
                        tokens.auth(),
                        new OperationControl(
                            securityPolicy(),
                            new AdapterBasicPermission(cfg.name(), Action.Standard.READ)
                        )
                    )
                );
                break;
            case "maven-group":
            case "gradle-group":
                // Maven AND Gradle groups need maven-metadata.xml merge +
                // cooldown filter on the merged result. Gradle uses the same
                // metadata format as Maven, so it routes through the same
                // slice — previously gradle-group fell into the generic
                // GroupResolver case which can't merge metadata.
                warnIfLegacyMembersStrategy(cfg);
                final List<String> mavenFlatMembers = flattenMembers(cfg.name(), cfg.members());
                final GroupResolver mavenDelegate = new GroupResolver(
                    this::slice, cfg.name(), mavenFlatMembers, port, depth,
                    cfg.groupMemberTimeout().orElse(120L),
                    java.util.Collections.emptyList(),
                    Optional.of(this.settings.artifactIndex()),
                    proxyMembers(mavenFlatMembers),
                    cfg.type(),
                    this.sharedNegativeCache,
                    this::getOrCreateMemberRegistry,
                    getOrCreateBulkhead(cfg.name()).drainExecutor()
                );
                slice = trimPathSlice(
                    new CombinedAuthzSliceWrap(
                        new com.auto1.pantera.group.MavenGroupSlice(
                            mavenDelegate,
                            cfg.name(),
                            cfg.members(),
                            this::slice,
                            port,
                            depth,
                            new com.auto1.pantera.group.GroupMetadataCache(cfg.name()),
                            this.cooldownMetadata,
                            cfg.type()
                        ),
                        authentication(),
                        tokens.auth(),
                        new OperationControl(
                            securityPolicy(),
                            new AdapterBasicPermission(cfg.name(), Action.Standard.READ)
                        )
                    )
                );
                break;
            case "gem-group":
            case "go-group":
            case "pypi-group":
            case "docker-group":
                warnIfLegacyMembersStrategy(cfg);
                final List<String> genericFlatMembers = flattenMembers(cfg.name(), cfg.members());
                slice = trimPathSlice(
                    new CombinedAuthzSliceWrap(
                        new GroupResolver(
                            this::slice, cfg.name(), genericFlatMembers, port, depth,
                            cfg.groupMemberTimeout().orElse(120L),
                            java.util.Collections.emptyList(),
                            Optional.of(this.settings.artifactIndex()),
                            proxyMembers(genericFlatMembers),
                            cfg.type(),
                            this.sharedNegativeCache,
                            this::getOrCreateMemberRegistry,
                            getOrCreateBulkhead(cfg.name()).drainExecutor()
                        ),
                        authentication(),
                        tokens.auth(),
                        new OperationControl(
                            securityPolicy(),
                            new AdapterBasicPermission(cfg.name(), Action.Standard.READ)
                        )
                    )
                );
                break;
            case "pypi-proxy":
                clientLease = jettyClientSlices(cfg);
                clientSlices = clientLease.client();
                slice = trimPathSlice(
                    new PathPrefixStripSlice(
                        new CombinedAuthzSliceWrap(
                            new TimeoutSlice(
                                new PypiProxy(
                                    clientSlices,
                                    cfg,
                                    settings.artifactMetadata()
                                        .flatMap(queues -> queues.proxyEventQueues(cfg)),
                                    this.cooldown
                                ),
                                settings.httpClientSettings().proxyTimeout()
                            ),
                            authentication(),
                            tokens.auth(),
                            new OperationControl(
                                securityPolicy(),
                                new AdapterBasicPermission(cfg.name(), Action.Standard.READ)
                            )
                        ),
                        "simple"
                    )
                );
                break;
            case "docker":
                final Docker docker = new AstoDocker(
                    cfg.name(),
                    new SubStorage(RegistryRoot.V2, cfg.storage())
                );
                if (cfg.port().isPresent()) {
                    slice = new DockerSlice(docker, securityPolicy(),
                        new CombinedAuthScheme(authentication(), tokens.auth()), artifactEvents(),
                        this.settings.syncArtifactIndexer());
                } else {
                    slice = new DockerRoutingSlice.Reverted(
                        new DockerSlice(new TrimmedDocker(docker, cfg.name()),
                            securityPolicy(), new CombinedAuthScheme(authentication(), tokens.auth()),
                            artifactEvents(),
                            this.settings.syncArtifactIndexer())
                    );
                }
                break;
            case "docker-proxy":
                clientLease = jettyClientSlices(cfg);
                clientSlices = clientLease.client();
                slice = new TimeoutSlice(
                    new DockerProxy(
                        clientSlices,
                        cfg,
                        securityPolicy(),
                        authentication(),
                        tokens.auth(),
                        artifactEvents(),
                        this.cooldown
                    ),
                    settings.httpClientSettings().proxyTimeout()
                );
                break;
            case "deb":
                slice = trimPathSlice(
                    new DebianSlice(
                        cfg.storage(), securityPolicy(), authentication(),
                        new com.auto1.pantera.debian.Config.FromYaml(cfg.name(), cfg.settings(), settings.configStorage()),
                        artifactEvents(),
                        this.settings.syncArtifactIndexer()
                    )
                );
                break;
            case "conda":
                slice = new CondaSlice(
                    cfg.storage(), securityPolicy(), authentication(), tokens,
                    cfg.url().toString(), cfg.name(), artifactEvents(),
                    this.settings.syncArtifactIndexer()
                );
                break;
            case "conan":
                // ItemTokenizer signs its own per-item JWTs with the same RS256
                // key pair the main auth flow uses. The Tokens interface does
                // not expose the keys, so the cast is explicit — production
                // always wires a JwtTokens here (see VertxMain).
                final com.auto1.pantera.auth.JwtTokens jwtTokens =
                    (com.auto1.pantera.auth.JwtTokens) tokens;
                slice = new ConanSlice(
                    cfg.storage(), securityPolicy(), authentication(), tokens,
                    new ItemTokenizer(
                        Vertx.vertx(), jwtTokens.publicKey(), jwtTokens.privateKey()
                    ),
                    cfg.name(), artifactEvents()
                );
                break;
            case "hexpm":
                slice = trimPathSlice(
                    new HexSlice(cfg.storage(), securityPolicy(), authentication(),
                        artifactEvents(), cfg.name(),
                        this.settings.syncArtifactIndexer())
                );
                break;
            case "pypi":
                slice = trimPathSlice(
                    new PathPrefixStripSlice(
                        new com.auto1.pantera.pypi.http.PySlice(
                            cfg.storage(), securityPolicy(), authentication(),
                            null, cfg.name(), artifactEvents(),
                            this.settings.syncArtifactIndexer()
                        ),
                        "simple"
                    )
                );
                break;
            default:
                throw new IllegalStateException(
                    String.format("Unsupported repository type '%s", cfg.type())
                );
        }
        return new SliceValue(
            wrapIntoCommonSlices(slice, cfg),
            Optional.ofNullable(clientLease)
        );
        } catch (final Exception ex) {
            if (clientLease != null) {
                clientLease.close();
            }
            if (ex instanceof RuntimeException) {
                throw (RuntimeException) ex;
            }
            throw new IllegalStateException(
                String.format("Failed to construct adapter slice for '%s'", cfg.name()), ex
            );
        } catch (final Error ex) {
            if (clientLease != null) {
                clientLease.close();
            }
            throw ex;
        }
    }

    private Slice wrapIntoCommonSlices(
        final Slice origin,
        final RepoConfig cfg
    ) {
        Optional<Filters> opt = settings.caches()
            .filtersCache()
            .filters(cfg.name(), cfg.repoYaml());
        Slice filtered = opt.isPresent() ? new FilterSlice(origin, opt.get()) : origin;

        // Wrap with repository metrics to add repo_name and repo_type labels
        final Slice withMetrics = new com.auto1.pantera.http.slice.RepoMetricsSlice(
            filtered, cfg.name(), cfg.type()
        );

        return cfg.contentLengthMax()
            .<Slice>map(limit -> new ContentLengthRestriction(withMetrics, limit))
            .orElse(withMetrics);
    }

    private Authentication authentication() {
        return new LoggingAuth(settings.authz().authentication());
    }

    private Policy<?> securityPolicy() {
        return this.settings.authz().policy();
    }

    private SharedJettyClients.Lease jettyClientSlices(final RepoConfig cfg) {
        final HttpClientSettings effective = cfg.httpClientSettings()
            .orElseGet(settings::httpClientSettings);
        return this.sharedClients.acquire(effective);
    }

    private static Slice trimPathSlice(final Slice original) {
        return new TrimPathSlice(original, RepositorySlices.PATTERN);
    }

    /**
     * Wrap slice with BrowsableSlice inside TrimPathSlice.
     * BrowsableSlice must be inside TrimPathSlice so it sees the trimmed path
     * (e.g., "/" instead of "/reponame") when generating directory listings.
     *
     * @param origin Origin slice (the adapter slice)
     * @param storage Repository storage for directory listings
     * @return Slice chain: TrimPathSlice(BrowsableSlice(origin))
     */
    private static Slice browsableTrimPathSlice(final Slice origin, final com.auto1.pantera.asto.Storage storage) {
        return trimPathSlice(
            new com.auto1.pantera.http.slice.BrowsableSlice(origin, storage)
        );
    }

    /**
     * Determine which group members are proxy repositories.
     * A member is a proxy if its repo type ends with "-proxy".
     *
     * @param members Member repository names
     * @return Set of member names that are proxy repositories
     */
    private Set<String> proxyMembers(final List<String> members) {
        return members.stream()
            .filter(this::isProxyOrContainsProxy)
            .collect(java.util.stream.Collectors.toSet());
    }

    /**
     * Check if a member is a proxy repo or a group that contains proxy repos.
     * Nested groups that contain proxies must be treated as proxy-like because
     * their content is only indexed after being cached from upstream.
     * @param name Member repository name
     * @return True if proxy or group containing proxies
     */
    private boolean isProxyOrContainsProxy(final String name) {
        return this.repos.config(name)
            .map(c -> {
                final String type = c.type();
                if (type.endsWith("-proxy")) {
                    return true;
                }
                if (type.endsWith("-group")) {
                    return c.members().stream().anyMatch(this::isProxyOrContainsProxy);
                }
                return false;
            })
            .orElse(false);
    }


    /**
     * Load negative cache config from YAML.
     *
     * <p>Reads {@code meta.caches.repo-negative} first (the v2.2 canonical key).
     * If absent, falls back to the legacy {@code meta.caches.group-negative} key
     * and emits a deprecation WARN.  When neither key is present, returns the
     * historical defaults (5 min / 10K / in-memory only) to preserve backwards
     * compatibility.
     *
     * @param settings Pantera settings
     * @return Unified negative cache config
     */
    private static NegativeCacheConfig loadNegativeCacheConfig(final Settings settings) {
        final com.amihaiemil.eoyaml.YamlMapping caches = settings != null && settings.meta() != null
            ? settings.meta().yamlMapping("caches")
            : null;
        // Try the new canonical key first
        if (caches != null && caches.yamlMapping("repo-negative") != null) {
            return NegativeCacheConfig.fromYaml(caches, "repo-negative");
        }
        // Fall back to legacy key with deprecation WARN
        if (caches != null && caches.yamlMapping("group-negative") != null) {
            EcsLogger.warn("com.auto1.pantera.settings")
                .message("YAML key 'meta.caches.group-negative' is deprecated; "
                    + "rename to 'meta.caches.repo-negative' — legacy key will be "
                    + "removed in a future release")
                .eventCategory("configuration")
                .eventAction("yaml_deprecation")
                .log();
            return NegativeCacheConfig.fromYaml(caches, "group-negative");
        }
        // Neither key present — preserve pre-YAML defaults
        return new NegativeCacheConfig(
            java.time.Duration.ofMinutes(5),
            10_000,
            false,
            NegativeCacheConfig.DEFAULT_L1_MAX_SIZE,
            NegativeCacheConfig.DEFAULT_L1_TTL,
            NegativeCacheConfig.DEFAULT_L2_MAX_SIZE,
            NegativeCacheConfig.DEFAULT_L2_TTL
        );
    }

    /**
     * Flatten nested group members into leaf repos using GroupMemberFlattener.
     * Returns the flat list of leaf repo names for direct querying.
     *
     * @param groupName Group repository name (for cycle-detection logging)
     * @param directMembers Direct member names declared in this group's config
     * @return Flat, deduplicated list of leaf repo names (no nested groups)
     */
    private List<String> flattenMembers(final String groupName, final List<String> directMembers) {
        final com.auto1.pantera.group.GroupMemberFlattener flattener =
            new com.auto1.pantera.group.GroupMemberFlattener(
                name -> this.repos.config(name)
                    .map(c -> c.type() != null && c.type().endsWith("-group"))
                    .orElse(false),
                name -> this.repos.config(name)
                    .map(c -> c.members())
                    .orElse(List.of())
            );
        return flattener.flatten(groupName);
    }

    /**
     * Names of group repos that have already had their legacy
     * {@code members_strategy} YAML key WARN'd about, so the WARN fires at
     * most once per (process-lifetime, group). Sequential is the only
     * fanout mode in v2.2.0 — the YAML key is preserved for forward-compat
     * config tolerance only.
     */
    private static final java.util.Set<String> WARNED_LEGACY_STRATEGY_REPOS =
        java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Tolerate the legacy {@code members_strategy} YAML key (removed in
     * v2.2.0). If present and non-blank, log a one-time WARN per group at
     * boot identifying the deprecated key; the key is otherwise ignored —
     * group fanout is sequential everywhere now (Nexus / JFrog style).
     *
     * @param cfg Group repository config
     */
    private static void warnIfLegacyMembersStrategy(final RepoConfig cfg) {
        final String raw = cfg.settings()
            .map(yaml -> yaml.string("members_strategy"))
            .orElse(null);
        if (raw == null || raw.isBlank()) {
            return;
        }
        if (WARNED_LEGACY_STRATEGY_REPOS.add(cfg.name())) {
            EcsLogger.warn("com.auto1.pantera")
                .message("Group '" + cfg.name()
                    + "' YAML still declares members_strategy='" + raw.trim()
                    + "'. The members_strategy key is removed in v2.2.0; "
                    + "all group fanout is now sequential (members tried in "
                    + "declared order, first 2xx wins). The key is ignored. "
                    + "See docs/admin-guide/group-member-ordering.md.")
                .eventCategory("configuration")
                .eventAction("group_legacy_members_strategy")
                .field("repository.name", cfg.name())
                .field("members_strategy.declared", raw.trim())
                .log();
        }
    }

    /**
     * Return the shared {@link AutoBlockRegistry} for {@code memberName}, creating one
     * with default settings on first access.  Subsequent lookups for the same name
     * (from different groups) return the same instance so circuit-breaker state is
     * consolidated across all groups that share a physical upstream.
     *
     * @param memberName Physical leaf repo name
     * @return Shared registry for that upstream
     */
    private AutoBlockRegistry getOrCreateMemberRegistry(final String memberName) {
        return this.memberRegistries.computeIfAbsent(
            memberName,
            n -> {
                EcsLogger.info("com.auto1.pantera")
                    .message("Member circuit-breaker registry created for upstream: " + n
                        + " (total shared registries: " + (this.memberRegistries.size() + 1) + ")")
                    .eventCategory("configuration")
                    .eventAction("circuit_breaker_init")
                    .log();
                return new AutoBlockRegistry(this.circuitBreakerSettings);
            }
        );
    }

    /**
     * Get or create a per-repo {@link com.auto1.pantera.http.resilience.RepoBulkhead}
     * for the given group repository name (WI-09).
     *
     * @param repoName Group repository name
     * @return Per-repo bulkhead (created on first access with default limits)
     */
    private com.auto1.pantera.http.resilience.RepoBulkhead getOrCreateBulkhead(final String repoName) {
        return this.repoBulkheads.computeIfAbsent(
            repoName,
            n -> {
                final com.auto1.pantera.http.resilience.BulkheadLimits limits =
                    com.auto1.pantera.http.resilience.BulkheadLimits.defaults();
                EcsLogger.info("com.auto1.pantera")
                    .message("Per-repo bulkhead created for: " + n
                        + " (maxConcurrent=" + limits.maxConcurrent()
                        + ", maxQueueDepth=" + limits.maxQueueDepth() + ")")
                    .eventCategory("configuration")
                    .eventAction("bulkhead_init")
                    .log();
                return new com.auto1.pantera.http.resilience.RepoBulkhead(
                    n, limits, java.util.concurrent.ForkJoinPool.commonPool()
                );
            }
        );
    }

    /**
     * Slice's cache key.
     */
    record SliceKey(Key name, int port) {
    }

    /**
     * Slice's cache value.
     */
    record SliceValue(Slice slice, Optional<SharedJettyClients.Lease> client) {
    }

    /**
     * Stores and shares Jetty clients per unique HTTP client configuration.
     *
     * <p>Package-private (rather than {@code private}) so unit tests in the
     * same package — see {@code SharedJettyClientsInvalidateTest} — can
     * exercise {@link #invalidateAll} directly without going through the
     * heavyweight slice construction path.</p>
     */
    static final class SharedJettyClients {

        private final ConcurrentMap<HttpClientSettingsKey, SharedClient> clients = new ConcurrentHashMap<>();
        private final AtomicReference<MeterRegistry> metrics = new AtomicReference<>();
        private final Supplier<HttpTuning> httpTuningSupplier;
        /**
         * When true, build {@link SharedClient}s via the legacy
         * {@link JettyClientSlices#JettyClientSlices(HttpClientSettings)} ctor
         * so the YAML {@code maxConnectionsPerDestination} is preserved
         * (pre-Task-9 behaviour). Set by the legacy 3-/4-arg
         * {@link RepositorySlices} constructors.
         */
        private final boolean useLegacyHttpClientCtor;

        SharedJettyClients(final Supplier<HttpTuning> httpTuningSupplier) {
            this(httpTuningSupplier, false);
        }

        SharedJettyClients(
            final Supplier<HttpTuning> httpTuningSupplier,
            final boolean useLegacyHttpClientCtor
        ) {
            this.httpTuningSupplier = httpTuningSupplier;
            this.useLegacyHttpClientCtor = useLegacyHttpClientCtor;
        }

        /**
         * Test hook: number of currently cached shared clients (one per
         * unique {@link HttpClientSettingsKey}). Drops to zero immediately
         * after {@link #invalidateAll} returns.
         */
        int cachedClientCount() {
            return this.clients.size();
        }

        Lease acquire(final HttpClientSettings settings) {
            final HttpClientSettingsKey key = HttpClientSettingsKey.from(settings);
            final SharedClient holder = this.clients.compute(
                key,
                (ignored, existing) -> {
                    if (existing == null) {
                        final HttpTuning tuning = this.httpTuningSupplier.get();
                        final SharedClient created = new SharedClient(
                            key, tuning, this.useLegacyHttpClientCtor
                        );
                        created.retain();
                        return created;
                    }
                    existing.retain();
                    return existing;
                }
            );
            final MeterRegistry registry = this.metrics.get();
            if (registry != null) {
                holder.registerMetrics(registry);
            }
            return new Lease(this, key, holder);
        }

        /**
         * Drop every cached client so the next {@link #acquire} miss
         * rebuilds with the latest {@link HttpTuning}. Active leases
         * keep their reference; the per-lease {@code release()} path
         * stops the client once refs hit zero.
         */
        void invalidateAll() {
            // Snapshot keys to avoid concurrent-modification surprises while
            // we mutate the map.
            final java.util.List<HttpClientSettingsKey> keys =
                new java.util.ArrayList<>(this.clients.keySet());
            int evictedNoRefs = 0;
            int evictedHeld = 0;
            for (final HttpClientSettingsKey key : keys) {
                final SharedClient[] removedRef = new SharedClient[1];
                this.clients.computeIfPresent(key, (k, existing) -> {
                    existing.markEvicted();
                    removedRef[0] = existing;
                    return null;
                });
                final SharedClient removed = removedRef[0];
                if (removed != null && removed.referenceCount() == 0) {
                    // Race-safe: stop() is idempotent (guarded by
                    // JettyClientSlices.stopped). If a release() ran
                    // between markEvicted and this check it may have
                    // stopped already; that's fine.
                    removed.stop();
                    evictedNoRefs += 1;
                } else if (removed != null) {
                    evictedHeld += 1;
                }
            }
            EcsLogger.info("com.auto1.pantera")
                .message("Upstream Jetty client pool invalidated")
                .eventCategory("configuration")
                .eventAction("http_client_invalidate")
                .eventOutcome("success")
                .field("clients.evicted_no_refs", evictedNoRefs)
                .field("clients.evicted_with_active_leases", evictedHeld)
                .log();
        }

        void enableMetrics(final MeterRegistry registry) {
            this.metrics.set(registry);
            this.clients.values().forEach(client -> client.registerMetrics(registry));
        }

        /**
         * Block until every currently-tracked {@link SharedClient} has finished
         * its asynchronous Jetty start. Intended to be called from startup code
         * (never the event-loop) after all repositories have been pre-acquired,
         * so later {@code client()} calls find their {@code startFuture} already
         * complete and return without parking the caller.
         *
         * @param timeout Max time to wait for all clients to start.
         * @throws IllegalStateException if any client fails to start within the
         *     timeout; partially-started clients remain tracked for later retry.
         */
        void awaitAllStarted(final java.time.Duration timeout) {
            final java.util.List<CompletableFuture<Void>> futures = this.clients.values().stream()
                .map(c -> c.startFuture)
                .toList();
            if (futures.isEmpty()) {
                return;
            }
            try {
                CompletableFuture
                    .allOf(futures.toArray(new CompletableFuture[0]))
                    .get(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (final InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                    "Interrupted while waiting for Jetty clients to start", ex
                );
            } catch (final java.util.concurrent.ExecutionException
                           | java.util.concurrent.TimeoutException ex) {
                throw new IllegalStateException(
                    "Jetty client warm-up did not complete within " + timeout, ex
                );
            }
        }

        private void release(final HttpClientSettingsKey key, final SharedClient shared) {
            // Evicted clients have already been removed from the cache map;
            // their lifecycle is no longer tied to the map entry. Release
            // the lease's ref and stop when the last lease drops it.
            if (shared.isEvicted()) {
                final int remaining = shared.release();
                if (remaining == 0) {
                    shared.stop();
                }
                return;
            }
            this.clients.computeIfPresent(
                key,
                (ignored, existing) -> {
                    if (existing != shared) {
                        // The cached entry was replaced (evict + new acquire
                        // for the same key). Drop the lease's ref against
                        // the original SharedClient and stop if last.
                        final int remaining = shared.release();
                        if (remaining == 0) {
                            shared.stop();
                        }
                        return existing;
                    }
                    final int remaining = existing.release();
                    if (remaining == 0) {
                        existing.stop();
                        return null;
                    }
                    return existing;
                }
            );
        }

        static final class Lease implements AutoCloseable {
            private final SharedJettyClients owner;
            private final HttpClientSettingsKey key;
            private final SharedClient shared;
            private final AtomicBoolean closed = new AtomicBoolean(false);

            Lease(
                final SharedJettyClients owner,
                final HttpClientSettingsKey key,
                final SharedClient shared
            ) {
                this.owner = owner;
                this.key = key;
                this.shared = shared;
            }

            JettyClientSlices client() {
                return this.shared.client();
            }

            @Override
            public void close() {
                if (this.closed.compareAndSet(false, true)) {
                    this.owner.release(this.key, this.shared);
                }
            }
        }

        private static final class SharedClient {
            private final HttpClientSettingsKey key;
            private final JettyClientSlices client;
            private final CompletableFuture<Void> startFuture;
            private final AtomicInteger references = new AtomicInteger(0);
            private final AtomicBoolean metricsRegistered = new AtomicBoolean(false);
            /**
             * True once this client has been removed from the cache map by
             * {@link SharedJettyClients#invalidateAll()}. The ref-counted
             * lifecycle still applies — active leases keep using the client
             * until released — but the {@link SharedJettyClients#release}
             * path skips the map lookup and stops directly at refs==0.
             */
            private final AtomicBoolean evicted = new AtomicBoolean(false);

            SharedClient(final HttpClientSettingsKey key, final HttpTuning tuning) {
                this(key, tuning, false);
            }

            SharedClient(
                final HttpClientSettingsKey key,
                final HttpTuning tuning,
                final boolean useLegacyHttpClientCtor
            ) {
                this.key = key;
                if (useLegacyHttpClientCtor) {
                    // Legacy path (3-/4-arg RepositorySlices ctors): use the
                    // 1-arg JettyClientSlices ctor so the connection-pool cap
                    // continues to come from settings.maxConnectionsPerDestination()
                    // (the YAML override) rather than the supplier-provided
                    // HttpTuning.h2MaxPoolSize() (which falls back to 1 when
                    // no runtime cache is wired).
                    this.client = new JettyClientSlices(key.toSettings());
                } else {
                    this.client = new JettyClientSlices(
                        key.toSettings(),
                        mapProtocol(tuning.protocol()),
                        tuning.h2MaxPoolSize(),
                        tuning.h2MultiplexingLimit()
                    );
                }
                // Start the Jetty client on the dedicated resolve executor to avoid
                // blocking the Vert.x event loop. The start() call can take 100ms+
                // due to SSL context initialization and socket setup.
                this.startFuture = CompletableFuture.runAsync(
                    this.client::start, RESOLVE_EXECUTOR
                );
            }

            void retain() {
                this.references.incrementAndGet();
            }

            int release() {
                final int remaining = this.references.updateAndGet(current -> Math.max(0, current - 1));
                if (remaining == 0 && this.references.get() == 0) {
                    EcsLogger.debug("com.auto1.pantera")
                        .message(String.format("Jetty client reference count reached zero for settings key '%s'", this.key.metricId()))
                        .eventCategory("network")
                        .eventAction("client_release")
                        .log();
                }
                return remaining;
            }

            int referenceCount() {
                return this.references.get();
            }

            void markEvicted() {
                this.evicted.set(true);
            }

            boolean isEvicted() {
                return this.evicted.get();
            }

            JettyClientSlices client() {
                // Ensure the client has finished starting before returning it.
                // The actual start() runs on RESOLVE_EXECUTOR, not the calling thread.
                this.startFuture.join();
                return this.client;
            }

            void registerMetrics(final MeterRegistry registry) {
                if (!this.metricsRegistered.compareAndSet(false, true)) {
                    return;
                }
                Gauge.builder("jetty.connection_pool.active", this, SharedClient::activeConnections)
                    .strongReference(true)
                    .tag("settings", this.key.metricId())
                    .register(registry);
                Gauge.builder("jetty.connection_pool.idle", this, SharedClient::idleConnections)
                    .strongReference(true)
                    .tag("settings", this.key.metricId())
                    .register(registry);
                Gauge.builder("jetty.connection_pool.max", this, SharedClient::maxConnections)
                    .strongReference(true)
                    .tag("settings", this.key.metricId())
                    .register(registry);
                Gauge.builder("jetty.connection_pool.pending", this, SharedClient::pendingConnections)
                    .strongReference(true)
                    .tag("settings", this.key.metricId())
                    .register(registry);
            }

            private double activeConnections() {
                return this.connectionMetric(AbstractConnectionPool::getActiveConnectionCount);
            }

            private double idleConnections() {
                return this.connectionMetric(AbstractConnectionPool::getIdleConnectionCount);
            }

            private double maxConnections() {
                return this.connectionMetric(AbstractConnectionPool::getMaxConnectionCount);
            }

            private double pendingConnections() {
                return this.connectionMetric(AbstractConnectionPool::getPendingConnectionCount);
            }

            private double connectionMetric(final ToIntFunction<AbstractConnectionPool> extractor) {
                return this.client.httpClient().getDestinations().stream()
                    .map(Destination::getConnectionPool)
                    .filter(AbstractConnectionPool.class::isInstance)
                    .map(AbstractConnectionPool.class::cast)
                    .mapToInt(extractor)
                    .sum();
            }

            void stop() {
                // Wait for start to complete before stopping to avoid race conditions.
                this.startFuture.join();
                this.client.stop();
            }
        }
    }

    /**
     * Signature of HTTP client settings used as a cache key.
     */
    private static final class HttpClientSettingsKey {

        private final boolean trustAll;
        private final String jksPath;
        private final String jksPwd;
        private final boolean followRedirects;
        private final boolean http3;
        private final long connectTimeout;
        private final long idleTimeout;
        private final long proxyTimeout;
        private final long connectionAcquireTimeout;
        private final int maxConnectionsPerDestination;
        private final int maxRequestsQueuedPerDestination;
        private final List<ProxySettingsKey> proxies;

        private HttpClientSettingsKey(
            final boolean trustAll,
            final String jksPath,
            final String jksPwd,
            final boolean followRedirects,
            final boolean http3,
            final long connectTimeout,
            final long idleTimeout,
            final long proxyTimeout,
            final long connectionAcquireTimeout,
            final int maxConnectionsPerDestination,
            final int maxRequestsQueuedPerDestination,
            final List<ProxySettingsKey> proxies
        ) {
            this.trustAll = trustAll;
            this.jksPath = jksPath;
            this.jksPwd = jksPwd;
            this.followRedirects = followRedirects;
            this.http3 = http3;
            this.connectTimeout = connectTimeout;
            this.idleTimeout = idleTimeout;
            this.proxyTimeout = proxyTimeout;
            this.connectionAcquireTimeout = connectionAcquireTimeout;
            this.maxConnectionsPerDestination = maxConnectionsPerDestination;
            this.maxRequestsQueuedPerDestination = maxRequestsQueuedPerDestination;
            this.proxies = proxies;
        }

        static HttpClientSettingsKey from(final HttpClientSettings settings) {
            return new HttpClientSettingsKey(
                settings.trustAll(),
                settings.jksPath(),
                settings.jksPwd(),
                settings.followRedirects(),
                settings.http3(),
                settings.connectTimeout(),
                settings.idleTimeout(),
                settings.proxyTimeout(),
                settings.connectionAcquireTimeout(),
                settings.maxConnectionsPerDestination(),
                settings.maxRequestsQueuedPerDestination(),
                settings.proxies()
                    .stream()
                    .map(ProxySettingsKey::from)
                    .collect(Collectors.toUnmodifiableList())
            );
        }

        HttpClientSettings toSettings() {
            final HttpClientSettings copy = new HttpClientSettings()
                .setTrustAll(this.trustAll)
                .setFollowRedirects(this.followRedirects)
                .setHttp3(this.http3)
                .setConnectTimeout(this.connectTimeout)
                .setIdleTimeout(this.idleTimeout)
                .setProxyTimeout(this.proxyTimeout)
                .setConnectionAcquireTimeout(this.connectionAcquireTimeout)
                .setMaxConnectionsPerDestination(this.maxConnectionsPerDestination)
                .setMaxRequestsQueuedPerDestination(this.maxRequestsQueuedPerDestination);
            if (this.jksPath != null) {
                copy.setJksPath(this.jksPath);
            }
            if (this.jksPwd != null) {
                copy.setJksPwd(this.jksPwd);
            }
            final Set<String> seen = new HashSet<>();
            copy.proxies().forEach(proxy -> seen.add(proxy.uri().toString()));
            for (final ProxySettingsKey proxy : this.proxies) {
                if (seen.add(proxy.uri())) {
                    copy.addProxy(proxy.toProxySettings());
                }
            }
            return copy;
        }

        String metricId() {
            return Integer.toHexString(this.hashCode());
        }

        @Override
        public boolean equals(final Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof HttpClientSettingsKey other)) {
                return false;
            }
            return this.trustAll == other.trustAll
                && this.followRedirects == other.followRedirects
                && this.http3 == other.http3
                && this.connectTimeout == other.connectTimeout
                && this.idleTimeout == other.idleTimeout
                && this.proxyTimeout == other.proxyTimeout
                && this.connectionAcquireTimeout == other.connectionAcquireTimeout
                && this.maxConnectionsPerDestination == other.maxConnectionsPerDestination
                && this.maxRequestsQueuedPerDestination == other.maxRequestsQueuedPerDestination
                && Objects.equals(this.jksPath, other.jksPath)
                && Objects.equals(this.jksPwd, other.jksPwd)
                && Objects.equals(this.proxies, other.proxies);
        }

        @Override
        public int hashCode() {
            return Objects.hash(
                this.trustAll,
                this.jksPath,
                this.jksPwd,
                this.followRedirects,
                this.http3,
                this.connectTimeout,
                this.idleTimeout,
                this.proxyTimeout,
                this.connectionAcquireTimeout,
                this.maxConnectionsPerDestination,
                this.maxRequestsQueuedPerDestination,
                this.proxies
            );
        }
    }

    private record ProxySettingsKey(
        String uri,
        String realm,
        String user,
        String password
    ) {

        static ProxySettingsKey from(final ProxySettings proxy) {
            return new ProxySettingsKey(
                proxy.uri().toString(),
                proxy.basicRealm(),
                proxy.basicUser(),
                proxy.basicPwd()
            );
        }

        ProxySettings toProxySettings() {
            final ProxySettings proxy = new ProxySettings(URI.create(this.uri));
            if (!Strings.isNullOrEmpty(this.realm)) {
                proxy.setBasicRealm(this.realm);
                proxy.setBasicUser(this.user);
                proxy.setBasicPwd(this.password);
            }
            return proxy;
        }

        @Override
        public String toString() {
            return String.format("ProxySettingsKey{uri='%s'}", this.uri);
        }
    }
}
