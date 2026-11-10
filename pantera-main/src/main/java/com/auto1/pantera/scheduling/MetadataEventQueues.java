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
package com.auto1.pantera.scheduling;

import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.goproxy.GoProxyPackageProcessor;

import com.auto1.pantera.maven.MavenProxyPackageProcessor;
import com.auto1.pantera.npm.events.NpmProxyPackageProcessor;
import com.auto1.pantera.pypi.PyProxyPackageProcessor;
import com.auto1.pantera.composer.http.proxy.ComposerProxyPackageProcessor;
import com.auto1.pantera.settings.repo.RepoConfig;
import com.auto1.pantera.http.log.EcsLogger;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Artifacts metadata events queues.
 * <p>
 * 1) This class holds events queue {@link MetadataEventQueues#eventQueue()} for all the adapters,
 * this queue is passed to adapters, adapters adds packages metadata on upload/delete to the queue.
 * The queue is drained by a per-node {@code LocalEventDrainScheduler} (never through Quartz — see
 * the WS2.2 fix, 2.3.0) and consumed by {@link com.auto1.pantera.db.DbConsumer}.
 * <p>
 * 2) This class also holds queues for proxy adapters (maven, npm, pypi, go, composer). Each proxy
 * repository has its own queue with packages metadata ({@link MetadataEventQueues#queues}) and its
 * own per-node {@link LocalEventDrainScheduler} draining it (WS2.2b fix, 2.3.0). The queue and
 * scheduler for a concrete proxy repository are created/started on the first queue request. If the
 * proxy repository is removed, the scheduler is stopped and the queue is removed.
 * <p>
 * Prior to the WS2.2b fix, the per-repository proxy-package-processor queues were drained through
 * the cluster-shared Quartz job store (RAM or JDBC mode): a {@code JobDataRegistry}-resolved queue
 * scheduled via {@code QuartzService.schedulePeriodicJob}. Under JDBC clustering, Quartz does not
 * pin a repeating trigger to the node that created it — any node's scheduler thread can acquire
 * and fire it. A node other than the one that registered a given repository's queue would find
 * nothing in its own {@code JobDataRegistry} to resolve, and every firing that landed on the
 * "wrong" node was a wasted drain opportunity for the node that actually owned the queue — the
 * same class of bug the WS2.2 artifact-events-drain fix addressed. This class now schedules each
 * proxy repository's processor directly on a per-node {@link LocalEventDrainScheduler}, so it only
 * ever runs on, and drains, the node that owns the queue.
 * @since 0.31
 */
public final class MetadataEventQueues {

    /**
     * Name of the yaml proxy repository settings and item in job data map for npm-proxy.
     */
    private static final String HOST = "host";

    /**
     * Map with proxy adapters name and queue.
     */
    private final Map<String, Queue<ProxyArtifactEvent>> queues;

    /**
     * Map with proxy adapters name and their per-node drain scheduler.
     */
    private final Map<String, LocalEventDrainScheduler<ProxyArtifactEvent>> schedulers;

    /**
     * Artifact events queue.
     */
    private final Queue<ArtifactEvent> queue;

    /**
     * Optional meter registry for metrics.
     */
    private final Optional<MeterRegistry> registry;

    /**
     * Ctor.
     *
     * @param queue Artifact events queue
     */
    public MetadataEventQueues(final Queue<ArtifactEvent> queue) {
        this(queue, Optional.empty());
    }

    /**
     * Ctor.
     *
     * @param queue Artifact events queue
     * @param registry Optional meter registry for queue depth metrics
     */
    public MetadataEventQueues(
        final Queue<ArtifactEvent> queue, final Optional<MeterRegistry> registry
    ) {
        this.queue = queue;
        this.queues = new ConcurrentHashMap<>();
        this.schedulers = new ConcurrentHashMap<>();
        this.registry = registry;
        this.registry.ifPresent(
            reg -> Gauge.builder("pantera.events.queue.size", queue, Queue::size)
                .tag("type", "events")
                .description("Size of the artifact events queue")
                .register(reg)
        );
    }

    /**
     * Artifact events queue.
     * @return Artifact events queue
     */
    public Queue<ArtifactEvent> eventQueue() {
        return this.queue;
    }

    /**
     * Obtain queue for proxy adapter repository.
     * <p>
     * Thread-safety note: concurrent calls for the same config.name() are safe because
     * {@link ConcurrentHashMap#computeIfAbsent} guarantees the mapping function executes
     * exactly once per key. The initial {@code this.queues.get()} check is a fast-path
     * optimization; if two threads both see null, both enter the if-block, but only one
     * thread's lambda will execute inside computeIfAbsent. The other thread receives the
     * already-created queue. The {@code this.schedulers.put()} call inside the lambda also
     * executes exactly once per key, so no duplicate per-node schedulers are started.
     * </p>
     * @param config Repository config
     * @return Queue for proxy events
     */
    public Optional<Queue<ProxyArtifactEvent>> proxyEventQueues(final RepoConfig config) {
        Optional<Queue<ProxyArtifactEvent>> result =
            Optional.ofNullable(this.queues.get(config.name()));
        if (result.isEmpty() && config.storageOpt().isPresent()) {
            try {
                final Queue<ProxyArtifactEvent> events = this.queues.computeIfAbsent(
                    config.name(), key -> this.startProxyProcessing(config)
                );
                result = Optional.of(events);
            } catch (final RuntimeException err) {
                EcsLogger.error("com.auto1.pantera.scheduling")
                    .message("Failed to initialize events queue processing")
                    .eventCategory("process")
                    .eventAction("events_queue_init")
                    .eventOutcome("failure")
                    .field("repository.name", config.name())
                    .error(err)
                    .field("log.source", "application")
                    .log();
                result = Optional.empty();
            }
        }
        return result;
    }

    /**
     * Create the proxy-events queue for {@code config} and start its per-node
     * {@link LocalEventDrainScheduler}, one tick-task per configured thread.
     * @param config Repository config
     * @return The newly created queue
     */
    private Queue<ProxyArtifactEvent> startProxyProcessing(final RepoConfig config) {
        final Queue<ProxyArtifactEvent> res = new LinkedBlockingQueue<>(10_000);
        final ProxyRepoType type = ProxyRepoType.type(config.type());
        final String host = type == ProxyRepoType.NPM_PROXY ? panteraHost(config) : null;
        final ProxyTaskContext ctx = new ProxyTaskContext(res, config.storage(), this.queue, host);
        final int threads = Math.max(1, settingsIntValue(config, "threads_count"));
        final int interval = Math.max(1, settingsIntValue(config, "interval_seconds"));
        final List<Runnable> tasks = new ArrayList<>(threads);
        for (int idx = 0; idx < threads; idx = idx + 1) {
            tasks.add(type.task(ctx));
        }
        this.schedulers.put(
            config.name(), new LocalEventDrainScheduler<>(tasks, interval)
        );
        EcsLogger.info("com.auto1.pantera.scheduling")
            .message("Initialized proxy metadata local scheduler and queue")
            .eventCategory("process")
            .eventAction("metadata_job_init")
            .eventOutcome("success")
            .field("repository.name", config.name())
            .field("log.source", "application")
            .log();
        this.registry.ifPresent(
            reg -> Gauge.builder(
                "pantera.proxy.queue.size", res, Queue::size
            ).tag("repo", config.name())
                .description("Size of proxy artifact event queue")
                .register(reg)
        );
        return res;
    }

    /**
     * Stops proxy repository events processing and removes corresponding queue.
     * @param name Repository name
     */
    public void stopProxyMetadataProcessing(final String name) {
        final LocalEventDrainScheduler<ProxyArtifactEvent> scheduler = // NOPMD CloseResource - closed on the next line when present; null means no scheduler was ever started for this repo
            this.schedulers.remove(name);
        if (scheduler != null) {
            scheduler.close();
        }
        this.queues.remove(name);
    }

    /**
     * Get integer value from settings.
     * @param config Repo config
     * @param key Setting name key
     * @return Int value from repository setting section, -1 if not present
     */
    private static int settingsIntValue(final RepoConfig config, final String key) {
        return config.settings().map(yaml -> yaml.integer(key)).orElse(-1);
    }

    /**
     * Pantera server external host. Required for npm proxy adapter only.
     * @param config Repository config
     * @return The host
     */
    private static String panteraHost(final RepoConfig config) {
        return config.settings()
            .flatMap(yaml -> Optional.ofNullable(yaml.string(MetadataEventQueues.HOST)))
            .orElse("unknown");
    }

    /**
     * Bundles the node-local references a proxy package-processor tick needs: the shared
     * per-repository packages queue, repository storage, the shared artifact-events queue,
     * and (npm only) the external host. Passed as one object so
     * {@link ProxyRepoType#task(ProxyTaskContext)} overrides that don't need every field
     * (e.g. Maven never needs {@code host}) don't trip PMD's unused-parameter check.
     * @param packages Per-repository proxy-events queue
     * @param storage Repository storage
     * @param events Shared artifact-events queue
     * @param host Pantera external host (npm proxy only), or {@code null}
     */
    private record ProxyTaskContext(
        Queue<ProxyArtifactEvent> packages, Storage storage, Queue<ArtifactEvent> events,
        String host
    ) { }

    /**
     * Repository types.
     * @since 0.31
     */
    enum ProxyRepoType {

        MAVEN_PROXY {
            @Override
            Runnable task(final ProxyTaskContext ctx) {
                return () -> {
                    final MavenProxyPackageProcessor processor = new MavenProxyPackageProcessor();
                    processor.setPackages(ctx.packages());
                    processor.setStorage(ctx.storage());
                    processor.setEvents(ctx.events());
                    processor.run();
                };
            }
        },

        PYPI_PROXY {
            @Override
            Runnable task(final ProxyTaskContext ctx) {
                return () -> {
                    final PyProxyPackageProcessor processor = new PyProxyPackageProcessor();
                    processor.setPackages(ctx.packages());
                    processor.setStorage(ctx.storage());
                    processor.setEvents(ctx.events());
                    processor.run();
                };
            }
        },

        NPM_PROXY {
            @Override
            Runnable task(final ProxyTaskContext ctx) {
                return () -> {
                    final NpmProxyPackageProcessor processor = new NpmProxyPackageProcessor();
                    processor.setPackages(ctx.packages());
                    processor.setStorage(ctx.storage());
                    processor.setEvents(ctx.events());
                    processor.setHost(ctx.host());
                    processor.run();
                };
            }
        },

        GRADLE_PROXY {
            @Override
            Runnable task(final ProxyTaskContext ctx) {
                return () -> {
                    final MavenProxyPackageProcessor processor = new MavenProxyPackageProcessor();
                    processor.setPackages(ctx.packages());
                    processor.setStorage(ctx.storage());
                    processor.setEvents(ctx.events());
                    processor.run();
                };
            }
        },

        GO_PROXY {
            @Override
            Runnable task(final ProxyTaskContext ctx) {
                return () -> {
                    final GoProxyPackageProcessor processor = new GoProxyPackageProcessor();
                    processor.setPackages(ctx.packages());
                    processor.setStorage(ctx.storage());
                    processor.setEvents(ctx.events());
                    processor.run();
                };
            }
        },

        PHP_PROXY {
            @Override
            Runnable task(final ProxyTaskContext ctx) {
                return () -> {
                    final ComposerProxyPackageProcessor processor =
                        new ComposerProxyPackageProcessor();
                    processor.setPackages(ctx.packages());
                    processor.setStorage(ctx.storage());
                    processor.setEvents(ctx.events());
                    processor.run();
                };
            }
        };

        /**
         * Build the per-tick task for this repo type. Constructs a fresh processor instance
         * per invocation, matching the fresh-instance-per-firing semantics the pre-WS2.2b
         * Quartz-based scheduling had (Quartz instantiates a new {@code Job} on every
         * execution), so this scheduling-layer change carries no change to per-tick instance
         * state (e.g. {@code MavenProxyPackageProcessor}'s retry-count map).
         * @param ctx Node-local references the task needs
         * @return Runnable tick body
         */
        abstract Runnable task(ProxyTaskContext ctx);

        /**
         * Get enum item by string repo type.
         * @param val String repo type
         * @return Item enum value
         */
        static ProxyRepoType type(final String val) {
            return ProxyRepoType.valueOf(val.toUpperCase(Locale.ROOT).replace("-", "_"));
        }
    }

}
