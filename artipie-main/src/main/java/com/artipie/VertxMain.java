/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */

package com.artipie;

import com.artipie.api.RepositoryEvents;
import com.artipie.api.RestApi;
import com.artipie.asto.Key;
import com.artipie.auth.JwtTokens;
import com.artipie.http.BaseSlice;
import com.artipie.http.MainSlice;
import com.artipie.http.Slice;
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
import com.artipie.settings.repo.Repositories;
import com.artipie.vertx.VertxSliceServer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.jvm.ClassLoaderMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics;
import io.micrometer.core.instrument.binder.system.ProcessorMetrics;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.VertxOptions;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.ext.auth.PubSecKeyOptions;
import io.vertx.ext.auth.jwt.JWTAuth;
import io.vertx.ext.auth.jwt.JWTAuthOptions;
import io.vertx.micrometer.MicrometerMetricsOptions;
import com.artipie.vertx.ApmInstrumentation;
import io.vertx.micrometer.VertxPrometheusOptions;
import io.vertx.micrometer.backends.BackendRegistries;
import io.vertx.reactivex.core.Vertx;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Options;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private static final Logger LOGGER = LoggerFactory.getLogger(VertxMain.class);

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
        quartz = new QuartzService();
        this.settings = new SettingsFromPath(this.config).find(quartz);
        // Apply logging configuration from YAML settings
        if (settings.logging().configured()) {
            settings.logging().apply();
            LOGGER.info("Applied logging configuration from YAML settings");
        }
        final Vertx vertx = VertxMain.vertx(settings.metrics());
        final JWTAuth jwt = JWTAuth.create(
            vertx.getDelegate(), new JWTAuthOptions().addPubSecKey(
                new PubSecKeyOptions().setAlgorithm("HS256").setBuffer("some secret")
            )
        );
        final Repositories repos = new MapRepositories(settings);
        final RepositorySlices slices = new RepositorySlices(settings, repos, new JwtTokens(jwt));
        if (settings.metrics().http()) {
            try {
                slices.enableJettyMetrics(BackendRegistries.getDefaultNow());
            } catch (final IllegalStateException ex) {
                LOGGER.warn("HTTP metrics enabled but MeterRegistry unavailable", ex);
            }
        }
        // Listen for repository change events to refresh runtime without restart
        vertx.getDelegate().eventBus().consumer(
            RepositoryEvents.ADDRESS,
            msg -> {
                try {
                    final String body = String.valueOf(msg.body());
                    final String[] parts = body.split("\\|");
                    if (parts.length >= 2) {
                        final String action = parts[0];
                        final String name = parts[1];
                        if (RepositoryEvents.UPSERT.equals(action)) {
                            repos.refresh();
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
                                        // Start dedicated HTTP server if not already serving this port
                                        final boolean exists = this.servers.stream().anyMatch(s -> s.port() == prt);
                                        if (!exists) {
                                            this.listenOn(
                                                slice,
                                                prt,
                                                vertx,
                                                settings.metrics(),
                                                settings.httpServerRequestTimeout()
                                            );
                                        }
                                    }
                                }
                            ));
                        } else if (RepositoryEvents.REMOVE.equals(action)) {
                            slices.invalidateRepo(name);
                            repos.refresh();
                        } else if (RepositoryEvents.MOVE.equals(action) && parts.length >= 3) {
                            slices.invalidateRepo(name);
                            slices.invalidateRepo(parts[2]);
                            repos.refresh();
                        }
                    }
                } catch (final Throwable err) {
                    LOGGER.error("Failed to process repository event", err);
                }
            }
        );
        final int main = this.listenOn(
            new MainSlice(settings, slices),
            this.port,
            vertx,
            settings.metrics(),
            settings.httpServerRequestTimeout()
        );
        LOGGER.info("Artipie was started on port {}", main);
        this.startRepos(vertx, settings, repos, this.port, slices);
        
        // Deploy RestApi verticle with multiple instances for CPU scaling
        // Use 2x CPU cores to handle concurrent API requests efficiently
        final int apiInstances = Runtime.getRuntime().availableProcessors() * 2;
        final DeploymentOptions deployOpts = new DeploymentOptions()
            .setInstances(apiInstances);
        vertx.deployVerticle(
            () -> new RestApi(settings, apiPort, jwt),
            deployOpts,
            result -> {
                if (result.succeeded()) {
                    LOGGER.info("RestApi deployed with {} instances", apiInstances);
                } else {
                    LOGGER.error("Failed to deploy RestApi", result.cause());
                }
            }
        );
        
        quartz.start();
        new ScriptScheduler(quartz).loadCrontab(settings, repos);
        
        // Start config watch service for hot reload
        try {
            this.configWatch = new com.artipie.settings.ConfigWatchService(
                this.config, settings.prefixes()
            );
            this.configWatch.start();
            LOGGER.info("Config watch service started for hot reload");
        } catch (final IOException ex) {
            LOGGER.error("Failed to start config watch service", ex);
        }
        
        return main;
    }

    public void stop() {
        LOGGER.info("Stopping Artipie and cleaning up resources...");
        this.servers.forEach(s -> {
            s.stop();
            LOGGER.info("Artipie's server on port {} was stopped", s.port());
        });
        if (quartz != null) {
            quartz.stop();
        }
        if (this.configWatch != null) {
            this.configWatch.close();
        }
        // Close settings to cleanup storage resources (S3AsyncClient, etc.)
        if (this.settings != null) {
            try {
                this.settings.close();
                LOGGER.info("Settings and storage resources closed successfully");
            } catch (final Exception e) {
                LOGGER.error("Failed to close settings", e);
            }
        }
        LOGGER.info("Artipie shutdown complete");
    }

    /**
     * Entry point.
     * @param args CLI args
     * @throws Exception If fails
     */
    public static void main(final String... args) throws Exception {
        // Initialize Elastic APM FIRST (before any other code)
        try {
            final Object apm = Class.forName("com.artipie.vertx.ApmInstrumentation")
                .getDeclaredConstructor()
                .newInstance();
            apm.getClass().getMethod("attach").invoke(apm);
        } catch (ClassNotFoundException e) {
            LOGGER.debug("APM instrumentation not available (optional dependency)");
        } catch (Exception e) {
            LOGGER.warn("Failed to initialize APM", e);
        }
        
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
            LOGGER.info("Using default port: {}", defp);
            port = defp;
        }
        if (cmd.hasOption(fopt)) {
            config = Path.of(cmd.getOptionValue(fopt));
        } else {
            throw new IllegalStateException("Storage is not configured");
        }
        LOGGER.info("Used version of Artipie: {}", new ArtipieProperties().version());
        final VertxMain app = new VertxMain(config, port);
        
        // Register shutdown hook to ensure proper cleanup on JVM exit
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOGGER.info("Shutdown hook triggered - cleaning up resources");
            app.stop();
        }, "artipie-shutdown-hook"));
        
        app.start(Integer.parseInt(cmd.getOptionValue(apiport, VertxMain.DEF_API_PORT)));
        LOGGER.info("Artipie started successfully. Press Ctrl+C to shutdown.");
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
                        LOGGER.info("Artipie repo '{}' was started on port {}", name, prt);
                    },
                    () -> LOGGER.info("Artipie repo '{}' was started on port {}", repo.name(), port)
                );
            } catch (final IllegalStateException err) {
                LOGGER.error("Invalid repo config file: " + repo.name(), err);
            } catch (final ArtipieException err) {
                LOGGER.error("Failed to start repo:" + repo.name(), err);
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
        final MeterRegistry apm = ApmInstrumentation.registry();
        
        // Configure Vert.x options for optimal event loop performance
        final int cpuCores = Runtime.getRuntime().availableProcessors();
        final VertxOptions options = new VertxOptions()
            // Event loop pool size: 2x CPU cores for optimal throughput
            .setEventLoopPoolSize(cpuCores * 2)
            // Worker pool size: for blocking operations (BlockingStorage, etc.)
            .setWorkerPoolSize(Math.max(20, cpuCores * 4))
            // Increase blocked thread check interval to reduce false positives
            .setBlockedThreadCheckInterval(5000)
            // Warn if event loop blocked for more than 2 seconds
            .setMaxEventLoopExecuteTime(2000L * 1000000L) // 2 seconds in nanoseconds
            // Warn if worker thread blocked for more than 60 seconds
            .setMaxWorkerExecuteTime(60000L * 1000000L); // 60 seconds in nanoseconds
        
        if (apm != null || endpoint.isPresent()) {
            final MicrometerMetricsOptions micrometer = new MicrometerMetricsOptions().setEnabled(true);
            if (apm != null) {
                micrometer.setMicrometerRegistry(apm).setJvmMetricsEnabled(true);
            }
            if (endpoint.isPresent()) {
                micrometer.setPrometheusOptions(
                    new VertxPrometheusOptions().setEnabled(true)
                        .setStartEmbeddedServer(true)
                        .setEmbeddedServerOptions(
                            new HttpServerOptions().setPort(endpoint.get().getValue())
                        ).setEmbeddedServerEndpoint(endpoint.get().getKey())
                );
            }
            options.setMetricsOptions(micrometer);
            res = Vertx.vertx(options);
            final MeterRegistry registry = apm != null ? apm : BackendRegistries.getDefaultNow();
            if (mctx.jvm()) {
                new ClassLoaderMetrics().bindTo(registry);
                new JvmMemoryMetrics().bindTo(registry);
                new JvmGcMetrics().bindTo(registry);
                new ProcessorMetrics().bindTo(registry);
                new JvmThreadMetrics().bindTo(registry);
            }
            if (endpoint.isPresent()) {
                LOGGER.info(
                    "Monitoring is enabled, prometheus metrics are available on localhost:{}{}",
                    endpoint.get().getValue(), endpoint.get().getKey()
                );
            }
        } else {
            res = Vertx.vertx(options);
        }
        
        LOGGER.info(
            "Vert.x configured: {} event loop threads, {} worker threads",
            options.getEventLoopPoolSize(), options.getWorkerPoolSize()
        );
        
        return res;
    }

}
