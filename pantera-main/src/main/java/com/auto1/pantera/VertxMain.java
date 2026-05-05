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

import com.auto1.pantera.api.RepositoryEvents;
import com.auto1.pantera.api.v1.AsyncApiVerticle;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.auth.JwtTokens;
import com.auto1.pantera.cooldown.CooldownCleanupFallback;
import com.auto1.pantera.cooldown.CooldownRepository;
import com.auto1.pantera.cooldown.PgCronStatus;
import com.auto1.pantera.http.BaseSlice;
import com.auto1.pantera.http.MainSlice;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.misc.ConfigDefaults;
import com.auto1.pantera.http.misc.RepoNameMeterFilter;
import com.auto1.pantera.http.misc.StorageExecutors;
import com.auto1.pantera.http.slice.LoggingSlice;
import com.auto1.pantera.jetty.http3.Http3Server;
import com.auto1.pantera.jetty.http3.SslFactoryFromYaml;
import com.auto1.pantera.scheduling.QuartzService;
import com.auto1.pantera.scheduling.ScriptScheduler;
import com.auto1.pantera.settings.ConfigFile;
import com.auto1.pantera.settings.MetricsContext;
import com.auto1.pantera.settings.Settings;
import com.auto1.pantera.settings.SettingsFromPath;
import com.auto1.pantera.settings.repo.DbRepositories;
import com.auto1.pantera.settings.repo.MapRepositories;
import com.auto1.pantera.settings.repo.RepoConfig;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.settings.repo.Repositories;
import com.auto1.pantera.db.DbManager;
import com.auto1.pantera.db.migration.YamlToDbMigrator;
import com.auto1.pantera.vertx.VertxSliceServer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.jvm.ClassLoaderMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics;
import io.micrometer.core.instrument.binder.system.ProcessorMetrics;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.VertxOptions;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.micrometer.MicrometerMetricsOptions;
import io.vertx.micrometer.VertxPrometheusOptions;
import io.vertx.micrometer.backends.BackendRegistries;
import io.vertx.reactivex.core.Vertx;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Options;
import org.apache.commons.lang3.tuple.Pair;
import com.auto1.pantera.diagnostics.BlockedThreadDiagnostics;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Vertx server entry point.
 * @since 1.0
 */
@SuppressWarnings("PMD.PrematureDeclaration")
public final class VertxMain {

    /**
     * Default port to start Pantera Rest API service.
     */
    private static final String DEF_API_PORT = "8086";

    /**
     * Config file path.
     */
    private final Path config;

    /**
     * Server port.
     */
    private final int port;

    /**
     * Servers.
     */
    private final List<VertxSliceServer> servers;
    private QuartzService quartz;

    /**
     * Settings instance - must be closed on shutdown.
     */
    private Settings settings;

    /**
     * Port and http3 server.
     */
    private final Map<Integer, Http3Server> http3;

    /**
     * Config watch service for hot reload.
     */
    private com.auto1.pantera.settings.ConfigWatchService configWatch;

    /**
     * Vertx-periodic cooldown cleanup fallback. Only started when pg_cron
     * is absent or its cleanup job is not scheduled. Nullable.
     */
    private CooldownCleanupFallback cooldownCleanupFallback;

    /**
     * Vert.x instance - must be closed on shutdown to release event loops and worker threads.
     */
    private Vertx vertx;

    /**
     * Runtime settings cache (LISTEN/NOTIFY-driven snapshot of pantera_settings).
     * Null when no DataSource is configured (DB-less boot, tests).
     */
    private com.auto1.pantera.settings.runtime.RuntimeSettingsCache settingsCache;

    /**
     * Prefetch coordinator (Phase 4 / Task 17). Null until
     * {@link #installPrefetch} succeeds; held here so {@link #stop()} can
     * shut down the worker pool cleanly between
     * {@link com.auto1.pantera.settings.runtime.RuntimeSettingsCache#stop()}
     * and {@code DataSource} shutdown.
     */
    private com.auto1.pantera.prefetch.PrefetchCoordinator prefetchCoordinator;

    /**
     * Prefetch metrics (Phase 4 / Task 15). Null until
     * {@link #installPrefetch} succeeds; passed into
     * {@link com.auto1.pantera.api.v1.AsyncApiVerticle} so the
     * {@link com.auto1.pantera.api.v1.PrefetchStatsHandler} (Task 22)
     * can read 24h sliding-window counts.
     *
     * @since 2.2.0
     */
    private com.auto1.pantera.prefetch.PrefetchMetrics prefetchMetrics;

    /**
     * Ctor.
     *
     * @param config Config file path.
     * @param port HTTP port
     */
    public VertxMain(final Path config, final int port) {
        this.config = config;
        this.port = port;
        this.servers = new ArrayList<>(0);
        this.http3 = new ConcurrentHashMap<>(0);
    }

    /**
     * Starts the server.
     *
     * @param apiPort Port to run Rest API service on
     * @return Port the servers listening on.
     * @throws IOException In case of error reading settings.
     */
    public int start(final int apiPort) throws IOException {
        org.slf4j.MDC.put(
            com.auto1.pantera.http.log.EcsMdc.TRACE_ID,
            com.auto1.pantera.http.trace.SpanContext.generateHex16()
        );
        org.slf4j.MDC.put(
            com.auto1.pantera.http.log.EcsMdc.SPAN_ID,
            com.auto1.pantera.http.trace.SpanContext.generateHex16()
        );
        // Pre-parse YAML to detect DB configuration for Quartz JDBC clustering
        final com.amihaiemil.eoyaml.YamlMapping yamlContent =
            com.amihaiemil.eoyaml.Yaml.createYamlInput(this.config.toFile()).readYamlMapping();
        final com.amihaiemil.eoyaml.YamlMapping meta = yamlContent.yamlMapping("meta");
        final Optional<javax.sql.DataSource> sharedDs;
        final Optional<javax.sql.DataSource> writeDs;
        if (meta != null && meta.yamlMapping("artifacts_database") != null) {
            final com.auto1.pantera.db.ArtifactDbFactory dbFactory =
                new com.auto1.pantera.db.ArtifactDbFactory(meta, "artifacts");
            final javax.sql.DataSource ds = dbFactory.initialize();
            sharedDs = Optional.of(ds);
            writeDs = Optional.of(dbFactory.initializeWritePool());
            DbManager.migrate(ds);
            // Resolve repos and security dirs from YAML config, not relative to config file.
            // pantera.yml may be mounted at /etc/pantera/ while data lives at /var/pantera/.
            final Path configDir = this.config.toAbsolutePath().getParent();
            final com.amihaiemil.eoyaml.YamlMapping storageYaml = meta.yamlMapping("storage");
            final Path reposDir = storageYaml != null && storageYaml.string("path") != null
                ? Path.of(storageYaml.string("path"))
                : configDir.resolve("repo");
            final com.amihaiemil.eoyaml.YamlMapping policyYaml = meta.yamlMapping("policy");
            final com.amihaiemil.eoyaml.YamlMapping policyStorage =
                policyYaml != null ? policyYaml.yamlMapping("storage") : null;
            final Path securityDir = policyStorage != null && policyStorage.string("path") != null
                ? Path.of(policyStorage.string("path"))
                : configDir.resolve("security");
            new YamlToDbMigrator(
                ds, securityDir, reposDir, this.config.toAbsolutePath()
            ).migrate();
            // Runtime-tunable settings: seed defaults on first boot, then start
            // the LISTEN/NOTIFY-backed cache so PATCHes via the Settings API
            // propagate to all consumers without a server restart. Construction
            // happens here (before any verticle deploys or slice warmup) so
            // future subscribers (Phase 2 HTTP/2 client, Phase 4 prefetch) can
            // reliably resolve the singleton via the boot wiring.
            final com.auto1.pantera.db.dao.SettingsDao settingsDao =
                new com.auto1.pantera.db.dao.SettingsDao(ds);
            new com.auto1.pantera.settings.runtime.SettingsBootstrap(settingsDao)
                .seedIfMissing();
            // NOTE: PgListenNotify holds one Hikari connection indefinitely (the LISTEN
            // connection has to stay open). With pool leak detection enabled
            // (PANTERA_DB_LEAK_DETECTION_MS=5000 by default), Hikari logs a WARN every
            // 5s for this connection. The warning is benign — the listener is doing
            // exactly what it's designed to do — but it's noisy in dev logs.
            // TODO(perf-pack): give PgListenNotify a dedicated DriverManager.getConnection
            // (bypass the pool entirely) to silence the warning. Tracked separately.
            this.settingsCache =
                new com.auto1.pantera.settings.runtime.RuntimeSettingsCache(
                    settingsDao, ds
                );
            this.settingsCache.start();
            quartz = new QuartzService(ds);
            EcsLogger.info("com.auto1.pantera")
                .message("Quartz JDBC clustering enabled with shared DataSource")
                .eventCategory("process")
                .eventAction("quartz_jdbc_init")
                .eventOutcome("success")
                .log();
        } else {
            sharedDs = Optional.empty();
            writeDs = Optional.empty();
            quartz = new QuartzService();
        }
        this.settings = new SettingsFromPath(this.config).find(quartz, sharedDs, writeDs);
        // Apply logging configuration from YAML settings
        if (settings.logging().configured()) {
            settings.logging().apply();
            EcsLogger.info("com.auto1.pantera")
                .message("Applied logging configuration from YAML settings")
                .eventCategory("configuration")
                .eventAction("logging_configure")
                .eventOutcome("success")
                .log();
        }



        this.vertx = VertxMain.vertx(settings.metrics());
        final com.auto1.pantera.settings.JwtSettings jwtSettings = settings.jwtSettings();
        final com.auto1.pantera.auth.RsaKeyLoader rsaKeys =
            new com.auto1.pantera.auth.RsaKeyLoader(
                jwtSettings.privateKeyPath().orElseThrow(
                    () -> new IllegalStateException(
                        "JWT private key path not configured. Set meta.jwt.private-key-path."
                    )
                ),
                jwtSettings.publicKeyPath().orElseThrow(
                    () -> new IllegalStateException(
                        "JWT public key path not configured. Set meta.jwt.public-key-path."
                    )
                )
            );
        final Repositories repos;
        if (sharedDs.isPresent()) {
            repos = new DbRepositories(
                sharedDs.get(),
                settings.caches().storagesCache(),
                settings.metrics().storage()
            );
        } else {
            repos = new MapRepositories(settings);
        }
        final com.auto1.pantera.db.dao.UserTokenDao userTokenDao = sharedDs
            .map(com.auto1.pantera.db.dao.UserTokenDao::new)
            .orElse(null);
        // Per-request enabled-state gate for the JWT filter. When the
        // security Policy is a CachedDbPolicy (i.e. DB-backed), delegate
        // to its cached isEnabled() method — any user disabled via the
        // admin UI has their sessions and API tokens rejected on the
        // next request, even if the token itself hasn't expired. In
        // non-DB modes we fall back to the always-enabled default.
        final com.auto1.pantera.auth.UserEnabledCheck enabledCheck;
        if (settings.authz().policy()
            instanceof com.auto1.pantera.security.policy.CachedDbPolicy cachedPolicy) {
            enabledCheck = cachedPolicy::isEnabled;
        } else {
            enabledCheck = com.auto1.pantera.auth.UserEnabledCheck.ALWAYS_ENABLED;
        }
        final com.auto1.pantera.auth.JwtTokens jwtTokens = new com.auto1.pantera.auth.JwtTokens(
            rsaKeys.privateKey(), rsaKeys.publicKey(), userTokenDao, null, null, enabledCheck
        );
        // Install the circuit-breaker settings loader BEFORE constructing
        // RepositorySlices so the default-constructor activeSupplier()
        // picks up the DB-backed loader rather than pure hardcoded
        // defaults. When no DataSource is present (tests, DB-less boot)
        // RepositorySlices falls back to AutoBlockSettings::defaults
        // automatically via activeSupplier().
        sharedDs.ifPresent(ds ->
            com.auto1.pantera.circuit.CircuitBreakerSettingsLoader.install(
                new com.auto1.pantera.db.dao.AuthSettingsDao(ds)
            )
        );
        // Install singleton PublishDateRegistry. Each adapter slice now resolves
        // canonical publish dates via RegistryBackedInspector(repoType, registry)
        // instead of HEAD-probing upstream — eliminates the per-cooldown-eval
        // round-trip and gives us a single L1+L2 cache shared across adapters.
        sharedDs.ifPresent(ds -> {
            // Publish-date WebClient: pool sizing comes from the same
            // {@code http_client} block in pantera.yml that the proxy clients
            // use, so operators have a single tuning knob. Default Vert.x
            // maxPoolSize of 5 is far too small under `go get` fanout — a
            // hundreds-of-deps resolution times out at the connection layer
            // ("timeout getting a connection") long before any HTTP body
            // arrives. We also negotiate HTTP/2 via ALPN so a few
            // connections can multiplex many concurrent streams.
            //
            // Concurrency is scaled to a quarter of the proxy-traffic budget
            // because publish-date lookups all hit the SAME upstream host
            // (e.g. proxy.golang.org). Slamming a single registry with the
            // full proxy quota triggers per-IP rate-limits / 429s and blocks.
            // 25% keeps us well below the typical anonymous-bot thresholds.
            final com.auto1.pantera.http.client.HttpClientSettings httpClientSettings =
                this.settings.httpClientSettings();
            final double publishDateConcurrencyFactor = 0.25;
            final int maxPool = Math.max(32, (int) Math.round(
                httpClientSettings.maxConnectionsPerDestination()
                    * publishDateConcurrencyFactor));
            final int idleSec = Math.max(30,
                (int) (httpClientSettings.idleTimeout() / 1_000L));
            final int connectMs = Math.max(1_000,
                (int) httpClientSettings.connectTimeout());
            final io.vertx.ext.web.client.WebClient publishDateClient =
                io.vertx.ext.web.client.WebClient.create(
                    this.vertx.getDelegate(),
                    new io.vertx.ext.web.client.WebClientOptions()
                        // Default UA only matters if a source forgets to set
                        // a per-request one — every PublishDateSource above
                        // overrides this with a native ecosystem UA so
                        // upstream registries see e.g. "Go-http-client/1.1"
                        // or "npm/10.5..." rather than a Pantera-branded
                        // identity that gets per-UA-rate-limited at scale.
                        .setUserAgent(com.auto1.pantera.http.EcosystemUserAgents.GO)
                        .setConnectTimeout(connectMs)
                        .setIdleTimeout(idleSec)
                        .setKeepAlive(true)
                        .setMaxPoolSize(maxPool)
                        .setHttp2MaxPoolSize(Math.max(4, maxPool / 8))
                        .setHttp2MultiplexingLimit(100)
                        .setProtocolVersion(io.vertx.core.http.HttpVersion.HTTP_2)
                        .setUseAlpn(true)
                );
            final com.auto1.pantera.publishdate.sources.MavenHeadSource mavenHead =
                new com.auto1.pantera.publishdate.sources.MavenHeadSource(publishDateClient);
            final com.auto1.pantera.publishdate.sources.JFrogStorageApiSource jfrogFallback =
                new com.auto1.pantera.publishdate.sources.JFrogStorageApiSource(
                    publishDateClient,
                    "https://groovy.jfrog.io/artifactory",
                    "plugins-release"
                );
            final com.auto1.pantera.publishdate.PublishDateSource mavenSource =
                new com.auto1.pantera.publishdate.sources.ChainedPublishDateSource(
                    mavenHead, jfrogFallback
                );
            final com.auto1.pantera.publishdate.DbPublishDateRegistry publishDates =
                new com.auto1.pantera.publishdate.DbPublishDateRegistry(
                    ds,
                    Map.of(
                        "maven", mavenSource,
                        "gradle", mavenSource,
                        "npm",
                        new com.auto1.pantera.publishdate.sources.NpmRegistrySource(publishDateClient),
                        "pypi",
                        new com.auto1.pantera.publishdate.sources.PyPiSource(publishDateClient),
                        "go",
                        new com.auto1.pantera.publishdate.sources.GoProxySource(publishDateClient),
                        "composer",
                        new com.auto1.pantera.publishdate.sources.PackagistSource(publishDateClient),
                        "gem",
                        new com.auto1.pantera.publishdate.sources.RubyGemsSource(publishDateClient)
                    )
                );
            com.auto1.pantera.publishdate.PublishDateRegistries.installDefault(publishDates);
        });
        // Wire RepositorySlices with the runtime HTTP tuning supplier so
        // every new SharedClient picks up the latest http_client.* values.
        // When no DB-backed cache is configured (legacy YAML-only boot),
        // fall back to HttpTuning.defaults() so behaviour matches v2.1.
        final java.util.function.Supplier<
            com.auto1.pantera.settings.runtime.HttpTuning
        > httpTuningSupplier = this.settingsCache != null
            ? this.settingsCache::httpTuning
            : com.auto1.pantera.settings.runtime.HttpTuning::defaults;
        final RepositorySlices slices = new RepositorySlices(
            settings, repos, jwtTokens,
            com.auto1.pantera.circuit.CircuitBreakerSettingsLoader.activeSupplier(),
            httpTuningSupplier
        );
        // Hot-reload: any http_client.* settings change drops every cached
        // upstream Jetty client so the next acquire rebuilds with the new
        // protocol / pool size / multiplexing limit. Active leases keep
        // their existing client until release; this is the v2.2 mechanism
        // that RuntimeSettingsCache was designed to enable.
        if (this.settingsCache != null) {
            this.settingsCache.addListener("http_client.", changedKey -> {
                EcsLogger.info("com.auto1.pantera")
                    .message("http_client.* setting changed; invalidating upstream client pool")
                    .eventCategory("configuration")
                    .eventAction("http_client_settings_change")
                    .field("settings.key", changedKey)
                    .log();
                slices.invalidateUpstreamClients();
            });
        }
        // Phase 4 / Task 19b+c — Prefetch subsystem boot wiring.
        //
        // Construct PrefetchMetrics → PrefetchCircuitBreaker → PrefetchCoordinator
        // → PrefetchDispatcher and register the dispatcher's onCacheWrite hook
        // via CacheWriteCallbackRegistry so every BaseCachedProxySlice cache
        // write fires the prefetch path. Subscribes the coordinator to
        // prefetch.* settings so admins can hot-tune queue capacity, worker
        // threads, and the kill-switch without a restart.
        //
        // Cooldown gate: STUBBED to "always allow" for now. Constructing the
        // real CooldownInspector per ecosystem from VertxMain is non-trivial
        // (the inspectors live inside the per-repo adapter constructors).
        // Foreground requests still hit the real cooldown gate when the
        // artifact is requested, so blocking semantics are preserved at the
        // user-visible layer — but prefetch may waste upstream bandwidth on
        // artifacts the cooldown will subsequently block. Phase 6 / Task 25.B
        // will replace this with the proper RepositorySlices-routed adapter.
        if (this.settingsCache != null) {
            this.installPrefetch(slices, jwtTokens);
        } else {
            EcsLogger.info("com.auto1.pantera.prefetch")
                .message("Skipping prefetch wiring — no RuntimeSettingsCache (DB-less boot)")
                .eventCategory("configuration")
                .eventAction("prefetch_init_skip")
                .log();
        }
        // Cooldown cleanup fallback: if pg_cron is not running the
        // cleanup job, start the Vertx-periodic fallback so expired
        // blocks still get archived and history still gets purged.
        // Requires a DataSource (no DB => no cooldown => no cleanup).
        // RepositorySlices construction above has already called
        // CooldownSupport.create(settings), which runs
        // loadDbCooldownSettings — so settings.cooldown() now reflects
        // the DB-persisted retention/batch values the fallback reads.
        if (sharedDs.isPresent()) {
            final javax.sql.DataSource ds = sharedDs.get();
            final PgCronStatus pgCron = new PgCronStatus(ds);
            if (!pgCron.cleanupJobScheduled()) {
                this.cooldownCleanupFallback = new CooldownCleanupFallback(
                    new CooldownRepository(ds), settings.cooldown()
                );
                this.cooldownCleanupFallback.start(this.vertx.getDelegate());
            } else {
                EcsLogger.info("com.auto1.pantera.cooldown.cleanup")
                    .message("pg_cron cleanup job is scheduled; skipping Vertx fallback")
                    .eventCategory("configuration")
                    .eventAction("cooldown_fallback_skip")
                    .eventOutcome("success")
                    .log();
            }
        }
        if (settings.metrics().http()) {
            try {
                slices.enableJettyMetrics(BackendRegistries.getDefaultNow());
            } catch (final IllegalStateException ex) {
                EcsLogger.warn("com.auto1.pantera")
                    .message("HTTP metrics enabled but MeterRegistry unavailable")
                    .eventCategory("configuration")
                    .eventAction("metrics_configure")
                    .eventOutcome("failure")
                    .error(ex)
                    .log();
            }
        }
        // Register HikariCP metrics now that Vert.x/Micrometer is initialized
        if (sharedDs.isPresent()) {
            com.auto1.pantera.db.ArtifactDbFactory.enableMetrics(
                sharedDs.get(), BackendRegistries.getDefaultNow()
            );
        }
        // Listen for repository change events to refresh runtime without restart
        this.vertx.getDelegate().eventBus().consumer(
            RepositoryEvents.ADDRESS,
            msg -> {
                try {
                    final String body = String.valueOf(msg.body());
                    final String[] parts = body.split("\\|");
                    if (parts.length >= 2) {
                        final String action = parts[0];
                        final String name = parts[1];
                        if (RepositoryEvents.UPSERT.equals(action)) {
                            repos.refreshAsync().whenComplete(
                                (ignored, err) -> {
                                    if (err != null) {
                                        EcsLogger.error("com.auto1.pantera")
                                            .message("Failed to refresh repositories after UPSERT event")
                                            .eventCategory("web")
                                            .eventAction("event_process")
                                            .eventOutcome("failure")
                                            .error(err)
                                            .log();
                                        return;
                                    }
                                    VertxMain.this.vertx.getDelegate().runOnContext(
                                        nothing -> {
                                            slices.invalidateRepo(name);
                                            repos.config(name).ifPresent(cfg -> cfg.port().ifPresent(
                                                prt -> {
                                                    final Slice slice = slices.slice(new Key.From(name), prt);
                                                    if (cfg.startOnHttp3()) {
                                                        this.http3.computeIfAbsent(
                                                            prt, key -> {
                                                                final Http3Server server = new Http3Server(
                                                                    new LoggingSlice(slice), prt,
                                                                    new SslFactoryFromYaml(cfg.repoYaml()).build()
                                                                );
                                                                server.start();
                                                                return server;
                                                            }
                                                        );
                                                    } else {
                                                        final boolean exists = this.servers
                                                            .stream()
                                                            .anyMatch(s -> s.port() == prt);
                                                        if (!exists) {
                                                            this.listenOn(
                                                                slice,
                                                                prt,
                                                                VertxMain.this.vertx,
                                                                settings.metrics(),
                                                                settings.httpServerRequestTimeout()
                                                            );
                                                        }
                                                    }
                                                }
                                            ));
                                        }
                                    );
                                }
                            );
                        } else if (RepositoryEvents.REMOVE.equals(action)) {
                            repos.refreshAsync().whenComplete(
                                (ignored, err) -> {
                                    if (err != null) {
                                        EcsLogger.error("com.auto1.pantera")
                                            .message("Failed to refresh repositories after REMOVE event")
                                            .eventCategory("web")
                                            .eventAction("event_process")
                                            .eventOutcome("failure")
                                            .error(err)
                                            .log();
                                        return;
                                    }
                                    VertxMain.this.vertx.getDelegate().runOnContext(
                                        nothing -> slices.invalidateRepo(name)
                                    );
                                }
                            );
                        } else if (RepositoryEvents.MOVE.equals(action) && parts.length >= 3) {
                            final String target = parts[2];
                            repos.refreshAsync().whenComplete(
                                (ignored, err) -> {
                                    if (err != null) {
                                        EcsLogger.error("com.auto1.pantera")
                                            .message("Failed to refresh repositories after MOVE event")
                                            .eventCategory("web")
                                            .eventAction("event_process")
                                            .eventOutcome("failure")
                                            .error(err)
                                            .log();
                                        return;
                                    }
                                    VertxMain.this.vertx.getDelegate().runOnContext(
                                        nothing -> {
                                            slices.invalidateRepo(name);
                                            slices.invalidateRepo(target);
                                        }
                                    );
                                }
                            );
                        }
                    }
                } catch (final Throwable err) {
                    EcsLogger.error("com.auto1.pantera")
                        .message("Failed to process repository event")
                        .eventCategory("web")
                        .eventAction("event_process")
                        .eventOutcome("failure")
                        .error(err)
                        .log();
                }
            }
        );
        // Warm up all configured repository slices before accepting traffic, so
        // the first request per repo does not block its Vert.x event-loop thread
        // on SharedClient.startFuture.join() during Jetty client initialisation.
        slices.warmUp(java.time.Duration.ofSeconds(30));
        final int main = this.listenOn(
            new MainSlice(settings, slices),
            this.port,
            this.vertx,
            settings.metrics(),
            settings.httpServerRequestTimeout(),
            settings.proxyProtocol()
        );
        EcsLogger.info("com.auto1.pantera")
            .message("Pantera was started on port")
            .eventCategory("web")
            .eventAction("server_start")
            .eventOutcome("success")
            .field("url.port", main)
            .log();
        this.startRepos(this.vertx, settings, repos, this.port, slices);
        
        // Deploy AsyncApiVerticle with multiple instances for CPU scaling
        // Use 2x CPU cores to handle concurrent API requests efficiently
        final int apiInstances = Runtime.getRuntime().availableProcessors() * 2;
        final DeploymentOptions deployOpts = new DeploymentOptions()
            .setInstances(apiInstances);
        this.vertx.deployVerticle(
            () -> new AsyncApiVerticle(
                settings, apiPort, null, sharedDs.orElse(null), jwtTokens,
                this.prefetchMetrics
            ),
            deployOpts,
            result -> {
                if (result.succeeded()) {
                    EcsLogger.info("com.auto1.pantera.api")
                        .message("AsyncApiVerticle deployed with " + apiInstances + " instances")
                        .eventCategory("api")
                        .eventAction("api_deploy")
                        .eventOutcome("success")
                        .log();
                } else {
                    EcsLogger.error("com.auto1.pantera.api")
                        .message("Failed to deploy AsyncApiVerticle")
                        .eventCategory("api")
                        .eventAction("api_deploy")
                        .eventOutcome("failure")
                        .error(result.cause())
                        .log();
                }
            }
        );

        quartz.start();
        new ScriptScheduler(quartz).loadCrontab(settings, repos);

        // JIT warmup: fire lightweight requests through group code paths so the
        // first real client request doesn't pay ~140ms JIT compilation penalty.
        // Runs on a daemon thread to avoid blocking startup.
        final int warmupPort = main;
        final Thread warmupThread = new Thread(() -> {
            try {
                Thread.sleep(2000); // wait for server to fully bind
                final java.net.http.HttpClient hc = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(3)).build();
                // Hit each group repo once to JIT-compile GroupResolver + index lookup
                for (final com.auto1.pantera.settings.repo.RepoConfig cfg : repos.configs()) {
                    if (cfg.type().endsWith("-group")) {
                        try {
                            final java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                                .uri(java.net.URI.create(
                                    String.format("http://localhost:%d/%s/", warmupPort, cfg.name())))
                                .timeout(java.time.Duration.ofSeconds(5))
                                .GET().build();
                            hc.send(req, java.net.http.HttpResponse.BodyHandlers.discarding());
                        } catch (final Exception ignored) {
                            // warmup failure is non-fatal
                        }
                    }
                }
                EcsLogger.info("com.auto1.pantera")
                    .message("JIT warmup complete for group repositories")
                    .eventCategory("web")
                    .eventAction("jit_warmup")
                    .eventOutcome("success")
                    .log();
            } catch (final Exception ignored) {
                // warmup failure is non-fatal
            }
        }, "pantera-jit-warmup");
        warmupThread.setDaemon(true);
        warmupThread.start();

        // Deploy AsyncMetricsVerticle as worker verticle to handle Prometheus scraping off event loop
        // This prevents the blocking issue where scrape() takes 2-10s and stalls all HTTP requests
        // See: docs/PERFORMANCE_ISSUES_ANALYSIS.md Issue #1
        if (settings.metrics().enabled()) {
            final Optional<Pair<String, Integer>> metricsEndpoint = settings.metrics().endpointAndPort();
            if (metricsEndpoint.isPresent()) {
                final int metricsPort = metricsEndpoint.get().getValue();
                final String metricsPath = metricsEndpoint.get().getKey();
                final long metricsCacheTtlMs = 10_000L; // 10 second cache TTL as requested
                final MeterRegistry metricsRegistry = BackendRegistries.getDefaultNow();
                StorageExecutors.registerMetrics(metricsRegistry);
                settings.artifactMetadata().ifPresent(
                    evtQueues -> io.micrometer.core.instrument.Gauge.builder(
                        "pantera.events.queue.size",
                        evtQueues.eventQueue(),
                        java.util.Queue::size
                    ).tag("type", "events")
                        .description("Size of the artifact events queue")
                        .register(metricsRegistry)
                );

                final DeploymentOptions metricsOpts = new DeploymentOptions()
                    .setWorker(true)
                    .setWorkerPoolName("metrics-scraper")
                    .setWorkerPoolSize(2);
                
                this.vertx.deployVerticle(
                    () -> new com.auto1.pantera.metrics.AsyncMetricsVerticle(
                        metricsRegistry, metricsPort, metricsPath, metricsCacheTtlMs
                    ),
                    metricsOpts,
                    metricsResult -> {
                        if (metricsResult.succeeded()) {
                            EcsLogger.info("com.auto1.pantera.metrics")
                                .message(String.format("AsyncMetricsVerticle deployed as worker verticle with cache TTL %dms", metricsCacheTtlMs))
                                .eventCategory("process")
                                .eventAction("metrics_verticle_deploy")
                                .eventOutcome("success")
                                .field("destination.port", metricsPort)
                                .field("url.path", metricsPath)
                                .log();
                        } else {
                            EcsLogger.error("com.auto1.pantera.metrics")
                                .message("Failed to deploy AsyncMetricsVerticle")
                                .eventCategory("process")
                                .eventAction("metrics_verticle_deploy")
                                .eventOutcome("failure")
                                .error(metricsResult.cause())
                                .log();
                        }
                    }
                );
            }
        }

        // Start config watch service for hot reload
        try {
            this.configWatch = new com.auto1.pantera.settings.ConfigWatchService(
                this.config, settings.prefixes()
            );
            this.configWatch.start();
            EcsLogger.info("com.auto1.pantera")
                .message("Config watch service started for hot reload")
                .eventCategory("configuration")
                .eventAction("config_watch_start")
                .eventOutcome("success")
                .log();
        } catch (final IOException ex) {
            EcsLogger.error("com.auto1.pantera")
                .message("Failed to start config watch service")
                .eventCategory("configuration")
                .eventAction("config_watch_start")
                .eventOutcome("failure")
                .error(ex)
                .log();
        }
        
        return main;
    }

    /**
     * Wire up the Phase 4 prefetch subsystem against an already-constructed
     * {@link RepositorySlices}. Builds the metrics/breaker/coordinator/
     * dispatcher chain, registers the dispatcher's
     * {@code onCacheWrite} callback into
     * {@link com.auto1.pantera.http.cache.CacheWriteCallbackRegistry} so every
     * {@link com.auto1.pantera.http.cache.BaseCachedProxySlice} adapter
     * picks it up automatically, and subscribes the coordinator to live
     * settings updates.
     *
     * <p>Cooldown gate is currently stubbed to "always allow" — see
     * Phase 6 / Task 25.B follow-up. Foreground requests still hit the
     * real cooldown service on the user-visible request path.</p>
     *
     * @param slices Slices registry whose accessors back the dispatcher.
     */
    @SuppressWarnings({"PMD.AvoidCatchingGenericException", "PMD.CognitiveComplexity"})
    private void installPrefetch(
        final RepositorySlices slices,
        final com.auto1.pantera.auth.JwtTokens jwtTokens
    ) {
        try {
            final java.time.Clock clock = java.time.Clock.systemUTC();
            final com.auto1.pantera.prefetch.PrefetchMetrics metrics =
                new com.auto1.pantera.prefetch.PrefetchMetrics(clock);
            // Lift onto the VertxMain field so the PrefetchStatsHandler
            // wired into AsyncApiVerticle can read this same instance
            // (Task 22). The verticle is deployed AFTER this method
            // returns, so the field is set before the deploy reads it.
            this.prefetchMetrics = metrics;
            final com.auto1.pantera.prefetch.PrefetchCircuitBreaker breaker =
                new com.auto1.pantera.prefetch.PrefetchCircuitBreaker(
                    this.settingsCache::circuitBreakerTuning
                );
            // Cooldown gate: stub allow-all (see CONCERN-task19-stub-cooldown-gate
            // in the audit doc). Real wiring is deferred to v2.3 — wiring the
            // per-ecosystem CooldownInspector through RepositorySlices needs a
            // dedicated factory the adapters do not yet expose. Until that lands,
            // prefetch issues upstream GETs even for cooldown-blocked artifacts;
            // foreground requests still hit the real cooldown gate so user-
            // visible blocking is preserved. Emit a one-shot operator-visible
            // WARN at boot so this is not invisible in production.
            final com.auto1.pantera.prefetch.PrefetchCoordinator.CooldownGate cooldownGate =
                task -> java.util.concurrent.CompletableFuture.completedFuture(Boolean.FALSE);
            EcsLogger.warn("com.auto1.pantera.prefetch")
                .message(
                    "Prefetch cooldown gate is the stubbed allow-all instance — "
                        + "prefetch may issue upstream GETs for cooldown-blocked "
                        + "artifacts. Foreground requests still honour cooldown. "
                        + "Real per-ecosystem inspector wiring is deferred to v2.3."
                )
                .eventCategory("configuration")
                .eventAction("prefetch_init")
                .eventOutcome("success")
                .field("cooldown.gate", "stub-allow-all")
                .log();
            // Upstream caller: invoke the resolved repository slice directly
            // and stamp a system-level Authorization header so the outer
            // auth wrapper (CombinedAuthzSliceWrap) lets the prefetch through.
            //
            // Why direct slice invocation: going through the JVM-resolved
            // slice — rather than the loopback HTTP endpoint — keeps the
            // call inside the same Vert.x context as the foreground request
            // that triggered it, avoids socket-layer overhead, and still
            // routes the response through the normal BaseCachedProxySlice /
            // ProxyCacheWriter pipeline so the cache-write completes the
            // same way a foreground request would have populated it.
            //
            // Why a system Authorization header: the slice chain wraps the
            // inner adapter in CombinedAuthzSliceWrap which rejects
            // anonymous requests with 401. We mint a one-shot service JWT
            // for the "pantera" system user (created during DB bootstrap
            // with admin role) so prefetch passes the auth gate without
            // hardcoding admin credentials. TODO(Phase 6 / Task 25.B):
            // replace this with a dedicated prefetch-internal slice
            // accessor that bypasses the outer auth wrapper entirely.
            final int loopbackPort = this.port;
            // Re-mint the system JWT per upstream call. The token has a 1h
            // TTL (defaultAccessTtl=3600s); capturing it once at boot would
            // silently 401 every prefetch after the first hour. JWT minting
            // is a single HMAC — cheap enough to do per call without a
            // cache. See review-v2.2.0 C1.
            final com.auto1.pantera.prefetch.PrefetchCoordinator.UpstreamCaller upstream =
                task -> {
                    final java.util.concurrent.CompletableFuture<Integer> out =
                        new java.util.concurrent.CompletableFuture<>();
                    try {
                        final com.auto1.pantera.http.Slice repoSlice = slices.slice(
                            new com.auto1.pantera.asto.Key.From(task.repoName()),
                            loopbackPort
                        );
                        // Match the public URL format the foreground HTTP server
                        // would route through TrimPathSlice / Browsable wrappers:
                        // "/<repoName>/<artifact path>". The slice() return is
                        // the same TrimPathSlice the public server hits, so the
                        // leading "/<repoName>" prefix is stripped before the
                        // inner MavenProxy sees the artifact path.
                        final String urlPath = "/" + task.repoName() + "/" + task.coord().path();
                        final com.auto1.pantera.http.rq.RequestLine line =
                            new com.auto1.pantera.http.rq.RequestLine(
                                com.auto1.pantera.http.rq.RqMethod.GET, urlPath
                            );
                        final com.auto1.pantera.http.Headers headers =
                            new com.auto1.pantera.http.Headers();
                        final String systemAuth = "Bearer "
                            + jwtTokens.generate(
                                new com.auto1.pantera.http.auth.AuthUser(
                                    "pantera", "internal-prefetch"
                                )
                            );
                        headers.add("Authorization", systemAuth);
                        repoSlice.response(line, headers, com.auto1.pantera.asto.Content.EMPTY)
                            .whenComplete((response, err) -> {
                                if (err != null) {
                                    out.completeExceptionally(err);
                                } else if (response == null) {
                                    out.completeExceptionally(
                                        new IllegalStateException("null response")
                                    );
                                } else {
                                    out.complete(response.status().code());
                                }
                            });
                    } catch (final Exception ex) {
                        out.completeExceptionally(ex);
                    }
                    return out;
                };
            this.prefetchCoordinator = new com.auto1.pantera.prefetch.PrefetchCoordinator(
                metrics,
                breaker,
                slices.negativeCache(),
                cooldownGate,
                upstream,
                this.settingsCache::prefetchTuning
            );
            this.prefetchCoordinator.start();
            this.prefetchCoordinator.subscribe(this.settingsCache);
            // Parser registry: maven + gradle share MavenPomParser; npm uses
            // NpmPackageParser fronted by CachedNpmMetadataLookup, which
            // resolves version ranges against locally-cached packuments
            // (NEVER initiates an upstream metadata fetch — pre-fetch must
            // stay strictly local). The lookup snapshots the npm-proxy
            // storages on every call so live config reloads (a new
            // npm-proxy added at runtime) are picked up automatically.
            final com.auto1.pantera.prefetch.parser.NpmMetadataLookup npmLookup =
                new com.auto1.pantera.prefetch.parser.CachedNpmMetadataLookup(
                    slices::npmProxyStorages
                );
            final java.util.Map<String,
                com.auto1.pantera.prefetch.parser.PrefetchParser> parsers = java.util.Map.of(
                "maven-proxy", new com.auto1.pantera.prefetch.parser.MavenPomParser(),
                "gradle-proxy", new com.auto1.pantera.prefetch.parser.MavenPomParser(),
                "npm-proxy", new com.auto1.pantera.prefetch.parser.NpmPackageParser(npmLookup)
            );
            final com.auto1.pantera.prefetch.PrefetchDispatcher dispatcher =
                new com.auto1.pantera.prefetch.PrefetchDispatcher(
                    this.settingsCache::prefetchTuning,
                    slices::prefetchEnabledFor,
                    slices::upstreamUrlOf,
                    parsers,
                    slices::repoTypeOf,
                    this.prefetchCoordinator::submit
                );
            // Register the dispatcher's onCacheWrite hook globally so every
            // BaseCachedProxySlice adapter — Maven CachedProxySlice today,
            // future adapters too — receives the callback without per-
            // adapter ctor surgery.
            com.auto1.pantera.http.cache.CacheWriteCallbackRegistry.instance()
                .setSharedCallback(dispatcher::onCacheWrite);
            EcsLogger.info("com.auto1.pantera.prefetch")
                .message("Prefetch subsystem started (Task 19b+c)")
                .eventCategory("configuration")
                .eventAction("prefetch_init")
                .eventOutcome("success")
                .field("parsers.count", parsers.size())
                .field("loopback.port", loopbackPort)
                .log();
        } catch (final Exception ex) {
            EcsLogger.error("com.auto1.pantera.prefetch")
                .message("Failed to start prefetch subsystem; continuing without prefetch")
                .eventCategory("configuration")
                .eventAction("prefetch_init")
                .eventOutcome("failure")
                .error(ex)
                .log();
        }
    }

    public void stop() {
        EcsLogger.info("com.auto1.pantera")
            .message("Stopping Pantera and cleaning up resources")
            .eventCategory("web")
            .eventAction("server_stop")
            .eventOutcome("success")
            .log();
        // 1. Stop HTTP/3 servers
        this.http3.forEach((port, server) -> {
            try {
                server.stop();
                EcsLogger.info("com.auto1.pantera")
                    .message("HTTP/3 server on port stopped")
                    .eventCategory("web")
                    .eventAction("http3_stop")
                    .eventOutcome("success")
                    .field("destination.port", port)
                    .log();
            } catch (final Exception e) {
                EcsLogger.error("com.auto1.pantera")
                    .message("Failed to stop HTTP/3 server")
                    .eventCategory("web")
                    .eventAction("http3_stop")
                    .eventOutcome("failure")
                    .field("destination.port", port)
                    .error(e)
                    .log();
            }
        });
        // 2. Stop HTTP/1.1+2 servers
        this.servers.forEach(s -> {
            try {
                s.stop();
                EcsLogger.info("com.auto1.pantera")
                    .message("Pantera's server on port was stopped")
                    .eventCategory("web")
                    .eventAction("server_stop")
                    .eventOutcome("success")
                    .field("destination.port", s.port())
                    .log();
            } catch (final Exception e) {
                EcsLogger.error("com.auto1.pantera")
                    .message("Failed to stop server")
                    .eventCategory("web")
                    .eventAction("server_stop")
                    .eventOutcome("failure")
                    .error(e)
                    .log();
            }
        });
        // 3. Stop QuartzService
        if (quartz != null) {
            try {
                quartz.stop();
            } catch (final Exception e) {
                EcsLogger.error("com.auto1.pantera")
                    .message("Failed to stop QuartzService")
                    .eventCategory("web")
                    .eventAction("quartz_stop")
                    .eventOutcome("failure")
                    .error(e)
                    .log();
            }
        }
        // 3a. Stop PrefetchCoordinator (drains worker threads).
        //     Must run before RuntimeSettingsCache.stop() so the coordinator's
        //     prefetch.* listener doesn't fire during shutdown, and before
        //     DataSource close so any in-flight cooldown lookup completes.
        //     Drop the shared CacheWriteCallback first so an in-flight
        //     cache-write that completes during shutdown can't dispatch to
        //     a coordinator we're tearing down.
        com.auto1.pantera.http.cache.CacheWriteCallbackRegistry.instance().clear();
        if (this.prefetchCoordinator != null) {
            try {
                this.prefetchCoordinator.stop();
                EcsLogger.info("com.auto1.pantera.prefetch")
                    .message("PrefetchCoordinator stopped")
                    .eventCategory("web")
                    .eventAction("prefetch_stop")
                    .eventOutcome("success")
                    .log();
            } catch (final Exception e) {
                EcsLogger.error("com.auto1.pantera.prefetch")
                    .message("Failed to stop PrefetchCoordinator")
                    .eventCategory("web")
                    .eventAction("prefetch_stop")
                    .eventOutcome("failure")
                    .error(e)
                    .log();
            }
        }
        // 3b. Stop RuntimeSettingsCache (releases LISTEN connection + poller).
        //     Must run before settings.close() / DataSource shutdown so the
        //     listener thread can return its connection to the pool cleanly.
        if (this.settingsCache != null) {
            try {
                this.settingsCache.stop();
                EcsLogger.info("com.auto1.pantera.settings.runtime")
                    .message("RuntimeSettingsCache stopped")
                    .eventCategory("web")
                    .eventAction("settings_cache_stop")
                    .eventOutcome("success")
                    .log();
            } catch (final Exception e) {
                EcsLogger.error("com.auto1.pantera.settings.runtime")
                    .message("Failed to stop RuntimeSettingsCache")
                    .eventCategory("web")
                    .eventAction("settings_cache_stop")
                    .eventOutcome("failure")
                    .error(e)
                    .log();
            }
        }
        // 4. Stop ConfigWatchService
        if (this.configWatch != null) {
            try {
                this.configWatch.close();
            } catch (final Exception e) {
                EcsLogger.error("com.auto1.pantera")
                    .message("Failed to close ConfigWatchService")
                    .eventCategory("web")
                    .eventAction("config_watch_stop")
                    .eventOutcome("failure")
                    .error(e)
                    .log();
            }
        }
        // 5. Shutdown BlockedThreadDiagnostics
        try {
            BlockedThreadDiagnostics.shutdownInstance();
            EcsLogger.info("com.auto1.pantera")
                .message("BlockedThreadDiagnostics shut down")
                .eventCategory("web")
                .eventAction("diagnostics_shutdown")
                .eventOutcome("success")
                .log();
        } catch (final Exception e) {
            EcsLogger.error("com.auto1.pantera")
                .message("Failed to shutdown BlockedThreadDiagnostics")
                .eventCategory("web")
                .eventAction("diagnostics_shutdown")
                .eventOutcome("failure")
                .error(e)
                .log();
        }
        // 6. Close settings (releases storage resources, S3AsyncClient, etc.)
        if (this.settings != null) {
            try {
                this.settings.close();
                EcsLogger.info("com.auto1.pantera")
                    .message("Settings and storage resources closed successfully")
                    .eventCategory("web")
                    .eventAction("resource_cleanup")
                    .eventOutcome("success")
                    .log();
            } catch (final Exception e) {
                EcsLogger.error("com.auto1.pantera")
                    .message("Failed to close settings")
                    .eventCategory("web")
                    .eventAction("resource_cleanup")
                    .eventOutcome("failure")
                    .error(e)
                    .log();
            }
        }
        // 7. Shutdown storage executor pools
        try {
            com.auto1.pantera.http.misc.StorageExecutors.shutdown();
            EcsLogger.info("com.auto1.pantera")
                .message("Storage executor pools shut down")
                .eventCategory("web")
                .eventAction("executor_shutdown")
                .eventOutcome("success")
                .log();
        } catch (final Exception e) {
            EcsLogger.error("com.auto1.pantera")
                .message("Failed to shutdown storage executor pools")
                .eventCategory("web")
                .eventAction("executor_shutdown")
                .eventOutcome("failure")
                .error(e)
                .log();
        }
        // 7b. Cancel cooldown cleanup fallback timers (must run before
        //     Vert.x close so the timer ids can still be cancelled).
        if (this.cooldownCleanupFallback != null) {
            try {
                this.cooldownCleanupFallback.stop(this.vertx.getDelegate());
                EcsLogger.info("com.auto1.pantera.cooldown.cleanup")
                    .message("Cooldown cleanup fallback stopped")
                    .eventCategory("web")
                    .eventAction("cooldown_fallback_stop")
                    .eventOutcome("success")
                    .log();
            } catch (final Exception e) {
                EcsLogger.error("com.auto1.pantera.cooldown.cleanup")
                    .message("Failed to stop cooldown cleanup fallback")
                    .eventCategory("web")
                    .eventAction("cooldown_fallback_stop")
                    .eventOutcome("failure")
                    .error(e)
                    .log();
            }
        }
        // 8. Close Vert.x instance (LAST - closes event loops and worker threads)
        if (this.vertx != null) {
            try {
                this.vertx.close();
                EcsLogger.info("com.auto1.pantera")
                    .message("Vert.x instance closed")
                    .eventCategory("web")
                    .eventAction("vertx_close")
                    .eventOutcome("success")
                    .log();
            } catch (final Exception e) {
                EcsLogger.error("com.auto1.pantera")
                    .message("Failed to close Vert.x instance")
                    .eventCategory("web")
                    .eventAction("vertx_close")
                    .eventOutcome("failure")
                    .error(e)
                    .log();
            }
        }
        EcsLogger.info("com.auto1.pantera")
            .message("Pantera shutdown complete")
            .eventCategory("web")
            .eventAction("server_shutdown")
            .eventOutcome("success")
            .log();
    }

    /**
     * Entry point.
     * @param args CLI args
     * @throws Exception If fails
     */
    public static void main(final String... args) throws Exception {
        
        final Path config;
        final int port;
        final int defp = 80;
        final Options options = new Options();
        final String popt = "p";
        final String fopt = "f";
        final String apiport = "ap";
        options.addOption(popt, "port", true, "The port to start Pantera on");
        options.addOption(fopt, "config-file", true, "The path to Pantera configuration file");
        options.addOption(apiport, "api-port", true, "The port to start Pantera Rest API on");
        final CommandLineParser parser = new DefaultParser();
        final CommandLine cmd = parser.parse(options, args);
        if (cmd.hasOption(popt)) {
            port = Integer.parseInt(cmd.getOptionValue(popt));
        } else {
            EcsLogger.info("com.auto1.pantera")
                .message("Using default port")
                .eventCategory("configuration")
                .eventAction("port_configure")
                .eventOutcome("success")
                .field("destination.port", defp)
                .log();
            port = defp;
        }
        if (cmd.hasOption(fopt)) {
            config = Path.of(cmd.getOptionValue(fopt));
        } else {
            throw new IllegalStateException("Storage is not configured");
        }
        EcsLogger.info("com.auto1.pantera")
            .message("Used version of Pantera")
            .eventCategory("web")
            .eventAction("server_start")
            .eventOutcome("success")
            .log();
        final VertxMain app = new VertxMain(config, port);

        // Register shutdown hook to ensure proper cleanup on JVM exit
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            EcsLogger.info("com.auto1.pantera")
                .message("Shutdown hook triggered - cleaning up resources")
                .eventCategory("web")
                .eventAction("shutdown_hook")
                .eventOutcome("success")
                .log();
            app.stop();
        }, "pantera-shutdown-hook"));

        app.start(Integer.parseInt(cmd.getOptionValue(apiport, VertxMain.DEF_API_PORT)));
        EcsLogger.info("com.auto1.pantera")
            .message("Pantera started successfully. Press Ctrl+C to shutdown.")
            .eventCategory("web")
            .eventAction("server_start")
            .eventOutcome("success")
            .log();
    }

    /**
     * Start repository servers.
     *
     * @param vertx Vertx instance
     * @param settings Settings.
     * @param port Pantera service main port
     * @param slices Slices cache
     */
    private void startRepos(
        final Vertx vertx,
        final Settings settings,
        final Repositories repos,
        final int port,
        final RepositorySlices slices
    ) {
        for (final RepoConfig repo : repos.configs()) {
            try {
                repo.port().ifPresentOrElse(
                    prt -> {
                        final String name = new ConfigFile(repo.name()).name();
                        final Slice slice = slices.slice(new Key.From(name), prt);
                        if (repo.startOnHttp3()) {
                            this.http3.computeIfAbsent(
                                prt, key -> {
                                    final Http3Server server = new Http3Server(
                                        new LoggingSlice(slice), prt,
                                        new SslFactoryFromYaml(repo.repoYaml()).build()
                                    );
                                    server.start();
                                    return server;
                                }
                            );
                        } else {
                            this.listenOn(
                                slice,
                                prt,
                                vertx,
                                settings.metrics(),
                                settings.httpServerRequestTimeout(),
                                settings.proxyProtocol()
                            );
                        }
                        EcsLogger.info("com.auto1.pantera")
                            .message("Pantera repo was started on port")
                            .eventCategory("web")
                            .eventAction("repo_start")
                            .eventOutcome("success")
                            .field("repository.name", name)
                            .field("destination.port", prt)
                            .log();
                    },
                    () -> EcsLogger.info("com.auto1.pantera")
                        .message("Pantera repo was started on port")
                        .eventCategory("web")
                        .eventAction("repo_start")
                        .eventOutcome("success")
                        .field("repository.name", repo.name())
                        .field("destination.port", port)
                        .log()
                );
            } catch (final IllegalStateException err) {
                EcsLogger.error("com.auto1.pantera")
                    .message("Invalid repo config file")
                    .eventCategory("web")
                    .eventAction("repo_start")
                    .eventOutcome("failure")
                    .field("repository.name", repo.name())
                    .error(err)
                    .log();
            } catch (final PanteraException err) {
                EcsLogger.error("com.auto1.pantera")
                    .message("Failed to start repo")
                    .eventCategory("web")
                    .eventAction("repo_start")
                    .eventOutcome("failure")
                    .field("repository.name", repo.name())
                    .error(err)
                    .log();
            }
        }
    }

    /**
     * Starts HTTP server listening on specified port.
     *
     * @param slice Slice.
     * @param serverPort Slice server port.
     * @param vertx Vertx instance
     * @param mctx Metrics context
     * @param requestTimeout Maximum time to process a single request
     * @return Port server started to listen on.
     */
    private int listenOn(
        final Slice slice,
        final int serverPort,
        final Vertx vertx,
        final MetricsContext mctx,
        final Duration requestTimeout
    ) {
        return this.listenOn(slice, serverPort, vertx, mctx, requestTimeout, false);
    }

    /**
     * Starts HTTP server listening on specified port.
     *
     * @param slice Slice.
     * @param serverPort Slice server port.
     * @param vertx Vertx instance
     * @param mctx Metrics context
     * @param requestTimeout Maximum time to process a single request
     * @param useProxyProtocol Whether to enable Proxy Protocol v2 (for AWS NLB)
     * @return Port server started to listen on.
     * @checkstyle ParameterNumberCheck (5 lines)
     */
    private int listenOn(
        final Slice slice,
        final int serverPort,
        final Vertx vertx,
        final MetricsContext mctx,
        final Duration requestTimeout,
        final boolean useProxyProtocol
    ) {
        final HttpServerOptions opts = new HttpServerOptions()
            .setPort(serverPort)
            .setIdleTimeout(60)
            .setTcpKeepAlive(true)
            .setTcpNoDelay(true)
            .setUseAlpn(true)
            .setHttp2ClearTextEnabled(true)
            .setInitialSettings(
                new io.vertx.core.http.Http2Settings()
                    .setInitialWindowSize(16 * 1024 * 1024)
            )
            .setHttp2ConnectionWindowSize(128 * 1024 * 1024)
            .setCompressionSupported(true)
            .setCompressionLevel(6);
        if (useProxyProtocol) {
            opts.setUseProxyProtocol(true);
            EcsLogger.info("com.auto1.pantera")
                .message("Proxy Protocol v2 enabled on port " + serverPort)
                .eventCategory("configuration")
                .eventAction("proxy_protocol_enable")
                .eventOutcome("success")
                .field("destination.port", serverPort)
                .log();
        }
        final VertxSliceServer server = new VertxSliceServer(
            vertx,
            new BaseSlice(mctx, slice),
            opts,
            requestTimeout
        );
        this.servers.add(server);
        return server.start();
    }

    /**
     * Obtain and configure Vert.x instance. If vertx metrics are configured,
     * this method enables Micrometer metrics options with Prometheus. Check
     * <a href="https://vertx.io/docs/3.9.13/vertx-micrometer-metrics/java/#_prometheus">docs</a>.
     * @param mctx Metrics context
     * @return Vert.x instance
     */
    private static Vertx vertx(final MetricsContext mctx) {
        final Vertx res;
        final Optional<Pair<String, Integer>> endpoint = mctx.endpointAndPort();
        // NOTE: APM registry removed - using Elastic APM Java Agent via -javaagent (safe mode)
        // Micrometer still used for Prometheus metrics
        final MeterRegistry apm = null;
        
        // Initialize blocked thread diagnostics for root cause analysis
        BlockedThreadDiagnostics.initialize();
        
        // Configure Vert.x options for optimal event loop performance
        final int cpuCores = Runtime.getRuntime().availableProcessors();
        final VertxOptions options = new VertxOptions()
            // Event loop pool size: 2x CPU cores for optimal throughput
            .setEventLoopPoolSize(cpuCores * 2)
            // Worker pool size: for blocking operations (BlockingStorage, etc.)
            .setWorkerPoolSize(Math.max(20, cpuCores * 4))
            // Increase blocked thread check interval to 10s to reduce false positives
            // GC pauses and system load spikes can cause spurious warnings at lower intervals
            .setBlockedThreadCheckInterval(10000)
            // Warn if event loop blocked for more than 5 seconds (increased from 2s)
            // This accounts for GC pauses and reduces false positives in production
            .setMaxEventLoopExecuteTime(5000L * 1000000L) // 5 seconds in nanoseconds
            // Warn if worker thread blocked for more than 120 seconds
            .setMaxWorkerExecuteTime(120000L * 1000000L); // 120 seconds in nanoseconds
        
        if (apm != null || endpoint.isPresent()) {
            final MicrometerMetricsOptions micrometer = new MicrometerMetricsOptions()
                .setEnabled(true)
                // Enable comprehensive Vert.x metrics with labels
                .setJvmMetricsEnabled(true)
                // Add labels for HTTP metrics (method, status code)
                // NOTE: HTTP_PATH label removed to avoid high cardinality
                // Repository-level metrics use pantera_http_requests_total with repo_name label instead
                .addLabels(io.vertx.micrometer.Label.HTTP_METHOD)
                .addLabels(io.vertx.micrometer.Label.HTTP_CODE)
                // Add labels for pool metrics (pool type, pool name)
                .addLabels(io.vertx.micrometer.Label.POOL_TYPE)
                .addLabels(io.vertx.micrometer.Label.POOL_NAME)
                // Add labels for event bus metrics
                .addLabels(io.vertx.micrometer.Label.EB_ADDRESS)
                .addLabels(io.vertx.micrometer.Label.EB_SIDE)
                .addLabels(io.vertx.micrometer.Label.EB_FAILURE);

            if (apm != null) {
                micrometer.setMicrometerRegistry(apm);
            }

            if (endpoint.isPresent()) {
                // CRITICAL FIX: Disable embedded Prometheus server to prevent event loop blocking.
                // The embedded server runs scrape() on the event loop, which can take 2-10s
                // and blocks ALL HTTP requests. Instead, we deploy AsyncMetricsVerticle
                // as a worker verticle that handles scraping off the event loop.
                // See: docs/PERFORMANCE_ISSUES_ANALYSIS.md Issue #1
                micrometer.setPrometheusOptions(
                    new VertxPrometheusOptions().setEnabled(true)
                        .setStartEmbeddedServer(false)  // Disabled - using AsyncMetricsVerticle instead
                );
            }
            options.setMetricsOptions(micrometer);
            res = Vertx.vertx(options);

            // CRITICAL FIX: Get MeterRegistry AFTER Vertx.vertx() to avoid NullPointerException
            // BackendRegistries.getDefaultNow() requires Vertx to be initialized first
            final MeterRegistry registry = BackendRegistries.getDefaultNow();

            // Add common tags to all metrics
            registry.config().commonTags("job", "pantera");

            // Add repo_name cardinality control filter (default max 50 distinct repos)
            registry.config().meterFilter(
                new RepoNameMeterFilter(
                    ConfigDefaults.getInt("PANTERA_METRICS_MAX_REPOS", 50)
                )
            );

            // Configure registry to publish histogram buckets for all Timer metrics
            // Opt-in via PANTERA_METRICS_PERCENTILES_HISTOGRAM env var (default: false)
            if (Boolean.parseBoolean(
                ConfigDefaults.get("PANTERA_METRICS_PERCENTILES_HISTOGRAM", "false")
            )) {
                registry.config().meterFilter(
                    new MeterFilter() {
                        @Override
                        public DistributionStatisticConfig configure(
                            final Meter.Id id,
                            final DistributionStatisticConfig config
                        ) {
                            if (id.getType() == Meter.Type.TIMER) {
                                return DistributionStatisticConfig.builder()
                                    .percentilesHistogram(true)
                                    .build()
                                    .merge(config);
                            }
                            return config;
                        }
                    }
                );
            }

            // Initialize MicrometerMetrics with the registry
            com.auto1.pantera.metrics.MicrometerMetrics.initialize(registry);

            // Initialize GroupResolverMetrics so the drain-drop counter
            // (pantera.group.drain.dropped) registers with Prometheus. Without
            // this call, GroupResolverMetrics.instance() returns null and the
            // counter is never emitted — operators fly blind on drain pool
            // saturation even though the code-level counter increments.
            com.auto1.pantera.metrics.GroupResolverMetrics.initialize(registry);

            // Initialize storage metrics recorder
            com.auto1.pantera.metrics.StorageMetricsRecorder.initialize();

            if (mctx.jvm()) {
                new ClassLoaderMetrics().bindTo(registry);
                new JvmMemoryMetrics().bindTo(registry);
                new JvmGcMetrics().bindTo(registry);
                new ProcessorMetrics().bindTo(registry);
                new JvmThreadMetrics().bindTo(registry);
            }
            if (endpoint.isPresent()) {
                EcsLogger.info("com.auto1.pantera")
                    .message("Micrometer metrics (JVM, Vert.x, Storage, Cache, Repository) enabled on port " + endpoint.get().getValue())
                    .eventCategory("configuration")
                    .eventAction("metrics_configure")
                    .eventOutcome("success")
                    .field("destination.port", endpoint.get().getValue())
                    .field("url.path", endpoint.get().getKey())
                    .log();
            }
        } else {
            res = Vertx.vertx(options);
        }

        EcsLogger.info("com.auto1.pantera")
            .message("Vert.x configured with " + options.getEventLoopPoolSize() + " event loop threads and " + options.getWorkerPoolSize() + " worker threads")
            .eventCategory("configuration")
            .eventAction("vertx_configure")
            .eventOutcome("success")
            .log();

        return res;
    }

}
