/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.scheduling;

import com.auto1.pantera.PanteraException;
import com.auto1.pantera.goproxy.GoProxyPackageProcessor;

import com.auto1.pantera.maven.MavenProxyPackageProcessor;
import com.auto1.pantera.npm.events.NpmProxyPackageProcessor;
import com.auto1.pantera.pypi.PyProxyPackageProcessor;
import com.auto1.pantera.composer.http.proxy.ComposerProxyPackageProcessor;
import com.auto1.pantera.settings.repo.RepoConfig;
import com.auto1.pantera.http.log.EcsLogger;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import org.quartz.JobDataMap;
import org.quartz.JobKey;
import org.quartz.SchedulerException;

/**
 * Artifacts metadata events queues.
 * <p>
 * 1) This class holds events queue {@link MetadataEventQueues#eventQueue()} for all the adapters,
 * this queue is passed to adapters, adapters adds packages metadata on upload/delete to the queue.
 * Queue is periodically processed by {@link com.auto1.pantera.scheduling.EventsProcessor} and consumed
 * by {@link com.auto1.pantera.db.DbConsumer}.
 * <p>
 * 2) This class also holds queues for proxy adapters (maven, npm, pypi). Each proxy repository
 * has its own queue with packages metadata ({@link MetadataEventQueues#queues}) and its own quartz
 * job to process this queue. The queue and job for concrete proxy repository are created/started
 * on the first queue request. If proxy repository is removed, jobs are stopped
 * and queue is removed.
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
     * Map with proxy adapters name and corresponding quartz jobs keys.
     */
    private final Map<String, Set<JobKey>> keys;

    /**
     * Artifact events queue.
     */
    private final Queue<ArtifactEvent> queue;

    /**
     * Quartz service.
     */
    private final QuartzService quartz;

    /**
     * Optional meter registry for metrics.
     */
    private final Optional<MeterRegistry> registry;

    /**
     * Ctor.
     *
     * @param queue Artifact events queue
     * @param quartz Quartz service
     */
    public MetadataEventQueues(
        final Queue<ArtifactEvent> queue, final QuartzService quartz
    ) {
        this(queue, quartz, Optional.empty());
    }

    /**
     * Ctor.
     *
     * @param queue Artifact events queue
     * @param quartz Quartz service
     * @param registry Optional meter registry for queue depth metrics
     */
    public MetadataEventQueues(
        final Queue<ArtifactEvent> queue, final QuartzService quartz,
        final Optional<MeterRegistry> registry
    ) {
        this.queue = queue;
        this.queues = new ConcurrentHashMap<>();
        this.quartz = quartz;
        this.keys = new ConcurrentHashMap<>();
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
     * already-created queue. The {@code this.keys.put()} call inside the lambda also
     * executes exactly once per key, so no duplicate jobs are scheduled.
     * </p>
     * @param config Repository config
     * @return Queue for proxy events
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    public Optional<Queue<ProxyArtifactEvent>> proxyEventQueues(final RepoConfig config) {
        Optional<Queue<ProxyArtifactEvent>> result =
            Optional.ofNullable(this.queues.get(config.name()));
        if (result.isEmpty() && config.storageOpt().isPresent()) {
            try {
                final Queue<ProxyArtifactEvent> events = this.queues.computeIfAbsent(
                    config.name(),
                    key -> {
                        final Queue<ProxyArtifactEvent> res =
                            new LinkedBlockingQueue<>(10_000);
                        final JobDataMap data = new JobDataMap();
                        final ProxyRepoType type = ProxyRepoType.type(config.type());
                        if (this.quartz.isClustered()) {
                            final String prefix = config.name() + "-proxy-";
                            final String pkgKey = prefix + "packages";
                            final String stoKey = prefix + "storage";
                            final String evtKey = prefix + "events";
                            JobDataRegistry.register(pkgKey, res);
                            JobDataRegistry.register(stoKey, config.storage());
                            JobDataRegistry.register(evtKey, this.queue);
                            data.put("packages_key", pkgKey);
                            data.put("storage_key", stoKey);
                            data.put("events_key", evtKey);
                            if (type == ProxyRepoType.NPM_PROXY) {
                                data.put(MetadataEventQueues.HOST, panteraHost(config));
                            }
                        } else {
                            data.put("packages", res);
                            data.put("storage", config.storage());
                            data.put("events", this.queue);
                            if (type == ProxyRepoType.NPM_PROXY) {
                                data.put(MetadataEventQueues.HOST, panteraHost(config));
                            }
                        }
                        final int threads = Math.max(1, settingsIntValue(config, "threads_count"));
                        final int interval = Math.max(
                            1, settingsIntValue(config, "interval_seconds")
                        );
                        try {
                            this.keys.put(
                                config.name(),
                                this.quartz.schedulePeriodicJob(interval, threads, type.job(), data)
                            );
                            EcsLogger.info("com.auto1.pantera.scheduling")
                                .message("Initialized proxy metadata job and queue")
                                .eventCategory("scheduling")
                                .eventAction("metadata_job_init")
                                .eventOutcome("success")
                                .field("repository.name", config.name())
                                .log();
                        } catch (final SchedulerException err) {
                            throw new PanteraException(err);
                        }
                        this.registry.ifPresent(
                            reg -> Gauge.builder(
                                "pantera.proxy.queue.size", res, Queue::size
                            ).tag("repo", config.name())
                                .description("Size of proxy artifact event queue")
                                .register(reg)
                        );
                        return res;
                    }
                );
                result = Optional.of(events);
            } catch (final Exception err) {
                EcsLogger.error("com.auto1.pantera.scheduling")
                    .message("Failed to initialize events queue processing")
                    .eventCategory("scheduling")
                    .eventAction("events_queue_init")
                    .eventOutcome("failure")
                    .field("repository.name", config.name())
                    .error(err)
                    .log();
                result = Optional.empty();
            }
        }
        return result;
    }

    /**
     * Stops proxy repository events processing and removes corresponding queue.
     * @param name Repository name
     */
    public void stopProxyMetadataProcessing(final String name) {
        final Set<JobKey> set = this.keys.remove(name);
        if (set != null) {
            set.forEach(this.quartz::deleteJob);
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
     * Repository types.
     * @since 0.31
     */
    enum ProxyRepoType {

        MAVEN_PROXY {
            @Override
            Class<? extends QuartzJob> job() {
                return MavenProxyPackageProcessor.class;
            }
        },

        PYPI_PROXY {
            @Override
            Class<? extends QuartzJob> job() {
                return PyProxyPackageProcessor.class;
            }
        },

        NPM_PROXY {
            @Override
            Class<? extends QuartzJob> job() {
                return NpmProxyPackageProcessor.class;
            }
        },

        GRADLE_PROXY {
            @Override
            Class<? extends QuartzJob> job() {
                return MavenProxyPackageProcessor.class;
            }
        },

        GO_PROXY {
            @Override
            Class<? extends QuartzJob> job() {
                return GoProxyPackageProcessor.class;
            }
        },

        PHP_PROXY {
            @Override
            Class<? extends QuartzJob> job() {
                return ComposerProxyPackageProcessor.class;
            }
        };

        /**
         * Class of the corresponding quartz job.
         * @return Class of the quartz job
         */
        abstract Class<? extends QuartzJob> job();

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
