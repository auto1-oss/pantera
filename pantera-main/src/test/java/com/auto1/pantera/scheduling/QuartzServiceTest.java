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

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.quartz.Job;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.SchedulerException;

/**
 * Test for {@link QuartzService}.
 * <p>
 * Artifact-events queue draining is no longer scheduled through
 * {@link QuartzService} — see {@link LocalEventDrainSchedulerTest} for that
 * mechanism's coverage (WS2.2, 2.3.0).
 * @since 1.3
 */
public final class QuartzServiceTest {

    /**
     * Quartz service to test.
     */
    private QuartzService service;

    @BeforeEach
    void init() {
        this.service = new QuartzService();
    }

    @AfterEach
    void stop() {
        this.service.stop();
    }

    @Test
    void runsGivenJobs() throws SchedulerException {
        final AtomicInteger count = new AtomicInteger();
        final JobDataMap data = new JobDataMap();
        data.put("cnt", count);
        this.service.schedulePeriodicJob(2, 3, TestJob.class, data);
        this.service.start();
        Awaitility.await().atMost(10, TimeUnit.SECONDS).until(() -> count.get() > 12);
    }

    @Test
    void doubleStopDoesNotThrow() {
        final QuartzService svc = new QuartzService();
        svc.start();
        Assertions.assertDoesNotThrow(
            () -> {
                svc.stop();
                svc.stop();
            },
            "Calling stop() twice must not throw an exception"
        );
    }

    /**
     * Test job.
     * @since 1.3
     */
    public static final class TestJob implements Job {

        /**
         * Count.
         */
        private AtomicInteger cnt;

        @Override
        public void execute(final JobExecutionContext context) throws JobExecutionException {
            this.cnt.incrementAndGet();
        }

        /**
         * Set count.
         * @param count Count
         */
        public void setCnt(final AtomicInteger count) {
            this.cnt = count;
        }
    }

}
