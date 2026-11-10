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

import com.auto1.pantera.PanteraException;
import com.auto1.pantera.http.log.EcsLogger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.quartz.CronScheduleBuilder;
import org.quartz.Job;
import org.quartz.JobBuilder;
import org.quartz.JobDataMap;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.SimpleTrigger;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.quartz.impl.StdSchedulerFactory;
import org.quartz.impl.matchers.GroupMatcher;
import org.quartz.utils.DBConnectionManager;

/**
 * Quartz scheduling service.
 * <p>
 * Supports two modes:
 * <ul>
 *   <li><b>RAM mode</b> (default, no-arg constructor) -- uses in-memory RAMJobStore.
 *       Suitable for single-instance deployments.</li>
 *   <li><b>JDBC mode</b> (DataSource constructor) -- uses {@code JobStoreTX} backed by
 *       PostgreSQL. Enables Quartz clustering so multiple Pantera instances coordinate
 *       job execution through the database and avoid duplicate scheduling.</li>
 * </ul>
 *
 * @since 1.3
 */
public final class QuartzService {

    /**
     * Scheduler instance name shared across all clustered nodes.
     */
    private static final String SCHED_NAME = "PanteraScheduler";

    /**
     * Job group name the pre-2.3.0 clustered artifact-events drain used
     * ({@code EventsProcessor.class.getSimpleName()} via the since-removed
     * {@code addPeriodicEventsProcessor}). That drain now runs on a per-node
     * {@code LocalEventDrainScheduler} instead of clustered Quartz (WS2.2,
     * 2.3.0), so any QRTZ_* rows surviving under this group from an older
     * deployment are permanently orphaned — their {@link JobDataRegistry}
     * entries died with the JVM that created them, and nothing will ever
     * schedule under this group again. Safe to purge once, on JDBC boot,
     * instead of the blanket {@code scheduler.clear()} this replaces (that
     * call wiped every node's jobs, including live cron jobs).
     */
    private static final String STALE_EVENTS_JOB_GROUP = "EventsProcessor";

    /**
     * Job group names the pre-2.3.0 clustered per-repository
     * proxy-package-processor pipeline used (each is a
     * {@code *ProxyPackageProcessor.class.getSimpleName()}, matching the
     * job group {@link #schedulePeriodicJob(int, int, Class, JobDataMap)}
     * derives from the job class). As of the WS2.2b fix, this pipeline runs
     * on a per-node {@code LocalEventDrainScheduler} instead — same
     * rationale as {@link #STALE_EVENTS_JOB_GROUP} — so QRTZ_* rows
     * surviving under these groups from a pre-fix deployment are orphaned
     * and, worse, reference job classes that no longer implement
     * {@code org.quartz.Job} at all: leaving them would make Quartz fail to
     * instantiate the job on the next misfire-driven or clustered-recovery
     * firing attempt. Purged once, on JDBC boot, same as the events group.
     */
    private static final String[] STALE_PROXY_PROCESSOR_JOB_GROUPS = {
        "GoProxyPackageProcessor", "MavenProxyPackageProcessor", "NpmProxyPackageProcessor",
        "PyProxyPackageProcessor", "ComposerProxyPackageProcessor",
    };

    /**
     * Quartz scheduler.
     */
    private final Scheduler scheduler;

    /**
     * Whether this service is backed by JDBC (clustered mode).
     */
    private final boolean clustered;

    /**
     * Flag to prevent double-shutdown of the Quartz scheduler.
     * @since 1.20.13
     */
    private final AtomicBoolean stopped = new AtomicBoolean(false);

    /**
     * Ctor for RAM-based (non-clustered) scheduler.
     * Uses the default Quartz configuration with in-memory RAMJobStore.
     */
    public QuartzService() {
        try {
            this.scheduler = new StdSchedulerFactory().getScheduler();
            this.clustered = false;
            this.addShutdownHook();
        } catch (final SchedulerException error) {
            throw new PanteraException(error);
        }
    }

    /**
     * Ctor for JDBC-backed clustered scheduler.
     * <p>
     * Creates the Quartz schema (QRTZ_* tables) if they do not exist,
     * registers a {@link PanteraQuartzConnectionProvider} wrapping the given
     * DataSource, and configures Quartz to use {@code JobStoreTX} with
     * PostgreSQL delegate and clustering enabled.
     *
     * @param dataSource PostgreSQL data source (typically HikariCP)
     */
    public QuartzService(final DataSource dataSource) {
        try {
            // 1. Create QRTZ_* tables if they don't exist
            new QuartzSchema(dataSource).create();
            // 2. Register our ConnectionProvider with Quartz's DBConnectionManager
            DBConnectionManager.getInstance().addConnectionProvider(
                PanteraQuartzConnectionProvider.DS_NAME,
                new PanteraQuartzConnectionProvider(dataSource)
            );
            // 3. Build JDBC properties for Quartz
            final Properties props = QuartzService.jdbcProperties();
            final StdSchedulerFactory factory = new StdSchedulerFactory();
            factory.initialize(props);
            this.scheduler = factory.getScheduler();
            this.clustered = true;
            // 4. Purge stale rows left by a pre-2.3.0 deployment's clustered
            // events-drain jobs — see STALE_EVENTS_JOB_GROUP — and, as of
            // the WS2.2b fix, a pre-fix deployment's clustered per-repository
            // proxy-package-processor jobs too. Scoped purge, not a blanket
            // clear(): a blanket clear() would also delete live cron jobs.
            this.purgeStaleJobGroup(QuartzService.STALE_EVENTS_JOB_GROUP);
            for (final String group : QuartzService.STALE_PROXY_PROCESSOR_JOB_GROUPS) {
                this.purgeStaleJobGroup(group);
            }
            this.addShutdownHook();
            EcsLogger.info("com.auto1.pantera.scheduling")
                .message("Quartz JDBC clustering enabled (scheduler: "
                    + QuartzService.SCHED_NAME + ")")
                .eventCategory("process")
                .eventAction("jdbc_cluster_init")
                .eventOutcome("success")
                .field("log.source", "application")
                .log();
        } catch (final SchedulerException error) {
            throw new PanteraException(error);
        }
    }

    /**
     * Returns whether this service is running in clustered JDBC mode.
     * @return True if JDBC-backed clustering is enabled
     */
    public boolean isClustered() {
        return this.clustered;
    }

    /**
     * Checks whether the Quartz scheduler is running.
     * @return True if started, not shutdown, and not in standby mode
     */
    public boolean isRunning() {
        try {
            return this.scheduler.isStarted() && !this.scheduler.isShutdown()
                && !this.scheduler.isInStandbyMode();
        } catch (final SchedulerException ex) {
            // EXPECTED: scheduler state probe — any access failure means
            // "not running", which is the safe default for health
            // checks and readiness probes.
            return false;
        }
    }

    /**
     * Schedule jobs for class `clazz` to be performed every `seconds` in parallel amount of
     * `thread` with given `data`. If scheduler thread pool size is smaller than `thread` value,
     * parallel jobs amount is reduced to thread pool size.
     * @param seconds Interval in seconds
     * @param threads Parallel threads amount
     * @param clazz Job class, implementation of {@link org.quartz.Job}
     * @param data Job data map
     * @param <T> Class type parameter
     * @return Set of the started quartz job keys
     * @throws SchedulerException On error
     */
    public <T extends Job> Set<JobKey> schedulePeriodicJob(
        final int seconds, final int threads, final Class<T> clazz, final JobDataMap data
    ) throws SchedulerException {
        final String id = String.join(
            "-", clazz.getSimpleName(), UUID.randomUUID().toString()
        );
        final TriggerBuilder<SimpleTrigger> trigger = TriggerBuilder.newTrigger()
            .startNow().withSchedule(SimpleScheduleBuilder.repeatSecondlyForever(seconds));
        final int count = this.parallelJobs(threads);
        final Set<JobKey> res = new HashSet<>(count);
        // Stamp the scheduling thread's MDC trace context once for all jobs
        // built from this shared JobDataMap; see TracingJobWrapper.
        TracingJobWrapper.stampMdc(data);
        for (int item = 0; item < count; item = item + 1) {
            final JobKey key = new JobKey(QuartzService.jobId(id, item), clazz.getSimpleName());
            this.scheduler.scheduleJob(
                JobBuilder.newJob(clazz).setJobData(data).withIdentity(key).build(),
                trigger.withIdentity(
                    QuartzService.triggerId(id, item),
                    clazz.getSimpleName()
                ).build()
            );
            res.add(key);
        }
        this.log(count, clazz.getSimpleName(), seconds);
        return res;
    }

    /**
     * Schedule jobs for class `clazz` to be performed according to `cronexp` cron format schedule.
     * @param cronexp Cron expression in format {@link org.quartz.CronExpression}
     * @param clazz Class of the Job.
     * @param data JobDataMap for job.
     * @param <T> Class type parameter.
     * @throws SchedulerException On error.
     */
    public <T extends Job> void schedulePeriodicJob(
        final String cronexp, final Class<T> clazz, final JobDataMap data
    ) throws SchedulerException {
        // Stamp scheduling-thread MDC trace context; see TracingJobWrapper.
        TracingJobWrapper.stampMdc(data);
        final JobDetail job = JobBuilder
            .newJob()
            .ofType(clazz)
            .withIdentity(String.format("%s-%s", cronexp, clazz.getCanonicalName()))
            .setJobData(data)
            .build();
        final Trigger trigger = TriggerBuilder.newTrigger()
            .withIdentity(
                String.format("trigger-%s", job.getKey()),
                "cron-group"
            )
            .withSchedule(CronScheduleBuilder.cronSchedule(cronexp))
            .forJob(job)
            .build();
        this.scheduler.scheduleJob(job, trigger);
    }

    /**
     * Purge every job currently registered under {@code group} from the
     * (possibly JDBC-shared) job store. Used once at JDBC boot to remove
     * jobs orphaned by a pre-2.3.0 deployment — see
     * {@link #STALE_EVENTS_JOB_GROUP}. Failure is logged and swallowed: a
     * purge that can't run must never block boot, and a future boot (on
     * this or another node) gets another chance.
     * @param group Job group name to purge
     */
    private void purgeStaleJobGroup(final String group) {
        try {
            final Set<JobKey> stale = this.scheduler.getJobKeys(
                GroupMatcher.jobGroupEquals(group)
            );
            if (!stale.isEmpty()) {
                this.scheduler.deleteJobs(new ArrayList<>(stale));
                EcsLogger.info("com.auto1.pantera.scheduling")
                    .message("Purged " + stale.size() + " stale QRTZ_* job(s) from group '"
                        + group + "' (orphaned by a pre-2.3.0 clustered events-drain deployment)")
                    .eventCategory("process")
                    .eventAction("quartz_stale_purge")
                    .eventOutcome("success")
                    .field("log.source", "application")
                    .log();
            }
        } catch (final SchedulerException error) {
            EcsLogger.error("com.auto1.pantera.scheduling")
                .message("Failed to purge stale Quartz job group '" + group + "'")
                .eventCategory("process")
                .eventAction("quartz_stale_purge")
                .eventOutcome("failure")
                .error(error)
                .field("log.source", "application")
                .log();
        }
    }

    /**
     * Delete quartz job by key.
     * @param key Job key
     */
    public void deleteJob(final JobKey key) {
        try {
            this.scheduler.deleteJob(key);
        } catch (final SchedulerException err) {
            EcsLogger.error("com.auto1.pantera.scheduling")
                .message("Error while deleting quartz job")
                .eventCategory("process")
                .eventAction("job_delete")
                .eventOutcome("failure")
                .field("process.name", key.toString())
                .error(err)
                .field("log.source", "application")
                .log();
        }
    }

    /**
     * Start quartz.
     */
    public void start() {
        try {
            this.scheduler.start();
        } catch (final SchedulerException error) {
            throw new PanteraException(error);
        }
    }

    /**
     * Stop scheduler.
     */
    public void stop() {
        if (this.stopped.compareAndSet(false, true)) {
            try {
                this.scheduler.shutdown(true);
            } catch (final SchedulerException exc) {
                throw new PanteraException(exc);
            }
        }
    }

    /**
     * Registers a JVM shutdown hook that gracefully shuts down the scheduler.
     */
    private void addShutdownHook() {
        Runtime.getRuntime().addShutdownHook(
            new Thread() {
                @Override
                public void run() {
                    if (QuartzService.this.stopped.compareAndSet(false, true)) {
                        try {
                            QuartzService.this.scheduler.shutdown();
                        } catch (final SchedulerException error) {
                            EcsLogger.error("com.auto1.pantera.scheduling")
                                .message("Failed to shutdown Quartz scheduler")
                                .eventCategory("process")
                                .eventAction("scheduler_shutdown")
                                .eventOutcome("failure")
                                .error(error)
                                .field("log.source", "application")
                                .log();
                        }
                    }
                }
            }
        );
    }

    /**
     * Build Quartz properties for JDBC-backed clustered mode.
     * @return Properties for StdSchedulerFactory
     */
    private static Properties jdbcProperties() {
        final Properties props = new Properties();
        // Scheduler identity
        props.setProperty(
            "org.quartz.scheduler.instanceName", QuartzService.SCHED_NAME
        );
        props.setProperty(
            "org.quartz.scheduler.instanceId", "AUTO"
        );
        // Thread pool
        props.setProperty(
            "org.quartz.threadPool.class",
            "org.quartz.simpl.SimpleThreadPool"
        );
        props.setProperty(
            "org.quartz.threadPool.threadCount", "10"
        );
        props.setProperty(
            "org.quartz.threadPool.threadPriority", "5"
        );
        // JobStore - JDBC with PostgreSQL
        props.setProperty(
            "org.quartz.jobStore.class",
            "org.quartz.impl.jdbcjobstore.JobStoreTX"
        );
        props.setProperty(
            "org.quartz.jobStore.driverDelegateClass",
            "org.quartz.impl.jdbcjobstore.PostgreSQLDelegate"
        );
        props.setProperty(
            "org.quartz.jobStore.dataSource",
            PanteraQuartzConnectionProvider.DS_NAME
        );
        props.setProperty(
            "org.quartz.jobStore.tablePrefix", "QRTZ_"
        );
        props.setProperty(
            "org.quartz.jobStore.isClustered", "true"
        );
        props.setProperty(
            "org.quartz.jobStore.clusterCheckinInterval", "15000"
        );
        props.setProperty(
            "org.quartz.jobStore.misfireThreshold", "60000"
        );
        return props;
    }

    /**
     * Checks if scheduler thread pool size allows to handle given `requested` amount
     * of parallel jobs. If thread pool size is smaller than `requested` value,
     * warning is logged and the smallest value is returned.
     * @param requested Requested amount of parallel jobs
     * @return The minimum of requested value and thread pool size
     * @throws SchedulerException On error
     */
    private int parallelJobs(final int requested) throws SchedulerException {
        final int count = Math.min(
            this.scheduler.getMetaData().getThreadPoolSize(), requested
        );
        if (requested > count) {
            EcsLogger.warn("com.auto1.pantera.scheduling")
                .message("Parallel quartz jobs amount limited to thread pool size (" + count + " threads, " + requested + " jobs requested)")
                .eventCategory("process")
                .eventAction("job_limit")
                .field("log.source", "application")
                .log();
        }
        return count;
    }

    /**
     * Log info about started job.
     * @param count Parallel count
     * @param clazz Job class name
     * @param seconds Scheduled interval
     */
    private void log(final int count, final String clazz, final int seconds) {
        EcsLogger.debug("com.auto1.pantera.scheduling")
            .message("Parallel jobs scheduled (" + count + " instances of " + clazz + ", interval: " + seconds + "s)")
            .eventCategory("process")
            .eventAction("job_schedule")
            .eventOutcome("success")
            .field("log.source", "application")
            .log();
    }

    /**
     * Construct job id.
     * @param id Id
     * @param item Job number
     * @return Full job id
     */
    private static String jobId(final String id, final int item) {
        return String.join("-", "job", id, String.valueOf(item));
    }

    /**
     * Construct trigger id.
     * @param id Id
     * @param item Job number
     * @return Full trigger id
     */
    private static String triggerId(final String id, final int item) {
        return String.join("-", "trigger", id, String.valueOf(item));
    }
}
