/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
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
                .message("Job processing failed, stopping job")
                .eventCategory("scheduling")
                .eventAction("job_stop")
                .eventOutcome("failure")
                .field("process.name", key.toString())
                .log();
            context.getScheduler().deleteJob(key);
            EcsLogger.error("com.auto1.pantera.scheduling")
                .message("Job stopped")
                .eventCategory("scheduling")
                .eventAction("job_stop")
                .eventOutcome("success")
                .field("process.name", key.toString())
                .log();
        } catch (final SchedulerException error) {
            EcsLogger.error("com.auto1.pantera.scheduling")
                .message("Error while stopping job")
                .eventCategory("scheduling")
                .eventAction("job_stop")
                .eventOutcome("failure")
                .field("process.name", key.toString())
                .error(error)
                .log();
            throw new PanteraException(error);
        }
    }
}
