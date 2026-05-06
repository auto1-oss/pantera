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
package com.auto1.pantera.asto.events;

import com.auto1.pantera.PanteraException;
import com.auto1.pantera.asto.log.EcsLogger;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import org.quartz.JobBuilder;
import org.quartz.JobDataMap;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.SimpleTrigger;
import org.quartz.TriggerBuilder;
import org.quartz.impl.StdSchedulerFactory;

/**
 * Start quarts service.
 * @since 1.17
 */
public final class QuartsService {

    /**
     * Quartz scheduler.
     */
    private final Scheduler scheduler;

    /**
     * Ctor.
     */
    public QuartsService() {
        try {
            this.scheduler = new StdSchedulerFactory().getScheduler();
            Runtime.getRuntime().addShutdownHook(
                new Thread() {
                    @Override
                    public void run() {
                        try {
                            QuartsService.this.scheduler.shutdown();
                        } catch (final SchedulerException error) {
                            EcsLogger.error("com.auto1.pantera.asto")
                                .message("Scheduler shutdown failed")
                                .eventCategory("process")
                                .eventAction("scheduler_shutdown")
                                .eventOutcome("failure")
                                .error(error)
                                .log();
                        }
                    }
                }
            );
        } catch (final SchedulerException error) {
            throw new PanteraException(error);
        }
    }

    /**
     * Adds event processor to the quarts job. The job is repeating forever every
     * given seconds. If given parallel value is bigger than thread pool size, parallel jobs mode is
     * limited to thread pool size.
     * @param consumer How to consume the data collection
     * @param parallel How many jobs to run in parallel
     * @param seconds Seconds interval for scheduling
     * @param <T> Data item object type
     * @return Queue to add the events into
     * @throws SchedulerException On error
     */
    public <T> EventQueue<T> addPeriodicEventsProcessor(
        final Consumer<T> consumer, final int parallel, final int seconds
    ) throws SchedulerException {
        final EventQueue<T> queue = new EventQueue<>();
        final JobDataMap data = new JobDataMap();
        data.put("elements", queue);
        data.put("action", Objects.requireNonNull(consumer));
        final String id = String.join(
            "-", EventsProcessor.class.getSimpleName(), UUID.randomUUID().toString()
        );
        final TriggerBuilder<SimpleTrigger> trigger = TriggerBuilder.newTrigger()
            .startNow().withSchedule(SimpleScheduleBuilder.repeatSecondlyForever(seconds));
        final JobBuilder job = JobBuilder.newJob(EventsProcessor.class).setJobData(data);
        final int count = Math.min(this.scheduler.getMetaData().getThreadPoolSize(), parallel);
        if (parallel > count) {
            EcsLogger.warn("com.auto1.pantera.asto")
                .message("Parallel quartz jobs amount limited to thread pool size (requested: " + parallel + ", actual: " + count + ", pool size: " + count + ")")
                .eventCategory("process")
                .eventAction("job_schedule")
                .eventOutcome("success")
                .log();
        }
        for (int item = 0; item < count; item = item + 1) {
            this.scheduler.scheduleJob(
                job.withIdentity(
                    String.join("-", "job", id, String.valueOf(item)),
                    EventsProcessor.class.getSimpleName()
                ).build(),
                trigger.withIdentity(
                    String.join("-", "trigger", id, String.valueOf(item)),
                    EventsProcessor.class.getSimpleName()
                ).build()
            );
        }
        return queue;
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

}
