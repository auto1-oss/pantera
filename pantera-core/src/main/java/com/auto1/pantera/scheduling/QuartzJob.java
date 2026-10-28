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

import com.auto1.pantera.http.log.EcsLogger;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobKey;

/**
 * Super class for classes, which implement {@link Job} interface.
 * The class has some common useful methods to avoid code duplication.
 * @since 1.3
 */
public abstract class QuartzJob implements Job {

    /**
     * Skip this execution because the job's node-local dependencies (a
     * {@link JobDataRegistry}-resolved {@code Queue}/{@code Consumer}/
     * {@code Storage}, etc.) did not resolve.
     * <p>
     * Prior to 2.3.0 this deleted the job/trigger from the scheduler. Under
     * a clustered JDBC job store that is exactly backwards: Quartz does not
     * pin a repeating trigger to the node that created it, so any node's
     * scheduler thread can acquire and fire it. A node that never registered
     * the corresponding {@link JobDataRegistry} entries (i.e. every node
     * except the one that owns the queue) would find nothing to resolve here
     * on every firing it happened to acquire — and used to respond by
     * deleting the shared job/trigger outright, permanently orphaning
     * whichever node <em>did</em> own the data (see the WS2.2 fix, 2.3.0:
     * lost {@code artifact_publish} audit records and search-index rows).
     * <p>
     * The safe response is to skip this firing and leave the job/trigger
     * intact: the owning node's next acquisition of the same trigger
     * resolves normally and processes whatever accumulated in the interim.
     * Only in-memory event-queue draining is genuinely node-local; the
     * artifact-events pipeline no longer schedules through Quartz at all
     * (see {@code LocalEventDrainScheduler}) — this method now guards the
     * remaining per-repository proxy-package-processor jobs, which still use
     * the registry pattern.
     * @param context Job context
     */
    protected void stopJob(final JobExecutionContext context) {
        final JobKey key = context.getJobDetail().getKey();
        EcsLogger.warn("com.auto1.pantera.scheduling")
            .message("Job fired without its node-local dependencies resolved "
                + "(JobDataRegistry miss on this node) — skipping this execution; "
                + "job/trigger left intact for the owning node")
            .eventCategory("process")
            .eventAction("job_skip_unresolved")
            .eventOutcome("failure")
            .field("process.name", key.toString())
            .field("log.source", "application")
            .log();
    }
}
