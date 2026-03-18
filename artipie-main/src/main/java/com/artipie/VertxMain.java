/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */

package com.artipie;

import com.artipie.api.RepositoryEvents;
import com.artipie.api.v1.AsyncApiVerticle;
import com.artipie.asto.Key;
import com.artipie.auth.JwtTokens;
import com.artipie.http.BaseSlice;
import com.artipie.http.MainSlice;
import com.artipie.http.Slice;
import com.artipie.http.misc.ConfigDefaults;
import com.artipie.http.misc.RepoNameMeterFilter;
import com.artipie.http.misc.StorageExecutors;
import com.artipie.http.slice.LoggingSlice;
import com.artipie.jetty.http3.Http3Server;
import com.artipie.jetty.http3.SslFactoryFromYaml;
import com.artipie.misc.ArtipieProperties;
import com.artipie.scheduling.QuartzService;
import com.artipie.scheduling.ScriptScheduler;
import com.artipie.settings.ConfigFile;
import com.artipie.settings.MetricsContext;
import com.artipie.settings.Settings;
import com.artipie.settings.SettingsFromPath;
import com.artipie.settings.repo.MapRepositories;
import com.artipie.settings.repo.RepoConfig;
import com.artipie.http.log.EcsLogger;
import com.artipie.settings.repo.Repositories;
import com.artipie.db.DbManager;
import com.artipie.db.migration.YamlToDbMigrator;
import com.artipie.vertx.VertxSliceServer;
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
import io.vertx.ext.auth.PubSecKeyOptions;
import io.vertx.ext.auth.jwt.JWTAuth;
import io.vertx.ext.auth.jwt.JWTAuthOptions;
import io.vertx.micrometer.MicrometerMetricsOptions;
import io.vertx.micrometer.VertxPrometheusOptions;
import io.vertx.micrometer.backends.BackendRegistries;
import io.vertx.reactivex.core.Vertx;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Options;
import org.apache.commons.lang3.tuple.Pair;
import com.artipie.diagnostics.BlockedThreadDiagnostics;

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
     * Default port to start Artipie Rest API service.
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
    private com.artipie.settings.ConfigWatchService configWatch;

    /**
     * Vert.x instance - must be closed on shutdown to release event loops and worker threads.
     */
    private Vertx vertx;

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
        // Pre-parse YAML to detect DB configuration for Quartz JDBC clustering
        final com.amihaiemil.eoyaml.YamlMapping yamlContent =
            com.amihaiemil.eoyaml.Yaml.createYamlInput(this.config.toFile()).readYamlMapping();
        final com.amihaiemil.eoyaml.YamlMapping meta = yamlContent.yamlMapping("meta");
        final Optional<javax.sql.DataSource> sharedDs;
        if (meta != null && meta.yamlMapping("artifacts_database") != null) {
            final javax.sql.DataSource ds =
                new com.artipie.db.ArtifactDbFactory(meta, "artifacts").initialize();
            sharedDs = Optional.of(ds);
            DbManager.migrate(ds);
            // Resolve repos and security dirs from YAML config, not relative to config file.
            // artipie.yml may be mounted at /etc/artipie/ while data lives at /var/artipie/.
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
            quartz = new QuartzService(ds);
            EcsLogger.info("com.artipie")
                .message("Quartz JDBC clustering enabled with shared DataSource")
                .eventCategory("scheduling")
                .eventAction("quartz_jdbc_init")
                .eventOutcome("success")
                .log();
        } else {
            sharedDs = Optional.empty();
            quartz = new QuartzService();
        }
        this.settings = new SettingsFromPath(this.config).find(quartz, sharedDs);
        // Apply logging configuration from YAML settings
        if (settings.logging().configured()) {
            settings.logging().apply();
            EcsLogger.info("com.artipie")
                .message("Applied logging configuration from YAML settings")
                .eventCategory("configuration")
                .eventAction("logging_configure")
                .eventOutcome("success")
                .log();
        }



        this.vertx = VertxMain.vertx(settings.metrics());
        final com.artipie.settings.JwtSettings jwtSettings = settings.jwtSettings();
        final JWTAuth jwt = JWTAuth.create(
            this.vertx.getDelegate(), new JWTAuthOptions().addPubSecKey(
                new PubSecKeyOptions().setAlgorithm("HS256").setBuffer(jwtSettings.secret())
            )
        );
        final Repositories repos = new MapRepositories(settings);
        final RepositorySlices slices = new RepositorySlices(settings, repos, new JwtTokens(jwt, jwtSettings));
        if (settings.metrics().http()) {
            try {
                slices.enableJettyMetrics(BackendRegistries.getDefaultNow());
            } catch (final IllegalStateException ex) {
                EcsLogger.warn("com.artipie")
                    .message("HTTP metrics enabled but MeterRegistry unavailable")
                    .eventCategory("configuration")
                    .eventAction("metrics_configure")
                    .eventOutcome("failure")
                    .error(ex)
                    .log();
            }
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
                                        EcsLogger.error("com.artipie")
                                            .message("Failed to refresh repositories after UPSERT event")
                                            .eventCategory("repository")
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
                                        EcsLogger.error("com.artipie")
                                            .message("Failed to refresh repositories after REMOVE event")
                                            .eventCategory("repository")
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
                                        EcsLogger.error("com.artipie")
                                            .message("Failed to refresh repositories after MOVE event")
                                            .eventCategory("repository")
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
                    EcsLogger.error("com.artipie")
                        .message("Failed to process repository event")
                        .eventCategory("repository")
                        .eventAction("event_process")
                        .eventOutcome("failure")
                        .error(err)
                        .log();
                }
            }
        );
        final int main = this.listenOn(
            new MainSlice(settings, slices),
            this.port,
            this.vertx,
            settings.metrics(),
            settings.httpServerRequestTimeout()
        );
        EcsLogger.info("com.artipie")
            .message("Artipie was started on port")
            .eventCategory("server")
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
            () -> new AsyncApiVerticle(settings, apiPort, jwt, sharedDs.orElse(null)),
            deployOpts,
            result -> {
                if (result.succeeded()) {
                    EcsLogger.info("com.artipie.api")
                        .message("AsyncApiVerticle deployed with " + apiInstances + " instances")
                        .eventCategory("api")
                        .eventAction("api_deploy")
                        .eventOutcome("success")
                        .log();
                } else {
                    EcsLogger.error("com.artipie.api")
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
                        "artipie.events.queue.size",
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
                    () -> new com.artipie.metrics.AsyncMetricsVerticle(
                        metricsRegistry, metricsPort, metricsPath, metricsCacheTtlMs
                    ),
                    metricsOpts,
                    metricsResult -> {
                        if (metricsResult.succeeded()) {
                            EcsLogger.info("com.artipie.metrics")
                                .message(String.format("AsyncMetricsVerticle deployed as worker verticle with cache TTL %dms", metricsCacheTtlMs))
                                .eventCategory("metrics")
                                .eventAction("metrics_verticle_deploy")
                                .eventOutcome("success")
                                .field("destination.port", metricsPort)
                                .field("url.path", metricsPath)
                                .log();
                        } else {
                            EcsLogger.error("com.artipie.metrics")
                                .message("Failed to deploy AsyncMetricsVerticle")
                                .eventCategory("metrics")
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
            this.configWatch = new com.artipie.settings.ConfigWatchService(
                this.config, settings.prefixes()
            );
            this.configWatch.start();
            EcsLogger.info("com.artipie")
                .message("Config watch service started for hot reload")
                .eventCategory("configuration")
                .eventAction("config_watch_start")
                .eventOutcome("success")
                .log();
        } catch (final IOException ex) {
            EcsLogger.error("com.artipie")
                .message("Failed to start config watch service")
                .eventCategory("configuration")
                .eventAction("config_watch_start")
                .eventOutcome("failure")
                .error(ex)
                .log();
        }
        
        return main;
    }

    public void stop() {
        EcsLogger.info("com.artipie")
            .message("Stopping Artipie and cleaning up resources")
            .eventCategory("server")
            .eventAction("server_stop")
            .eventOutcome("success")
            .log();
        // 1. Stop HTTP/3 servers
        this.http3.forEach((port, server) -> {
            try {
                server.stop();
                EcsLogger.info("com.artipie")
                    .message("HTTP/3 server on port stopped")
                    .eventCategory("server")
                    .eventAction("http3_stop")
                    .eventOutcome("success")
                    .field("destination.port", port)
                    .log();
            } catch (final Exception e) {
                EcsLogger.error("com.artipie")
                    .message("Failed to stop HTTP/3 server")
                    .eventCategory("server")
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
                EcsLogger.info("com.artipie")
                    .message("Artipie's server on port was stopped")
                    .eventCategory("server")
                    .eventAction("server_stop")
                    .eventOutcome("success")
                    .field("destination.port", s.port())
                    .log();
            } catch (final Exception e) {
                EcsLogger.error("com.artipie")
                    .message("Failed to stop server")
                    .eventCategory("server")
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
                EcsLogger.error("com.artipie")
                    .message("Failed to stop QuartzService")
                    .eventCategory("server")
                    .eventAction("quartz_stop")
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
                EcsLogger.error("com.artipie")
                    .message("Failed to close ConfigWatchService")
                    .eventCategory("server")
                    .eventAction("config_watch_stop")
                    .eventOutcome("failure")
                    .error(e)
                    .log();
            }
        }
        // 5. Shutdown BlockedThreadDiagnostics
        try {
            BlockedThreadDiagnostics.shutdownInstance();
            EcsLogger.info("com.artipie")
                .message("BlockedThreadDiagnostics shut down")
                .eventCategory("server")
                .eventAction("diagnostics_shutdown")
                .eventOutcome("success")
                .log();
        } catch (final Exception e) {
            EcsLogger.error("com.artipie")
                .message("Failed to shutdown BlockedThreadDiagnostics")
                .eventCategory("server")
                .eventAction("diagnostics_shutdown")
                .eventOutcome("failure")
                .error(e)
                .log();
        }
        // 6. Close settings (releases storage resources, S3AsyncClient, etc.)
        if (this.settings != null) {
            try {
                this.settings.close();
                EcsLogger.info("com.artipie")
                    .message("Settings and storage resources closed successfully")
                    .eventCategory("server")
                    .eventAction("resource_cleanup")
                    .eventOutcome("success")
                    .log();
            } catch (final Exception e) {
                EcsLogger.error("com.artipie")
                    .message("Failed to close settings")
                    .eventCategory("server")
                    .eventAction("resource_cleanup")
                    .eventOutcome("failure")
                    .error(e)
                    .log();
            }
        }
        // 7. Shutdown storage executor pools
        try {
            com.artipie.http.misc.StorageExecutors.shutdown();
            EcsLogger.info("com.artipie")
                .message("Storage executor pools shut down")
                .eventCategory("server")
                .eventAction("executor_shutdown")
                .eventOutcome("success")
                .log();
        } catch (final Exception e) {
            EcsLogger.error("com.artipie")
                .message("Failed to shutdown storage executor pools")
                .eventCategory("server")
                .eventAction("executor_shutdown")
                .eventOutcome("failure")
                .error(e)
                .log();
        }
        // 8. Close Vert.x instance (LAST - closes event loops and worker threads)
        if (this.vertx != null) {
            try {
                this.vertx.close();
                EcsLogger.info("com.artipie")
                    .message("Vert.x instance closed")
                    .eventCategory("server")
                    .eventAction("vertx_close")
                    .eventOutcome("success")
                    .log();
            } catch (final Exception e) {
                EcsLogger.error("com.artipie")
                    .message("Failed to close Vert.x instance")
                    .eventCategory("server")
                    .eventAction("vertx_close")
                    .eventOutcome("failure")
                    .error(e)
                    .log();
            }
        }
        EcsLogger.info("com.artipie")
            .message("Artipie shutdown complete")
            .eventCategory("server")
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
        options.addOption(popt, "port", true, "The port to start Artipie on");
        options.addOption(fopt, "config-file", true, "The path to Artipie configuration file");
        options.addOption(apiport, "api-port", true, "The port to start Artipie Rest API on");
        final CommandLineParser parser = new DefaultParser();
        final CommandLine cmd = parser.parse(options, args);
        if (cmd.hasOption(popt)) {
            port = Integer.parseInt(cmd.getOptionValue(popt));
        } else {
            EcsLogger.info("com.artipie")
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
        EcsLogger.info("com.artipie")
            .message("Used version of Artipie")
            .eventCategory("server")
            .eventAction("server_start")
            .eventOutcome("success")
            .field("service.version", new ArtipieProperties().version())
            .log();
        final VertxMain app = new VertxMain(config, port);

        // Register shutdown hook to ensure proper cleanup on JVM exit
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            EcsLogger.info("com.artipie")
                .message("Shutdown hook triggered - cleaning up resources")
                .eventCategory("server")
                .eventAction("shutdown_hook")
                .eventOutcome("success")
                .log();
            app.stop();
        }, "artipie-shutdown-hook"));

        app.start(Integer.parseInt(cmd.getOptionValue(apiport, VertxMain.DEF_API_PORT)));
        EcsLogger.info("com.artipie")
            .message("Artipie started successfully. Press Ctrl+C to shutdown.")
            .eventCategory("server")
            .eventAction("server_start")
            .eventOutcome("success")
            .log();
    }

    /**
     * Start repository servers.
     *
     * @param vertx Vertx instance
     * @param settings Settings.
     * @param port Artipie service main port
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
                                settings.httpServerRequestTimeout()
                            );
                        }
                        EcsLogger.info("com.artipie")
                            .message("Artipie repo was started on port")
                            .eventCategory("repository")
                            .eventAction("repo_start")
                            .eventOutcome("success")
                            .field("repository.name", name)
                            .field("destination.port", prt)
                            .log();
                    },
                    () -> EcsLogger.info("com.artipie")
                        .message("Artipie repo was started on port")
                        .eventCategory("repository")
                        .eventAction("repo_start")
                        .eventOutcome("success")
                        .field("repository.name", repo.name())
                        .field("destination.port", port)
                        .log()
                );
            } catch (final IllegalStateException err) {
                EcsLogger.error("com.artipie")
                    .message("Invalid repo config file")
                    .eventCategory("repository")
                    .eventAction("repo_start")
                    .eventOutcome("failure")
                    .field("repository.name", repo.name())
                    .error(err)
                    .log();
            } catch (final ArtipieException err) {
                EcsLogger.error("com.artipie")
                    .message("Failed to start repo")
                    .eventCategory("repository")
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
     * @return Port server started to listen on.
     */
    private int listenOn(
        final Slice slice,
        final int serverPort,
        final Vertx vertx,
        final MetricsContext mctx,
        final Duration requestTimeout
    ) {
        final VertxSliceServer server = new VertxSliceServer(
            vertx,
            new BaseSlice(mctx, slice),
            serverPort,
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
                // Repository-level metrics use artipie_http_requests_total with repo_name label instead
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
            registry.config().commonTags("job", "artipie");

            // Add repo_name cardinality control filter (default max 50 distinct repos)
            registry.config().meterFilter(
                new RepoNameMeterFilter(
                    ConfigDefaults.getInt("ARTIPIE_METRICS_MAX_REPOS", 50)
                )
            );

            // Configure registry to publish histogram buckets for all Timer metrics
            // Opt-in via ARTIPIE_METRICS_PERCENTILES_HISTOGRAM env var (default: false)
            if (Boolean.parseBoolean(
                ConfigDefaults.get("ARTIPIE_METRICS_PERCENTILES_HISTOGRAM", "false")
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
            com.artipie.metrics.MicrometerMetrics.initialize(registry);

            // Initialize storage metrics recorder
            com.artipie.metrics.StorageMetricsRecorder.initialize();

            if (mctx.jvm()) {
                new ClassLoaderMetrics().bindTo(registry);
                new JvmMemoryMetrics().bindTo(registry);
                new JvmGcMetrics().bindTo(registry);
                new ProcessorMetrics().bindTo(registry);
                new JvmThreadMetrics().bindTo(registry);
            }
            if (endpoint.isPresent()) {
                EcsLogger.info("com.artipie")
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

        EcsLogger.info("com.artipie")
            .message("Vert.x configured with " + options.getEventLoopPoolSize() + " event loop threads and " + options.getWorkerPoolSize() + " worker threads")
            .eventCategory("configuration")
            .eventAction("vertx_configure")
            .eventOutcome("success")
            .log();

        return res;
    }

}
