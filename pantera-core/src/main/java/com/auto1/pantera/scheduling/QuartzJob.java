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
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobKey;
import org.quartz.SchedulerException;

/**
 * Super class for classes, which implement {@link Job} interface.
 * The class has some common useful methods to avoid code duplication.
 * @since 1.3
 */
public abstract class QuartzJob implements Job {

    /**
     * Stop the job and log error.
     * Uses {@code context.getScheduler()} to get the correct scheduler
     * instance (important for JDBC/clustered mode where
     * {@code new StdSchedulerFactory().getScheduler()} would return
     * a different default scheduler, making deleteJob a no-op on the
     * real JDBC store).
     * @param context Job context
     */
    protected void stopJob(final JobExecutionContext context) {
        final JobKey key = context.getJobDetail().getKey();
        try {
            EcsLogger.error("com.auto1.pantera.scheduling")
                .message("Job processing failed, stopping job (job=" + key + ")")
                .eventCategory("process")
                .eventAction("job_stop")
                .eventOutcome("failure")
                .log();
            context.getScheduler().deleteJob(key);
            EcsLogger.error("com.auto1.pantera.scheduling")
                .message("Job stopped (job=" + key + ")")
                .eventCategory("process")
                .eventAction("job_stop")
                .eventOutcome("success")
                .log();
        } catch (final SchedulerException error) {
            EcsLogger.error("com.auto1.pantera.scheduling")
                .message("Error while stopping job (job=" + key + ")")
                .eventCategory("process")
                .eventAction("job_stop")
                .eventOutcome("failure")
                .error(error)
                .log();
            throw new PanteraException(error);
        }
    }
}
