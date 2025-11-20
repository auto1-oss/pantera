/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.scheduling;

import com.artipie.ArtipieException;
import com.artipie.http.log.EcsLogger;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobKey;
import org.quartz.SchedulerException;
import org.quartz.impl.StdSchedulerFactory;

/**
 * Super class for classes, which implement {@link Job} interface.
 * The class has some common useful methods to avoid code duplication.
 * @since 1.3
 */
public abstract class QuartzJob implements Job {

    /**
     * Stop the job and log error.
     * @param context Job context
     */
    protected void stopJob(final JobExecutionContext context) {
        final JobKey key = context.getJobDetail().getKey();
        try {
            EcsLogger.error("com.artipie.scheduling")
                .message("Job processing failed, stopping job")
                .eventCategory("scheduling")
                .eventAction("job_stop")
                .eventOutcome("failure")
                .field("process.name", key.toString())
                .log();
            new StdSchedulerFactory().getScheduler().deleteJob(key);
            EcsLogger.error("com.artipie.scheduling")
                .message("Job stopped")
                .eventCategory("scheduling")
                .eventAction("job_stop")
                .eventOutcome("success")
                .field("process.name", key.toString())
                .log();
        } catch (final SchedulerException error) {
            EcsLogger.error("com.artipie.scheduling")
                .message("Error while stopping job")
                .eventCategory("scheduling")
                .eventAction("job_stop")
                .eventOutcome("failure")
                .field("process.name", key.toString())
                .error(error)
                .log();
            throw new ArtipieException(error);
        }
    }
}
